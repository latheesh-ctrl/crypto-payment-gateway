package com.cpg.gateway.deposit;

import com.cpg.common.ApiException;
import com.cpg.common.Ids;
import com.cpg.gateway.ledger.AccountKind;
import com.cpg.gateway.ledger.AccountRef;
import com.cpg.gateway.ledger.LedgerService;
import com.cpg.gateway.ledger.Posting;
import com.cpg.gateway.outbox.OutboxWriter;
import com.cpg.gateway.wallet.AddressCodec;
import com.cpg.gateway.wallet.DepositAddressService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deposit lifecycle: detected → confirming → completed, or unsupported / reversed / needs_review.
 *
 * <p>Rules: nothing is credited before the chain's finality threshold is met and an independent source has
 * verified the transfer; each on-chain transfer (chain, tx hash, output index) becomes at most one deposit and
 * at most one ledger posting; every transition is guarded on the expected current status and recorded in
 * {@code deposit_status_history}.
 */
@Service
public class DepositService {

    private static final Logger log = LoggerFactory.getLogger(DepositService.class);

    private static final RowMapper<Deposit> MAPPER = (rs, i) -> new Deposit(
            rs.getString("id"),
            rs.getString("merchant_id"),
            rs.getString("owner_type"),
            rs.getString("owner_id"),
            rs.getString("chain_code"),
            rs.getString("address"),
            rs.getString("asset_id"),
            rs.getString("token_contract"),
            rs.getBigDecimal("amount").toBigIntegerExact(),
            rs.getBigDecimal("fee").toBigIntegerExact(),
            rs.getString("tx_hash"),
            rs.getInt("output_index"),
            (Long) rs.getObject("block_number"),
            rs.getString("block_hash"),
            rs.getInt("confirmations"),
            rs.getInt("required_confirmations"),
            rs.getString("status"),
            rs.getString("status_reason"),
            rs.getBoolean("simulated"),
            rs.getTimestamp("detected_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());

    private static final String SELECT = """
            SELECT d.*, a.address FROM deposits d JOIN deposit_addresses a ON a.id = d.address_id
            """;

    private final JdbcClient jdbc;
    private final DepositAddressService addresses;
    private final LedgerService ledger;
    private final OutboxWriter outbox;
    private final TransferVerifier verifier;
    private final ApplicationEventPublisher events;

    public DepositService(JdbcClient jdbc, DepositAddressService addresses, LedgerService ledger, OutboxWriter outbox,
                          TransferVerifier verifier, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.addresses = addresses;
        this.ledger = ledger;
        this.outbox = outbox;
        this.verifier = verifier;
        this.events = events;
    }

    // ---- Chain events ----------------------------------------------------------------------------------------

    /**
     * Records a transfer to one of our addresses. Idempotent: the same transfer reported again returns the
     * existing deposit unchanged. Transfers to addresses we don't own are ignored.
     */
    @Transactional
    public Optional<Deposit> onTransferObserved(ObservedTransfer t) {
        return record(t, false);
    }

    /** Sandbox only: records a transfer that did not happen on chain; it skips independent verification. */
    @Transactional
    public Optional<Deposit> simulate(ObservedTransfer t) {
        return record(t, true);
    }

    private Optional<Deposit> record(ObservedTransfer t, boolean simulated) {
        String family = jdbc.sql("SELECT family FROM chains WHERE code = :c").param("c", t.chain())
                .query(String.class).optional().orElseThrow(() -> new IllegalArgumentException("unknown chain " + t.chain()));
        String toAddress = "evm".equals(family) ? AddressCodec.normalizeEvm(t.toAddress()) : t.toAddress();
        Optional<DepositAddressService.DepositAddress> owner = addresses.findByAddress(t.chain(), toAddress);
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        Optional<Deposit> existing = findByTransfer(t.chain(), t.txHash(), t.outputIndex());
        if (existing.isPresent()) {
            if (existing.get().status().equals("reversed") && t.blockNumber() != null && !simulated) {
                return Optional.of(reinclude(existing.get(), t));
            }
            return existing;
        }

        record AssetMatch(String id, boolean acceptPayments) {
        }
        Optional<AssetMatch> asset = jdbc.sql("""
                        SELECT id, accept_payments AND enabled FROM assets
                        WHERE chain_code = :c AND (
                              (CAST(:contract AS text) IS NULL AND contract_address IS NULL)
                           OR (:family = 'evm' AND lower(contract_address) = lower(:contract))
                           OR (:family <> 'evm' AND contract_address = :contract))
                        """)
                .param("c", t.chain())
                .param("contract", t.tokenContract())
                .param("family", family)
                .query((rs, i) -> new AssetMatch(rs.getString(1), rs.getBoolean(2)))
                .optional();
        int required = jdbc.sql("SELECT default_confirmations FROM chains WHERE code = :c")
                .param("c", t.chain()).query(Integer.class).single();

        boolean supported = asset.isPresent() && asset.get().acceptPayments() && t.amount().signum() > 0;
        String status = supported ? "detected" : "unsupported";
        String reason = supported ? null
                : asset.isEmpty() ? "token not supported on this chain"
                : t.amount().signum() == 0 ? "zero amount" : "asset not accepted for payments";
        if (supported && !simulated) {
            // The same transaction mined again in another block can move its log index (EVM). If it was already
            // credited from the old block, a person must decide; it is never credited twice automatically.
            Optional<String> credited = jdbc.sql("""
                            SELECT id FROM deposits
                            WHERE chain_code = :c AND tx_hash = :tx AND status IN ('completed', 'needs_review')
                              AND block_hash IS DISTINCT FROM :hash AND address_id = :addressId
                            LIMIT 1
                            """)
                    .param("c", t.chain()).param("tx", t.txHash()).param("hash", t.blockHash())
                    .param("addressId", owner.get().id())
                    .query(String.class).optional();
            if (credited.isPresent()) {
                status = "needs_review";
                reason = "transaction re-included after a reorg; already handled as " + credited.get();
            }
        }

        var a = owner.get();
        String id = Ids.next("dep");
        int inserted = jdbc.sql("""
                        INSERT INTO deposits (id, merchant_id, owner_type, owner_id, address_id, chain_code, asset_id,
                                              token_contract, amount, tx_hash, output_index, block_number, block_hash,
                                              required_confirmations, status, status_reason, simulated)
                        VALUES (:id, :merchantId, :ownerType, :ownerId, :addressId, :chain, :asset, :contract, :amount,
                                :tx, :idx, :block, :blockHash, :required, :status, :reason, :simulated)
                        ON CONFLICT (chain_code, tx_hash, output_index) DO NOTHING
                        """)
                .param("id", id)
                .param("merchantId", a.merchantId())
                .param("ownerType", a.ownerType().name())
                .param("ownerId", a.ownerId())
                .param("addressId", a.id())
                .param("chain", t.chain())
                .param("asset", supported ? asset.get().id() : asset.map(AssetMatch::id).orElse(null))
                .param("contract", t.tokenContract())
                .param("amount", new BigDecimal(t.amount()))
                .param("tx", t.txHash())
                .param("idx", t.outputIndex())
                .param("block", t.blockNumber())
                .param("blockHash", t.blockHash())
                .param("required", required)
                .param("status", status)
                .param("reason", reason)
                .param("simulated", simulated)
                .update();
        if (inserted == 0) {
            return findByTransfer(t.chain(), t.txHash(), t.outputIndex());
        }
        history(id, null, status, reason);
        Deposit deposit = require(id);
        event(deposit, "deposit." + status);
        return Optional.of(deposit);
    }

    /** A reversed transfer was mined again (after a reorg): it starts over from detected in its new block. */
    private Deposit reinclude(Deposit d, ObservedTransfer t) {
        jdbc.sql("""
                        UPDATE deposits SET block_number = :block, block_hash = :hash, confirmations = 0,
                               updated_at = now()
                        WHERE id = :id AND status = 'reversed'
                        """)
                .param("block", t.blockNumber()).param("hash", t.blockHash()).param("id", d.id()).update();
        transition(d.id(), "reversed", "detected", "re-included in block " + t.blockNumber());
        Deposit revived = require(d.id());
        event(revived, "deposit.detected");
        return revived;
    }

    /**
     * Updates confirmation depth. When the chain's threshold is reached and independent verification passes, the
     * deposit completes and is credited exactly once.
     */
    @Transactional
    public Optional<Deposit> onConfirmations(String chain, String txHash, int outputIndex, long blockNumber,
                                             String blockHash, int confirmations) {
        Optional<Deposit> found = findByTransferForUpdate(chain, txHash, outputIndex);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Deposit d = found.get();
        if (!d.status().equals("detected") && !d.status().equals("confirming")) {
            return found;
        }
        jdbc.sql("""
                        UPDATE deposits SET confirmations = :conf, block_number = :block, block_hash = :hash,
                               updated_at = now()
                        WHERE id = :id
                        """)
                .param("conf", confirmations).param("block", blockNumber).param("hash", blockHash).param("id", d.id())
                .update();
        if (d.status().equals("detected") && confirmations >= 1) {
            transition(d.id(), "detected", "confirming", null);
            events.publishEvent(new DepositChanged(require(d.id())));
        }
        if (confirmations >= d.requiredConfirmations()) {
            tryComplete(require(d.id()));
        }
        return Optional.of(require(d.id()));
    }

    /** The transaction left the canonical chain (reorg) or was dropped from the mempool. */
    @Transactional
    public Optional<Deposit> onReorged(String chain, String txHash, int outputIndex, String reason) {
        Optional<Deposit> found = findByTransferForUpdate(chain, txHash, outputIndex);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Deposit d = found.get();
        switch (d.status()) {
            case "detected", "confirming" -> {
                transition(d.id(), d.status(), "reversed", reason);
                event(require(d.id()), "deposit.reversed");
            }
            case "completed" -> {
                // Should be impossible with correct finality rules. Credits are never silently undone: an operator
                // investigates and posts a compensating entry if needed.
                log.error("Completed deposit {} reported as reorged on {}: {}", d.id(), chain, reason);
                transition(d.id(), "completed", "needs_review", "reorg after completion: " + reason);
                event(require(d.id()), "deposit.needs_review");
            }
            default -> {
                // reversed, unsupported, needs_review: nothing further to do
            }
        }
        return Optional.of(require(d.id()));
    }

    /** Re-attempts verification for deposits that reached finality while the verifier was unavailable. */
    @Transactional
    public void retryVerification(String depositId) {
        Deposit d = jdbc.sql(SELECT + " WHERE d.id = :id FOR UPDATE OF d").param("id", depositId)
                .query(MAPPER).optional().orElseThrow();
        if (d.status().equals("confirming") && d.confirmations() >= d.requiredConfirmations()) {
            tryComplete(d);
        }
    }

    private void tryComplete(Deposit d) {
        TransferVerifier.Result result = d.simulated() ? TransferVerifier.Result.CONFIRMED : verifier.verify(d);
        switch (result) {
            case UNAVAILABLE -> log.warn("Deposit {} reached finality but verification is unavailable; will retry", d.id());
            case MISMATCH -> {
                transition(d.id(), d.status(), "needs_review", "independent verification mismatch");
                event(require(d.id()), "deposit.needs_review");
            }
            case CONFIRMED -> complete(d);
        }
    }

    private void complete(Deposit d) {
        if (d.status().equals("detected")) {
            transition(d.id(), "detected", "confirming", null);
        }
        BigInteger amount = d.amount();
        // Player money (a user deposit or a payment session) carries the merchant's deposit fee; a merchant
        // topping up its own liquidity does not.
        boolean playerDeposit = !d.ownerType().equals("merchant");
        // Only user deposits of platform-managed merchants create a player balance here. Merchants that keep player
        // balances themselves (games), and every payment session, get liquidity only; the player is credited in
        // the merchant's own wallet when it receives the webhook.
        boolean platformKeepsPlayerBalance = d.ownerType().equals("user") && !merchantManagedBalances(d.merchantId());
        BigInteger fee = playerDeposit ? amount.multiply(BigInteger.valueOf(depositFeeBps(d.merchantId())))
                .divide(BigInteger.valueOf(10_000)) : BigInteger.ZERO;
        String asset = d.assetId();

        List<Posting.Entry> entries = new ArrayList<>();
        entries.add(Posting.Entry.debit(AccountRef.platform(AccountKind.PLATFORM_DEPOSIT_WALLETS, asset), amount));
        addCredit(entries, AccountRef.merchant(AccountKind.MERCHANT_LIQUIDITY, d.merchantId(), asset), amount.subtract(fee));
        addCredit(entries, AccountRef.platform(AccountKind.PLATFORM_FEES, asset), fee);
        if (platformKeepsPlayerBalance) {
            // The user is credited the full amount; the merchant owes it to them and bears the fee.
            entries.add(Posting.Entry.debit(
                    AccountRef.merchant(AccountKind.MERCHANT_USER_OBLIGATIONS, d.merchantId(), asset), amount));
            entries.add(Posting.Entry.credit(AccountRef.user(AccountKind.USER_AVAILABLE, d.ownerId(), asset), amount));
        }
        String postingType = switch (d.ownerType()) {
            case "user" -> "user_deposit";
            case "session" -> "session_payment";
            default -> "merchant_payin";
        };
        LedgerService.Result posted = ledger.post(new Posting(postingType,
                "deposit:" + d.chain() + ":" + d.txHash() + ":" + d.outputIndex(),
                "Deposit " + d.id(), entries));

        jdbc.sql("""
                        UPDATE deposits SET status = 'completed', fee = :fee, ledger_transaction_id = :ltx,
                               completed_at = now(), updated_at = now()
                        WHERE id = :id AND status = 'confirming'
                        """)
                .param("fee", new BigDecimal(fee)).param("ltx", posted.transactionId()).param("id", d.id())
                .update();
        history(d.id(), "confirming", "completed", null);
        event(require(d.id()), "deposit.completed");
    }

    private static void addCredit(List<Posting.Entry> entries, AccountRef account, BigInteger amount) {
        if (amount.signum() > 0) {
            entries.add(Posting.Entry.credit(account, amount));
        }
    }

    private boolean merchantManagedBalances(String merchantId) {
        return jdbc.sql("SELECT player_balances = 'merchant_managed' FROM merchants WHERE id = :id")
                .param("id", merchantId).query(Boolean.class).single();
    }

    private int depositFeeBps(String merchantId) {
        return jdbc.sql("SELECT deposit_fee_bps FROM merchants WHERE id = :id").param("id", merchantId)
                .query(Integer.class).single();
    }

    // ---- Queries ---------------------------------------------------------------------------------------------

    public Deposit require(String id) {
        return jdbc.sql(SELECT + " WHERE d.id = :id").param("id", id).query(MAPPER).single();
    }

    public Deposit requireForMerchant(String merchantId, String id) {
        return jdbc.sql(SELECT + " WHERE d.id = :id AND d.merchant_id = :m").param("id", id).param("m", merchantId)
                .query(MAPPER).optional().orElseThrow(() -> ApiException.notFound("Deposit"));
    }

    public List<Deposit> list(String merchantId, String ownerType, String ownerId, String status, String startingAfter,
                              int limit) {
        return jdbc.sql(SELECT + """
                         WHERE d.merchant_id = :m
                           AND (CAST(:ownerType AS text) IS NULL OR (d.owner_type = :ownerType AND d.owner_id = :ownerId))
                           AND (CAST(:status AS text) IS NULL OR d.status = :status)
                           AND (CAST(:after AS text) IS NULL OR d.id < :after)
                         ORDER BY d.id DESC LIMIT :limit
                        """)
                .param("m", merchantId)
                .param("ownerType", ownerType)
                .param("ownerId", ownerId)
                .param("status", status)
                .param("after", startingAfter)
                .param("limit", limit)
                .query(MAPPER).list();
    }

    public List<String> awaitingVerification(int limit) {
        return jdbc.sql("""
                        SELECT id FROM deposits
                        WHERE status = 'confirming' AND confirmations >= required_confirmations
                        ORDER BY updated_at LIMIT :limit
                        """)
                .param("limit", limit).query(String.class).list();
    }

    private Optional<Deposit> findByTransfer(String chain, String txHash, int outputIndex) {
        return jdbc.sql(SELECT + " WHERE d.chain_code = :c AND d.tx_hash = :tx AND d.output_index = :idx")
                .param("c", chain).param("tx", txHash).param("idx", outputIndex).query(MAPPER).optional();
    }

    private Optional<Deposit> findByTransferForUpdate(String chain, String txHash, int outputIndex) {
        return jdbc.sql(SELECT + " WHERE d.chain_code = :c AND d.tx_hash = :tx AND d.output_index = :idx FOR UPDATE OF d")
                .param("c", chain).param("tx", txHash).param("idx", outputIndex).query(MAPPER).optional();
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------

    private void transition(String id, String from, String to, String reason) {
        int updated = jdbc.sql("""
                        UPDATE deposits SET status = :to, status_reason = :reason, updated_at = now()
                        WHERE id = :id AND status = :from
                        """)
                .param("to", to).param("reason", reason).param("id", id).param("from", from).update();
        if (updated != 1) {
            throw new IllegalStateException("deposit " + id + " is no longer " + from);
        }
        history(id, from, to, reason);
    }

    private void history(String id, String from, String to, String reason) {
        jdbc.sql("INSERT INTO deposit_status_history (deposit_id, from_status, to_status, reason) VALUES (:id, :f, :t, :r)")
                .param("id", id).param("f", from).param("t", to).param("r", reason).update();
    }

    private void event(Deposit d, String type) {
        outbox.write(d.merchantId(), "deposit", d.id(), type, DepositView.of(d, decimals(d.assetId())).toMap());
        events.publishEvent(new DepositChanged(d));
    }

    int decimals(String assetId) {
        return assetId == null ? 0 : jdbc.sql("SELECT decimals FROM assets WHERE id = :id").param("id", assetId)
                .query(Integer.class).single();
    }

    static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
