package com.cpg.gateway.ledger;

import static com.cpg.gateway.ledger.Posting.Entry.credit;
import static com.cpg.gateway.ledger.Posting.Entry.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cpg.common.ApiException;
import com.cpg.gateway.IntegrationTest;
import com.cpg.gateway.merchant.MerchantService;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class LedgerServiceIT {

    private static final String USDT = "USDT_TRON";

    @Autowired LedgerService ledger;
    @Autowired MerchantService merchants;
    @Autowired JdbcClient jdbc;

    private String merchantId;

    @BeforeEach
    void createMerchant() {
        merchantId = merchants.create("Acme", UUID.randomUUID() + "@example.com", null).id();
    }

    private AccountRef pending() {
        return AccountRef.merchant(AccountKind.MERCHANT_PENDING, merchantId, USDT);
    }

    private AccountRef available() {
        return AccountRef.merchant(AccountKind.MERCHANT_AVAILABLE, merchantId, USDT);
    }

    private AccountRef depositWallets() {
        return AccountRef.platform(AccountKind.PLATFORM_DEPOSIT_WALLETS, USDT);
    }

    private Posting deposit(String reference, long amount) {
        return new Posting("deposit", reference, null, List.of(
                debit(depositWallets(), BigInteger.valueOf(amount)),
                credit(pending(), BigInteger.valueOf(amount))));
    }

    @Test
    void depositThenConfirmationMovesFundsFromPendingToAvailable() {
        ledger.post(deposit("dep-" + merchantId, 100_000_000));
        assertThat(ledger.balance(pending())).isEqualTo(BigInteger.valueOf(100_000_000));

        ledger.post(new Posting("confirm", "conf-" + merchantId, null, List.of(
                debit(pending(), BigInteger.valueOf(100_000_000)),
                credit(available(), BigInteger.valueOf(99_000_000)),
                credit(AccountRef.platform(AccountKind.PLATFORM_FEES, USDT), BigInteger.valueOf(1_000_000)))));

        assertThat(ledger.balance(pending())).isZero();
        assertThat(ledger.balance(available())).isEqualTo(BigInteger.valueOf(99_000_000));
    }

    @Test
    void samePostingReferenceIsRecordedOnlyOnce() {
        LedgerService.Result first = ledger.post(deposit("dup-" + merchantId, 5));
        LedgerService.Result second = ledger.post(deposit("dup-" + merchantId, 5));

        assertThat(second.replayed()).isTrue();
        assertThat(second.transactionId()).isEqualTo(first.transactionId());
        assertThat(ledger.balance(pending())).isEqualTo(BigInteger.valueOf(5));
    }

    @Test
    void unbalancedPostingIsRejected() {
        assertThatThrownBy(() -> new Posting("bad", "bad-" + merchantId, null, List.of(
                debit(depositWallets(), BigInteger.TEN),
                credit(pending(), BigInteger.ONE))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("differ");
    }

    @Test
    void merchantBalanceCannotGoNegative() {
        assertThatThrownBy(() -> ledger.post(new Posting("payout", "neg-" + merchantId, null, List.of(
                debit(available(), BigInteger.TEN),
                credit(AccountRef.platform(AccountKind.PLATFORM_HOT_WALLET, USDT), BigInteger.TEN)))))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("insufficient_balance");
        assertThat(jdbc.sql("SELECT count(*) FROM ledger_transactions WHERE reference = :r")
                .param("r", "neg-" + merchantId).query(Long.class).single()).isZero();
    }

    @Test
    void ledgerEntriesAreAppendOnly() {
        ledger.post(deposit("ro-" + merchantId, 7));
        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_entries SET amount = 1").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger_transactions").update())
                .hasMessageContaining("append-only");
    }

    @Test
    void databaseRejectsUnbalancedEntriesEvenIfCodeIsBypassed() {
        ledger.post(deposit("seed-" + merchantId, 1));
        String accountId = jdbc.sql("SELECT id FROM ledger_accounts WHERE owner_id = :m")
                .param("m", merchantId).query(String.class).list().getFirst();

        // Without an explicit transaction each statement commits on its own, so the deferred check fires now.
        jdbc.sql("INSERT INTO ledger_transactions (id, type, reference) VALUES ('ltx_raw', 'raw', :ref)")
                .param("ref", "raw-" + merchantId).update();
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO ledger_entries (transaction_id, account_id, asset_id, direction, amount)
                VALUES ('ltx_raw', :a, 'USDT_TRON', 'credit', 10)
                """).param("a", accountId).update())
                .hasMessageContaining("unbalanced");
    }

    @Test
    void concurrentPostingsToSameAccountsDoNotLoseUpdates() throws Exception {
        int threads = 16;
        int perThread = 25;
        var pool = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int thread = t;
            futures.add(pool.submit((Callable<Void>) () -> {
                for (int i = 0; i < perThread; i++) {
                    ledger.post(deposit("conc-" + merchantId + "-" + thread + "-" + i, 1));
                }
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.close();

        assertThat(ledger.balance(pending())).isEqualTo(BigInteger.valueOf((long) threads * perThread));
        String sum = jdbc.sql("""
                SELECT coalesce(sum(CASE WHEN direction = 'credit' THEN amount ELSE -amount END), 0)::text
                FROM ledger_entries e JOIN ledger_accounts a ON a.id = e.account_id
                WHERE a.owner_id = :m AND a.kind = 'pending'
                """).param("m", merchantId).query(String.class).single();
        assertThat(sum).isEqualTo(String.valueOf(threads * perThread));
    }
}
