package com.cpg.gateway.ledger;

import com.cpg.common.ApiException;
import com.cpg.common.Ids;
import com.cpg.gateway.outbox.OutboxWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of the ledger. Each {@link Posting} is recorded atomically, exactly once per reference.
 *
 * <p>Concurrency: balance rows are locked with {@code SELECT ... FOR UPDATE} in account-id order, so
 * concurrent postings touching the same accounts serialise without deadlocking. The database also enforces
 * append-only rows and per-asset balance at commit (see V1 migration), as a second line of defence.
 */
@Service
public class LedgerService {

    private final JdbcClient jdbc;
    private final OutboxWriter outbox;

    public LedgerService(JdbcClient jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    public record Result(String transactionId, boolean replayed) {
    }

    @Transactional
    public Result post(Posting posting) {
        String txId = Ids.next("ltx");
        int inserted = jdbc.sql("""
                        INSERT INTO ledger_transactions (id, type, reference, description)
                        VALUES (:id, :type, :reference, :description)
                        ON CONFLICT (reference) DO NOTHING
                        """)
                .param("id", txId)
                .param("type", posting.type())
                .param("reference", posting.reference())
                .param("description", posting.description())
                .update();
        if (inserted == 0) {
            String existing = jdbc.sql("SELECT id FROM ledger_transactions WHERE reference = :reference")
                    .param("reference", posting.reference())
                    .query(String.class)
                    .single();
            return new Result(existing, true);
        }

        Map<AccountRef, String> accountIds = new LinkedHashMap<>();
        for (Posting.Entry entry : posting.entries()) {
            accountIds.computeIfAbsent(entry.account(), this::resolveAccount);
        }
        Map<String, BigInteger> balances = lockBalances(accountIds.values().stream().sorted().toList());

        for (Posting.Entry entry : posting.entries()) {
            AccountRef ref = entry.account();
            String accountId = accountIds.get(ref);
            jdbc.sql("""
                            INSERT INTO ledger_entries (transaction_id, account_id, asset_id, direction, amount)
                            VALUES (:tx, :account, :asset, :direction, :amount)
                            """)
                    .param("tx", txId)
                    .param("account", accountId)
                    .param("asset", ref.assetId())
                    .param("direction", entry.direction().name().toLowerCase())
                    .param("amount", new BigDecimal(entry.amount()))
                    .update();

            BigInteger delta = entry.direction() == ref.kind().normalBalance() ? entry.amount() : entry.amount().negate();
            balances.merge(accountId, delta, BigInteger::add);
        }

        for (Map.Entry<AccountRef, String> account : accountIds.entrySet()) {
            BigInteger balance = balances.get(account.getValue());
            if (balance.signum() < 0 && !account.getKey().kind().mayGoNegative()) {
                throw new ApiException(422, "insufficient_balance",
                        "Posting would make %s balance negative".formatted(account.getKey().kind().code()));
            }
            jdbc.sql("""
                            UPDATE ledger_balances SET balance = :balance, version = version + 1, updated_at = now()
                            WHERE account_id = :account
                            """)
                    .param("balance", new BigDecimal(balance))
                    .param("account", account.getValue())
                    .update();
        }

        outbox.write("ledger_transaction", txId, "ledger.transaction.posted", Map.of(
                "id", txId,
                "type", posting.type(),
                "reference", posting.reference()));
        return new Result(txId, false);
    }

    public BigInteger balance(AccountRef ref) {
        return findAccount(ref)
                .map(id -> jdbc.sql("SELECT balance FROM ledger_balances WHERE account_id = :id")
                        .param("id", id)
                        .query(BigDecimal.class)
                        .single()
                        .toBigIntegerExact())
                .orElse(BigInteger.ZERO);
    }

    private Optional<String> findAccount(AccountRef ref) {
        return jdbc.sql("""
                        SELECT id FROM ledger_accounts
                        WHERE owner_type = :ownerType AND owner_id = :ownerId AND asset_id = :asset AND kind = :kind
                        """)
                .param("ownerType", ref.kind().owner().name().toLowerCase())
                .param("ownerId", ref.ownerId())
                .param("asset", ref.assetId())
                .param("kind", ref.kind().code())
                .query(String.class)
                .optional();
    }

    private String resolveAccount(AccountRef ref) {
        String id = Ids.next("acct");
        jdbc.sql("""
                        INSERT INTO ledger_accounts (id, owner_type, owner_id, asset_id, kind, normal_balance)
                        VALUES (:id, :ownerType, :ownerId, :asset, :kind, :normal)
                        ON CONFLICT (owner_type, owner_id, asset_id, kind) DO NOTHING
                        """)
                .param("id", id)
                .param("ownerType", ref.kind().owner().name().toLowerCase())
                .param("ownerId", ref.ownerId())
                .param("asset", ref.assetId())
                .param("kind", ref.kind().code())
                .param("normal", ref.kind().normalBalance().name().toLowerCase())
                .update();
        String accountId = findAccount(ref).orElseThrow();
        jdbc.sql("INSERT INTO ledger_balances (account_id) VALUES (:id) ON CONFLICT DO NOTHING")
                .param("id", accountId)
                .update();
        return accountId;
    }

    private Map<String, BigInteger> lockBalances(List<String> sortedAccountIds) {
        Map<String, BigInteger> balances = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT account_id, balance FROM ledger_balances
                        WHERE account_id IN (:ids) ORDER BY account_id FOR UPDATE
                        """)
                .param("ids", sortedAccountIds)
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getBigDecimal(2).toBigIntegerExact()))
                .list()
                .stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(e -> balances.put(e.getKey(), e.getValue()));
        return balances;
    }
}
