package com.cpg.gateway.payout;

import com.cpg.common.ApiException;
import com.cpg.common.Ids;
import com.cpg.common.Money;
import com.cpg.gateway.audit.AuditLog;
import com.cpg.gateway.ledger.AccountKind;
import com.cpg.gateway.ledger.AccountRef;
import com.cpg.gateway.ledger.LedgerService;
import com.cpg.gateway.ledger.Posting;
import com.cpg.gateway.merchant.Merchant;
import com.cpg.gateway.merchant.MerchantService;
import com.cpg.gateway.outbox.OutboxWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Payouts (cashouts) from a merchant's liquidity to a player's wallet.
 *
 * <pre>
 * awaiting_liquidity ─► processing ─► signing ─► broadcast ─► confirming ─► completed
 *        │                   │                       ▲             │
 *        └──── cancelled ◄───┘                       └─────────────┘ (reorg: back to broadcast, same bytes)
 * reverted on chain ─► failed (reservation released)
 * uncertain (not mined in time, nonce taken by another transaction, cannot broadcast) ─► needs_review
 * </pre>
 * Money: creating a payout reserves {@code amount + fee} by moving it from the merchant's liquidity to its
 * allocated liquidity, in the same transaction that checks the balance. Completion settles the reservation
 * against the hot wallet and fee revenue; failure or cancellation releases it. Exactly one transaction is ever
 * signed per payout (a unique constraint enforces it), so retries and uncertain outcomes can only re-send the same
 * bytes and never pay twice.
 */
@Service
public class PayoutService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final List<String> CANCELLABLE = List.of("awaiting_liquidity", "processing");

    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final MerchantService merchants;
    private final PayoutRails rails;
    private final DestinationAddresses destinations;
    private final OutboxWriter outbox;
    private final AuditLog audit;
    private final JsonMapper json;

    public PayoutService(JdbcClient jdbc, LedgerService ledger, MerchantService merchants, PayoutRails rails,
                         DestinationAddresses destinations, OutboxWriter outbox, AuditLog audit, JsonMapper json) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.merchants = merchants;
        this.rails = rails;
        this.destinations = destinations;
        this.outbox = outbox;
        this.audit = audit;
        this.json = json;
    }

    public record CreateRequest(String merchantPayoutId, String playerRef, String network, String asset, String amount,
                                String destinationAddress, Map<String, Object> metadata) {
    }

    public record Created(Payout payout, boolean created) {
    }

    // ---- Merchant operations -----------------------------------------------------------------------------------

    @Transactional
    public Created create(String merchantId, CreateRequest r, AuditLog.Actor actor) {
        Merchant merchant = merchants.require(merchantId);
        if (!merchant.merchantManagedBalances()) {
            throw ApiException.conflict("payouts_require_merchant_managed_balances",
                    "Payouts through this API are for merchants that keep their players' balances");
        }
        String chain = r.network().toUpperCase();
        record AssetRow(String id, int decimals, String contract, String family, int confirmations) {
        }
        AssetRow asset = jdbc.sql("""
                        SELECT a.id, a.decimals, a.contract_address, c.family, c.default_confirmations
                        FROM assets a JOIN chains c ON c.code = a.chain_code
                        WHERE a.chain_code = :c AND a.symbol = :s AND a.enabled AND c.enabled
                        """)
                .param("c", chain).param("s", r.asset().toUpperCase())
                .query((rs, i) -> new AssetRow(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5)))
                .optional()
                .orElseThrow(() -> ApiException.badRequest("unsupported_asset",
                        r.asset() + " on " + r.network() + " is not supported"));
        PayoutRail rail = rails.rail(chain).orElseThrow(() -> new ApiException(503, "payouts_unavailable",
                "Payouts on " + chain + " are not available"));

        BigInteger amount;
        try {
            amount = Money.parse(asset.id(), r.amount(), asset.decimals()).minorUnits();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_amount", e.getMessage());
        }
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("invalid_amount", "amount must be greater than zero");
        }
        String destination = destinations.normalize(chain, asset.family(), r.destinationAddress());
        boolean internal = destination.equalsIgnoreCase(rail.fromAddress())
                || jdbc.sql("SELECT count(*) FROM deposit_addresses WHERE chain_code = :c AND lower(address) = lower(:a)")
                        .param("c", chain).param("a", destination).query(Long.class).single() > 0;
        if (internal) {
            throw ApiException.badRequest("invalid_destination_address",
                    "The destination is a wallet of this platform; pay players to their own wallets");
        }

        Optional<Payout> existing = findByMerchantPayoutId(merchantId, r.merchantPayoutId());
        if (existing.isPresent()) {
            return new Created(samePayout(existing.get(), r.playerRef(), chain, asset.id(), amount, destination), false);
        }

        checkLimits(merchantId, asset.id(), amount);
        BigInteger fee = amount.multiply(BigInteger.valueOf(merchant.payoutFeeBps())).divide(BigInteger.valueOf(10_000));
        BigInteger total = amount.add(fee);
        BigInteger liquidity = ledger.lockedBalance(liquidity(merchantId, asset.id()));
        boolean reserve = liquidity.compareTo(total) >= 0;
        String status = reserve ? "processing" : "awaiting_liquidity";

        String id = Ids.next("po");
        int inserted = jdbc.sql("""
                        INSERT INTO payouts (id, merchant_id, player_ref, merchant_payout_id, chain_code, asset_id,
                                             destination_address, amount, fee, status, required_confirmations,
                                             reserved, metadata)
                        VALUES (:id, :m, :player, :mpid, :chain, :asset, :dest, :amount, :fee, :status, :confs,
                                :reserved, CAST(:metadata AS jsonb))
                        ON CONFLICT (merchant_id, merchant_payout_id) DO NOTHING
                        """)
                .param("id", id).param("m", merchantId).param("player", r.playerRef())
                .param("mpid", r.merchantPayoutId()).param("chain", chain).param("asset", asset.id())
                .param("dest", destination).param("amount", new BigDecimal(amount)).param("fee", new BigDecimal(fee))
                .param("status", status).param("confs", asset.confirmations()).param("reserved", reserve)
                .param("metadata", json.writeValueAsString(r.metadata() == null ? Map.of() : r.metadata()))
                .update();
        if (inserted == 0) {
            Payout winner = findByMerchantPayoutId(merchantId, r.merchantPayoutId()).orElseThrow();
            return new Created(samePayout(winner, r.playerRef(), chain, asset.id(), amount, destination), false);
        }
        Payout created = require(id);
        if (reserve) {
            postReservation(created);
        }
        history(id, null, status, reserve ? null : "waiting for merchant liquidity");
        audit.record(actor, "payout.created", "payout", id, merchantId, Map.of(
                "merchant_payout_id", r.merchantPayoutId(), "player_ref", r.playerRef(), "chain", chain,
                "asset", asset.id(), "amount", r.amount(), "destination_address", destination));
        outbox.write(merchantId, "payout", id, "payout." + status, PayoutViews.merchantView(created));
        return new Created(created, true);
    }

    private static Payout samePayout(Payout p, String playerRef, String chain, String assetId, BigInteger amount,
                                     String destination) {
        if (!p.playerRef().equals(playerRef) || !p.chain().equals(chain) || !p.assetId().equals(assetId)
                || !p.amount().equals(amount) || !p.destinationAddress().equalsIgnoreCase(destination)) {
            throw ApiException.conflict("payout_conflict",
                    "merchant_payout_id " + p.merchantPayoutId() + " already exists with different details");
        }
        return p;
    }

    /** Per-payout and rolling 24-hour limits; the limits row lock serialises concurrent payouts of one asset. */
    private void checkLimits(String merchantId, String assetId, BigInteger amount) {
        record Limits(BigInteger perPayout, BigInteger perDay) {
        }
        Optional<Limits> limits = jdbc.sql("""
                        SELECT per_payout, per_day FROM merchant_payout_limits
                        WHERE merchant_id = :m AND asset_id = :a FOR UPDATE
                        """)
                .param("m", merchantId).param("a", assetId)
                .query((rs, i) -> new Limits(units(rs.getBigDecimal(1)), units(rs.getBigDecimal(2))))
                .optional();
        if (limits.isEmpty()) {
            return;
        }
        if (limits.get().perPayout() != null && amount.compareTo(limits.get().perPayout()) > 0) {
            throw new ApiException(422, "payout_limit_exceeded", "amount is above the per-payout limit");
        }
        if (limits.get().perDay() != null) {
            BigInteger today = units(jdbc.sql("""
                            SELECT COALESCE(sum(amount), 0) FROM payouts
                            WHERE merchant_id = :m AND asset_id = :a AND created_at > now() - interval '24 hours'
                              AND status NOT IN ('cancelled', 'failed')
                            """)
                    .param("m", merchantId).param("a", assetId).query(BigDecimal.class).single());
            if (today.add(amount).compareTo(limits.get().perDay()) > 0) {
                throw new ApiException(422, "payout_limit_exceeded", "amount would exceed the 24-hour payout limit");
            }
        }
    }

    /** Cancels a payout that has not been signed yet and releases its reservation. */
    @Transactional
    public Payout cancel(String merchantId, String id, AuditLog.Actor actor) {
        Payout p = lock(id).filter(x -> x.merchantId().equals(merchantId))
                .orElseThrow(() -> ApiException.notFound("Payout"));
        if (p.status().equals("cancelled")) {
            return p;
        }
        if (!CANCELLABLE.contains(p.status())) {
            throw ApiException.conflict("payout_not_cancellable",
                    "A payout can be cancelled only before its transaction is signed");
        }
        if (p.reserved()) {
            postRelease(p);
        }
        setStatus(p, "cancelled", "cancelled by merchant");
        audit.record(actor, "payout.cancelled", "payout", id, merchantId, Map.of());
        return require(id);
    }

    // ---- Reservation -------------------------------------------------------------------------------------------

    /** Payouts waiting for liquidity, oldest first. */
    public List<String> awaitingLiquidity(int limit) {
        return jdbc.sql("SELECT id FROM payouts WHERE status = 'awaiting_liquidity' ORDER BY created_at LIMIT :l")
                .param("l", limit).query(String.class).list();
    }

    /** Reserves liquidity for a waiting payout if the merchant now has enough. */
    @Transactional
    public boolean tryReserve(String id) {
        Payout p = lock(id).orElseThrow();
        if (!p.status().equals("awaiting_liquidity")) {
            return false;
        }
        if (ledger.lockedBalance(liquidity(p.merchantId(), p.assetId())).compareTo(p.total()) < 0) {
            return false;
        }
        postReservation(p);
        jdbc.sql("UPDATE payouts SET reserved = TRUE, next_attempt_at = now() WHERE id = :id").param("id", id).update();
        setStatus(p, "processing", null);
        return true;
    }

    // ---- Worker steps (called by PayoutProcessor) --------------------------------------------------------------

    /** Leases up to {@code limit} payouts that are due for work. */
    @Transactional
    public List<String> claimDue(int limit, Duration lease) {
        return jdbc.sql("""
                        UPDATE payouts SET locked_until = now() + make_interval(secs => :lease)
                        WHERE id IN (
                            SELECT id FROM payouts
                            WHERE status IN ('processing', 'signing', 'broadcast', 'confirming', 'needs_review')
                              AND next_attempt_at <= now() AND (locked_until IS NULL OR locked_until < now())
                            ORDER BY next_attempt_at LIMIT :limit
                            FOR UPDATE SKIP LOCKED)
                        RETURNING id
                        """)
                .param("lease", lease.toSeconds()).param("limit", limit).query(String.class).list();
    }

    /** Ends the lease; {@code failed} counts an attempt so retries back off. */
    public void reschedule(String id, Duration delay, boolean failed) {
        jdbc.sql("""
                        UPDATE payouts SET locked_until = NULL, next_attempt_at = now() + make_interval(secs => :delay),
                                           attempts = attempts + CASE WHEN :failed THEN 1 ELSE 0 END
                        WHERE id = :id
                        """)
                .param("delay", (double) delay.toMillis() / 1000).param("failed", failed).param("id", id).update();
    }

    public int attempts(String id) {
        return jdbc.sql("SELECT attempts FROM payouts WHERE id = :id").param("id", id).query(Integer.class).single();
    }

    public record Outbound(String id, String payoutId, String chain, String fromAddress, Long nonce, String txHash,
                           String rawTx, int broadcastCount, Instant firstBroadcastAt, Instant lastBroadcastAt,
                           Instant createdAt) {
    }

    public Optional<Outbound> outbound(String payoutId) {
        return jdbc.sql("SELECT * FROM outbound_transactions WHERE payout_id = :p").param("p", payoutId)
                .query((rs, i) -> new Outbound(rs.getString("id"), rs.getString("payout_id"),
                        rs.getString("chain_code"), rs.getString("from_address"), (Long) rs.getObject("nonce"),
                        rs.getString("tx_hash"), rs.getString("raw_tx"), rs.getInt("broadcast_count"),
                        instant(rs.getTimestamp("first_broadcast_at")), instant(rs.getTimestamp("last_broadcast_at")),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    /**
     * Allocates the hot wallet's next nonce, signs the transfer and stores the signed bytes, all before anything is
     * broadcast. The nonce row lock serialises signing per wallet; the payout row lock and the unique
     * {@code outbound_transactions.payout_id} make sure a payout is signed at most once.
     *
     * @param chainPendingNonce the node's pending nonce, read before this transaction to keep RPC out of it
     * @return false when the payout is no longer waiting to be signed (cancelled, or another worker signed it)
     */
    @Transactional
    public boolean sign(String id, PayoutRail rail, long chainPendingNonce) {
        Payout p = lock(id).orElseThrow();
        if (!p.status().equals("processing") || outbound(id).isPresent()) {
            return false;
        }
        String from = rail.fromAddress();
        jdbc.sql("""
                        INSERT INTO hot_wallet_nonces (chain_code, address, next_nonce) VALUES (:c, :a, :n)
                        ON CONFLICT DO NOTHING
                        """)
                .param("c", p.chain()).param("a", from).param("n", chainPendingNonce).update();
        long stored = jdbc.sql("SELECT next_nonce FROM hot_wallet_nonces WHERE chain_code = :c AND address = :a FOR UPDATE")
                .param("c", p.chain()).param("a", from).query(Long.class).single();
        // Above the node's view when earlier payouts are signed but not yet in its mempool; the node's view wins
        // when something else used the wallet.
        long nonce = Math.max(stored, chainPendingNonce);

        PayoutRail.Signed signed = rail.sign(new PayoutRail.Transfer(p.destinationAddress(), p.tokenContract(),
                p.amount()), nonce);

        jdbc.sql("""
                        INSERT INTO outbound_transactions (id, payout_id, chain_code, from_address, nonce, tx_hash, raw_tx)
                        VALUES (:id, :p, :c, :from, :nonce, :hash, :raw)
                        """)
                .param("id", Ids.next("otx")).param("p", id).param("c", p.chain()).param("from", from)
                .param("nonce", nonce).param("hash", signed.txHash()).param("raw", signed.rawTx()).update();
        jdbc.sql("UPDATE hot_wallet_nonces SET next_nonce = :n WHERE chain_code = :c AND address = :a")
                .param("n", nonce + 1).param("c", p.chain()).param("a", from).update();
        jdbc.sql("UPDATE payouts SET tx_hash = :hash WHERE id = :id").param("hash", signed.txHash()).param("id", id)
                .update();
        setStatus(p, "signing", null);
        return true;
    }

    /** The node has the transaction: count the broadcast and move a freshly signed payout to {@code broadcast}. */
    @Transactional
    public void broadcasted(String id) {
        Payout p = lock(id).orElseThrow();
        jdbc.sql("""
                        UPDATE outbound_transactions SET broadcast_count = broadcast_count + 1, last_error = NULL,
                               first_broadcast_at = COALESCE(first_broadcast_at, now()), last_broadcast_at = now()
                        WHERE payout_id = :id
                        """)
                .param("id", id).update();
        if (p.status().equals("signing")) {
            setStatus(p, "broadcast", null);
        }
    }

    public void broadcastFailed(String id, String error) {
        jdbc.sql("UPDATE outbound_transactions SET last_error = :e, last_broadcast_at = now() WHERE payout_id = :id")
                .param("e", error == null ? null : error.substring(0, Math.min(error.length(), 1000)))
                .param("id", id).update();
    }

    /** Mined but not yet final. */
    @Transactional
    public void confirming(String id, long block, int confirmations) {
        Payout p = lock(id).orElseThrow();
        if (p.terminal()) {
            return;
        }
        jdbc.sql("UPDATE payouts SET block_number = :b, confirmations = :c, updated_at = now() WHERE id = :id")
                .param("b", block).param("c", confirmations).param("id", id).update();
        if (!p.status().equals("confirming")) {
            setStatus(p, "confirming", null);
        }
    }

    /** The transaction left the chain (reorganisation): it goes back to the mempool view and is re-sent as is. */
    @Transactional
    public void reorged(String id) {
        Payout p = lock(id).orElseThrow();
        if (p.status().equals("confirming")) {
            jdbc.sql("UPDATE payouts SET block_number = NULL, confirmations = 0 WHERE id = :id").param("id", id).update();
            setStatus(p, "broadcast", "transaction left the chain in a reorganisation; re-sending the same transaction");
        }
    }

    /** Final and successful: settle the reservation against the hot wallet and fee revenue. */
    @Transactional
    public void complete(String id, long block, int confirmations, BigInteger networkFee) {
        Payout p = lock(id).orElseThrow();
        if (p.terminal()) {
            return;
        }
        List<Posting.Entry> entries = new ArrayList<>();
        entries.add(Posting.Entry.debit(allocated(p.merchantId(), p.assetId()), p.total()));
        entries.add(Posting.Entry.credit(AccountRef.platform(AccountKind.PLATFORM_HOT_WALLET, p.assetId()), p.amount()));
        if (p.fee().signum() > 0) {
            entries.add(Posting.Entry.credit(AccountRef.platform(AccountKind.PLATFORM_FEES, p.assetId()), p.fee()));
        }
        ledger.post(new Posting("payout", "payout:settle:" + id, "Payout " + id + " to " + p.destinationAddress(),
                entries));
        postNetworkFee(p, networkFee);
        jdbc.sql("UPDATE payouts SET block_number = :b, confirmations = :c, reserved = FALSE WHERE id = :id")
                .param("b", block).param("c", confirmations).param("id", id).update();
        setStatus(p, "completed", null);
    }

    /** Final and reverted: nothing reached the player, so the reservation goes back to the merchant. */
    @Transactional
    public void failReverted(String id, long block, BigInteger networkFee) {
        Payout p = lock(id).orElseThrow();
        if (p.terminal()) {
            return;
        }
        postRelease(p);
        postNetworkFee(p, networkFee);
        jdbc.sql("UPDATE payouts SET block_number = :b, reserved = FALSE WHERE id = :id")
                .param("b", block).param("id", id).update();
        setStatus(p, "failed", "transaction reverted on chain");
    }

    /** Flags an uncertain outcome for an operator; the payout keeps being tracked and can still complete. */
    @Transactional
    public void flagForReview(String id, String reason) {
        Payout p = lock(id).orElseThrow();
        if (!p.terminal() && !p.status().equals("needs_review")) {
            setStatus(p, "needs_review", reason);
        }
    }

    /**
     * Operator resolution of a payout in review whose transaction provably can never be mined: the chain does not
     * know it and the wallet's mined nonce has moved past it. Releases the reservation.
     */
    @Transactional
    public Payout resolveAsFailed(String id, PayoutRail.TxStatus status, long minedNonce, AuditLog.Actor actor) {
        Payout p = lock(id).orElseThrow(() -> ApiException.notFound("Payout"));
        Outbound out = outbound(id).orElse(null);
        if (!p.status().equals("needs_review") || out == null || out.nonce() == null) {
            throw ApiException.conflict("payout_not_resolvable", "Only a payout in review with a sent transaction can be resolved");
        }
        if (status.state() != PayoutRail.State.NOT_FOUND || minedNonce <= out.nonce()) {
            throw ApiException.conflict("outcome_not_proven",
                    "The transaction may still be mined; it can be failed only once its nonce is used by another transaction");
        }
        postRelease(p);
        jdbc.sql("UPDATE payouts SET reserved = FALSE WHERE id = :id").param("id", id).update();
        setStatus(p, "failed", "nonce used by a different transaction; resolved by an operator");
        audit.record(actor, "payout.resolved_failed", "payout", id, p.merchantId(),
                Map.of("tx_hash", out.txHash(), "nonce", out.nonce(), "mined_nonce", minedNonce));
        return require(id);
    }

    // ---- Ledger ------------------------------------------------------------------------------------------------

    private void postReservation(Payout p) {
        ledger.post(new Posting("payout_reserve", "payout:reserve:" + p.id(), "Reserve liquidity for payout " + p.id(),
                List.of(Posting.Entry.debit(liquidity(p.merchantId(), p.assetId()), p.total()),
                        Posting.Entry.credit(allocated(p.merchantId(), p.assetId()), p.total()))));
    }

    private void postRelease(Payout p) {
        ledger.post(new Posting("payout_release", "payout:release:" + p.id(), "Release liquidity of payout " + p.id(),
                List.of(Posting.Entry.debit(allocated(p.merchantId(), p.assetId()), p.total()),
                        Posting.Entry.credit(liquidity(p.merchantId(), p.assetId()), p.total()))));
    }

    /** Gas comes out of the hot wallet's native coin and is a platform expense. */
    private void postNetworkFee(Payout p, BigInteger fee) {
        if (fee == null || fee.signum() <= 0) {
            return;
        }
        String nativeAsset = jdbc.sql("SELECT id FROM assets WHERE chain_code = :c AND contract_address IS NULL")
                .param("c", p.chain()).query(String.class).single();
        ledger.post(new Posting("payout_network_fee", "payout:gas:" + p.id(), "Network fee of payout " + p.id(),
                List.of(Posting.Entry.debit(AccountRef.platform(AccountKind.PLATFORM_NETWORK_FEE_EXPENSE, nativeAsset), fee),
                        Posting.Entry.credit(AccountRef.platform(AccountKind.PLATFORM_HOT_WALLET, nativeAsset), fee))));
        jdbc.sql("UPDATE outbound_transactions SET network_fee = :f WHERE payout_id = :id")
                .param("f", new BigDecimal(fee)).param("id", p.id()).update();
    }

    private static AccountRef liquidity(String merchantId, String assetId) {
        return AccountRef.merchant(AccountKind.MERCHANT_LIQUIDITY, merchantId, assetId);
    }

    private static AccountRef allocated(String merchantId, String assetId) {
        return AccountRef.merchant(AccountKind.MERCHANT_LIQUIDITY_ALLOCATED, merchantId, assetId);
    }

    // ---- Status ------------------------------------------------------------------------------------------------

    /** Records the transition and, except for the internal {@code signing} step, tells the merchant. */
    private void setStatus(Payout p, String status, String reason) {
        jdbc.sql("""
                        UPDATE payouts SET status = :s, status_reason = :r, updated_at = now(),
                               completed_at = CASE WHEN :s IN ('completed', 'failed', 'cancelled') THEN now() ELSE completed_at END
                        WHERE id = :id
                        """)
                .param("s", status).param("r", reason).param("id", p.id()).update();
        if (!Objects.equals(status, p.status())) {
            history(p.id(), p.status(), status, reason);
            if (!status.equals("signing")) {
                outbox.write(p.merchantId(), "payout", p.id(), "payout." + status, PayoutViews.merchantView(require(p.id())));
            }
        }
    }

    private void history(String id, String from, String to, String reason) {
        jdbc.sql("""
                        INSERT INTO payout_status_history (payout_id, from_status, to_status, reason)
                        VALUES (:id, :f, :t, :r)
                        """)
                .param("id", id).param("f", from).param("t", to).param("r", reason).update();
    }

    // ---- Queries -----------------------------------------------------------------------------------------------

    private static final String SELECT = """
            SELECT p.*, a.symbol, a.decimals, a.contract_address, c.family
            FROM payouts p
            JOIN assets a ON a.id = p.asset_id
            JOIN chains c ON c.code = p.chain_code
            """;

    private RowMapper<Payout> mapper() {
        return (rs, i) -> new Payout(
                rs.getString("id"), rs.getString("merchant_id"), rs.getString("player_ref"),
                rs.getString("merchant_payout_id"), rs.getString("chain_code"), rs.getString("family"),
                rs.getString("asset_id"), rs.getString("symbol"), rs.getInt("decimals"),
                rs.getString("contract_address"), rs.getString("destination_address"),
                units(rs.getBigDecimal("amount")), units(rs.getBigDecimal("fee")), rs.getString("status"),
                rs.getString("status_reason"), rs.getString("tx_hash"), (Long) rs.getObject("block_number"),
                rs.getInt("confirmations"), rs.getInt("required_confirmations"), rs.getBoolean("reserved"),
                json.readValue(rs.getString("metadata"), MAP), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), instant(rs.getTimestamp("completed_at")));
    }

    public Payout require(String id) {
        return find(id).orElseThrow(() -> ApiException.notFound("Payout"));
    }

    public Optional<Payout> find(String id) {
        return jdbc.sql(SELECT + " WHERE p.id = :id").param("id", id).query(mapper()).optional();
    }

    public Payout requireForMerchant(String merchantId, String id) {
        return find(id).filter(p -> p.merchantId().equals(merchantId)).orElseThrow(() -> ApiException.notFound("Payout"));
    }

    public Optional<Payout> findByMerchantPayoutId(String merchantId, String merchantPayoutId) {
        return jdbc.sql(SELECT + " WHERE p.merchant_id = :m AND p.merchant_payout_id = :o")
                .param("m", merchantId).param("o", merchantPayoutId).query(mapper()).optional();
    }

    public List<Payout> list(String merchantId, String playerRef, String status, String startingAfter, int limit) {
        return jdbc.sql(SELECT + """
                         WHERE p.merchant_id = :m
                           AND (CAST(:player AS text) IS NULL OR p.player_ref = :player)
                           AND (CAST(:status AS text) IS NULL OR p.status = :status)
                           AND (CAST(:after AS text) IS NULL OR p.id < :after)
                         ORDER BY p.id DESC LIMIT :limit
                        """)
                .param("m", merchantId).param("player", playerRef).param("status", status)
                .param("after", startingAfter).param("limit", limit)
                .query(mapper()).list();
    }

    /** Status history, oldest first, for operators and the merchant's audit trail. */
    public List<Map<String, Object>> history(String id) {
        return jdbc.sql("""
                        SELECT from_status, to_status, reason, created_at FROM payout_status_history
                        WHERE payout_id = :id ORDER BY id
                        """)
                .param("id", id)
                .query((rs, i) -> com.cpg.gateway.web.JsonMaps.ordered(
                        "from", rs.getString(1), "to", rs.getString(2), "reason", rs.getString(3),
                        "at", rs.getTimestamp(4).toInstant().toString()))
                .list();
    }

    private Optional<Payout> lock(String id) {
        jdbc.sql("SELECT id FROM payouts WHERE id = :id FOR UPDATE").param("id", id).query(String.class).optional();
        return find(id);
    }

    private static BigInteger units(BigDecimal value) {
        return value == null ? null : value.toBigIntegerExact();
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
