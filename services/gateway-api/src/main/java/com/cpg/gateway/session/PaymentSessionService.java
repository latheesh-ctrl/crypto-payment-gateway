package com.cpg.gateway.session;

import com.cpg.common.ApiException;
import com.cpg.common.Ids;
import com.cpg.common.Money;
import com.cpg.gateway.audit.AuditLog;
import com.cpg.gateway.deposit.DepositChanged;
import com.cpg.gateway.outbox.OutboxWriter;
import com.cpg.gateway.wallet.DepositAddressService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Payment sessions: a merchant order (player reference, order ID, amount, asset, network) with its own deposit
 * address and an expiry.
 *
 * <p>A session's status is always derived from the deposits made to its address, which follow the chain-verified
 * deposit lifecycle; nothing a browser or the merchant says can complete a session. The status is recomputed in
 * the same transaction as every deposit change and when a session expires:
 * <pre>
 * open ─► pending ─► confirming ─► completed | underpaid | overpaid | late_payment (paid after expiry)
 *   │                                   (a later top-up can still move underpaid ─► completed ─► overpaid)
 *   ├─► expired (nothing arrived in time; a later payment still makes it late_payment)
 *   └─► cancelled (by the merchant, only while nothing has arrived)
 * any ─► needs_review (verification mismatch, reorg after credit, or a payment in another asset)
 * a reorg before finality sends pending/confirming back to open or expired (event payment_session.reversed)
 * </pre>
 * Sessions always fund the merchant's liquidity: the merchant credits its player in its own wallet.
 */
@Service
public class PaymentSessionService {

    public static final Duration MIN_EXPIRY = Duration.ofMinutes(5);
    public static final Duration MAX_EXPIRY = Duration.ofHours(24);
    public static final Duration DEFAULT_EXPIRY = Duration.ofMinutes(30);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final DepositAddressService addresses;
    private final OutboxWriter outbox;
    private final AuditLog audit;
    private final JsonMapper json;
    private final SessionViews views;

    public PaymentSessionService(JdbcClient jdbc, DepositAddressService addresses, OutboxWriter outbox, AuditLog audit,
                                 JsonMapper json, SessionViews views) {
        this.jdbc = jdbc;
        this.addresses = addresses;
        this.outbox = outbox;
        this.audit = audit;
        this.json = json;
        this.views = views;
    }

    public record CreateRequest(String playerRef, String merchantOrderId, String network, String asset, String amount,
                                Duration expiresIn, String returnUrl, Map<String, Object> metadata) {
    }

    public record Created(PaymentSession session, boolean created) {
    }

    @Transactional
    public Created create(String merchantId, CreateRequest r, AuditLog.Actor actor) {
        String chain = r.network().toUpperCase();
        record Asset(String id, int decimals) {
        }
        Asset asset = jdbc.sql("""
                        SELECT a.id, a.decimals FROM assets a JOIN chains c ON c.code = a.chain_code
                        WHERE a.chain_code = :c AND a.symbol = :s AND a.enabled AND a.accept_payments AND c.enabled
                        """)
                .param("c", chain).param("s", r.asset().toUpperCase())
                .query((rs, i) -> new Asset(rs.getString(1), rs.getInt(2))).optional()
                .orElseThrow(() -> ApiException.badRequest("unsupported_asset",
                        r.asset() + " on " + r.network() + " is not accepted"));
        BigInteger amount;
        try {
            amount = Money.parse(asset.id(), r.amount(), asset.decimals()).minorUnits();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_amount", e.getMessage());
        }
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("invalid_amount", "amount must be greater than zero");
        }
        Duration expiresIn = r.expiresIn() == null ? DEFAULT_EXPIRY : r.expiresIn();
        if (expiresIn.compareTo(MIN_EXPIRY) < 0 || expiresIn.compareTo(MAX_EXPIRY) > 0) {
            throw ApiException.badRequest("invalid_expiry", "expires_in_seconds must be between 300 and 86400");
        }

        Optional<PaymentSession> existing = findByOrder(merchantId, r.merchantOrderId());
        if (existing.isPresent()) {
            return new Created(sameOrder(existing.get(), r.playerRef(), chain, asset.id(), amount), false);
        }

        String id = Ids.next("ps");
        DepositAddressService.DepositAddress address = addresses.getOrCreate(merchantId,
                DepositAddressService.OwnerType.session, id, chain, actor);
        int inserted = jdbc.sql("""
                        INSERT INTO payment_sessions (id, merchant_id, player_ref, merchant_order_id, chain_code,
                                                      asset_id, amount_expected, address_id, status, expires_at,
                                                      return_url, metadata)
                        VALUES (:id, :m, :player, :order, :chain, :asset, :amount, :address, 'open', :expires,
                                :returnUrl, CAST(:metadata AS jsonb))
                        ON CONFLICT (merchant_id, merchant_order_id) DO NOTHING
                        """)
                .param("id", id).param("m", merchantId).param("player", r.playerRef())
                .param("order", r.merchantOrderId()).param("chain", chain).param("asset", asset.id())
                .param("amount", new BigDecimal(amount)).param("address", address.id())
                .param("expires", Timestamp.from(Instant.now().plus(expiresIn)))
                .param("returnUrl", r.returnUrl())
                .param("metadata", json.writeValueAsString(r.metadata() == null ? Map.of() : r.metadata()))
                .update();
        if (inserted == 0) {
            // A concurrent request created the same order first; its address goes unused.
            PaymentSession winner = findByOrder(merchantId, r.merchantOrderId()).orElseThrow();
            return new Created(sameOrder(winner, r.playerRef(), chain, asset.id(), amount), false);
        }
        history(id, null, "open", null);
        audit.record(actor, "payment_session.created", "payment_session", id, merchantId,
                Map.of("merchant_order_id", r.merchantOrderId(), "player_ref", r.playerRef(), "chain", chain,
                        "asset", asset.id(), "amount", r.amount()));
        return new Created(require(id), true);
    }

    private static PaymentSession sameOrder(PaymentSession s, String playerRef, String chain, String assetId,
                                            BigInteger amount) {
        if (!s.playerRef().equals(playerRef) || !s.chain().equals(chain) || !s.assetId().equals(assetId)
                || !s.amountExpected().equals(amount)) {
            throw ApiException.conflict("order_conflict",
                    "merchant_order_id " + s.merchantOrderId() + " already exists with different details");
        }
        return s;
    }

    @Transactional
    public PaymentSession cancel(String merchantId, String id, AuditLog.Actor actor) {
        PaymentSession s = lock(id).filter(p -> p.merchantId().equals(merchantId))
                .orElseThrow(() -> ApiException.notFound("Payment session"));
        if (s.status().equals("cancelled")) {
            return s;
        }
        long deposits = jdbc.sql("SELECT count(*) FROM deposits WHERE owner_type = 'session' AND owner_id = :id")
                .param("id", id).query(Long.class).single();
        if (!s.status().equals("open") || deposits > 0) {
            throw ApiException.conflict("session_not_cancellable", "Only an open session with no payment can be cancelled");
        }
        setStatus(s, "cancelled", s.amountReceived(), "cancelled by merchant");
        audit.record(actor, "payment_session.cancelled", "payment_session", id, merchantId, Map.of());
        PaymentSession cancelled = require(id);
        outbox.write(merchantId, "payment_session", id, "payment_session.cancelled", views.merchantView(cancelled));
        return cancelled;
    }

    /** Keeps each session in step with the deposits to its address, inside the deposit's transaction. */
    @EventListener
    public void onDepositChanged(DepositChanged changed) {
        if (changed.deposit().ownerType().equals("session")) {
            refresh(changed.deposit().ownerId());
        }
    }

    /** Recomputes a session's status from its deposits and the clock; emits an event when anything changed. */
    @Transactional
    public PaymentSession refresh(String id) {
        PaymentSession s = lock(id).orElseThrow();
        record Payment(String status, String assetId, BigInteger amount, Instant detectedAt) {
        }
        List<Payment> payments = jdbc.sql("""
                        SELECT status, asset_id, amount, detected_at FROM deposits
                        WHERE owner_type = 'session' AND owner_id = :id
                        """)
                .param("id", id)
                .query((rs, i) -> new Payment(rs.getString(1), rs.getString(2),
                        rs.getBigDecimal(3).toBigIntegerExact(), rs.getTimestamp(4).toInstant()))
                .list();

        List<Payment> live = payments.stream()
                .filter(p -> !p.status().equals("reversed") && !p.status().equals("unsupported")).toList();
        boolean otherAsset = live.stream().anyMatch(p -> !s.assetId().equals(p.assetId()));
        List<Payment> ours = live.stream().filter(p -> s.assetId().equals(p.assetId())).toList();
        BigInteger received = ours.stream().filter(p -> p.status().equals("completed"))
                .map(Payment::amount).reduce(BigInteger.ZERO, BigInteger::add);
        boolean review = ours.stream().anyMatch(p -> p.status().equals("needs_review")) || otherAsset;
        boolean confirming = ours.stream().anyMatch(p -> p.status().equals("confirming"));
        boolean detected = ours.stream().anyMatch(p -> p.status().equals("detected"));

        String next;
        String reason = null;
        if (review) {
            next = "needs_review";
            reason = otherAsset ? "payment received in a different asset" : "a payment needs review";
        } else if (confirming || detected) {
            next = confirming ? "confirming" : "pending";
        } else if (received.signum() > 0) {
            Instant firstPayment = ours.stream().map(Payment::detectedAt).min(Instant::compareTo).orElseThrow();
            int cmp = received.compareTo(s.amountExpected());
            next = firstPayment.isAfter(s.expiresAt()) ? "late_payment"
                    : cmp == 0 ? "completed" : cmp < 0 ? "underpaid" : "overpaid";
        } else if (s.status().equals("cancelled")) {
            next = "cancelled";
        } else {
            next = Instant.now().isAfter(s.expiresAt()) ? "expired" : "open";
        }

        if (next.equals(s.status()) && received.equals(s.amountReceived())) {
            return s;
        }
        boolean reversed = (s.status().equals("pending") || s.status().equals("confirming"))
                && (next.equals("open") || next.equals("expired"));
        setStatus(s, next, received, reversed ? "payment reversed by a chain reorganisation" : reason);
        PaymentSession updated = require(id);
        outbox.write(updated.merchantId(), "payment_session", id,
                "payment_session." + (reversed ? "reversed" : next), views.merchantView(updated));
        return updated;
    }

    /** Sessions past their expiry with nothing paid. */
    public List<String> dueForExpiry(int limit) {
        return jdbc.sql("SELECT id FROM payment_sessions WHERE status = 'open' AND expires_at < now() ORDER BY expires_at LIMIT :l")
                .param("l", limit).query(String.class).list();
    }

    private void setStatus(PaymentSession s, String status, BigInteger received, String reason) {
        boolean paid = switch (status) {
            case "completed", "underpaid", "overpaid", "late_payment" -> true;
            default -> false;
        };
        jdbc.sql("""
                        UPDATE payment_sessions SET status = :status, amount_received = :received, updated_at = now(),
                               completed_at = CASE WHEN :paid THEN COALESCE(completed_at, now()) ELSE completed_at END
                        WHERE id = :id
                        """)
                .param("status", status).param("received", new BigDecimal(received)).param("paid", paid)
                .param("id", s.id()).update();
        if (!Objects.equals(status, s.status())) {
            history(s.id(), s.status(), status, reason);
        }
    }

    private void history(String id, String from, String to, String reason) {
        jdbc.sql("""
                        INSERT INTO payment_session_status_history (session_id, from_status, to_status, reason)
                        VALUES (:id, :f, :t, :r)
                        """)
                .param("id", id).param("f", from).param("t", to).param("r", reason).update();
    }

    // ---- Queries ---------------------------------------------------------------------------------------------

    private static final String SELECT = """
            SELECT s.*, a.symbol, a.decimals, d.address, c.default_confirmations
            FROM payment_sessions s
            JOIN assets a ON a.id = s.asset_id
            JOIN chains c ON c.code = s.chain_code
            JOIN deposit_addresses d ON d.id = s.address_id
            """;

    private RowMapper<PaymentSession> mapper() {
        return (rs, i) -> new PaymentSession(
                rs.getString("id"), rs.getString("merchant_id"), rs.getString("player_ref"),
                rs.getString("merchant_order_id"), rs.getString("chain_code"), rs.getString("asset_id"),
                rs.getString("symbol"), rs.getInt("decimals"),
                rs.getBigDecimal("amount_expected").toBigIntegerExact(),
                rs.getBigDecimal("amount_received").toBigIntegerExact(), rs.getString("address"),
                rs.getInt("default_confirmations"), rs.getString("status"),
                rs.getTimestamp("expires_at").toInstant(), rs.getString("return_url"),
                json.readValue(rs.getString("metadata"), MAP), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());
    }

    public PaymentSession require(String id) {
        return jdbc.sql(SELECT + " WHERE s.id = :id").param("id", id).query(mapper()).single();
    }

    public Optional<PaymentSession> find(String id) {
        return jdbc.sql(SELECT + " WHERE s.id = :id").param("id", id).query(mapper()).optional();
    }

    public PaymentSession requireForMerchant(String merchantId, String id) {
        return find(id).filter(s -> s.merchantId().equals(merchantId))
                .orElseThrow(() -> ApiException.notFound("Payment session"));
    }

    public Optional<PaymentSession> findByOrder(String merchantId, String merchantOrderId) {
        return jdbc.sql(SELECT + " WHERE s.merchant_id = :m AND s.merchant_order_id = :o")
                .param("m", merchantId).param("o", merchantOrderId).query(mapper()).optional();
    }

    public List<PaymentSession> list(String merchantId, String playerRef, String status, String startingAfter,
                                     int limit) {
        return jdbc.sql(SELECT + """
                         WHERE s.merchant_id = :m
                           AND (CAST(:player AS text) IS NULL OR s.player_ref = :player)
                           AND (CAST(:status AS text) IS NULL OR s.status = :status)
                           AND (CAST(:after AS text) IS NULL OR s.id < :after)
                         ORDER BY s.id DESC LIMIT :limit
                        """)
                .param("m", merchantId).param("player", playerRef).param("status", status)
                .param("after", startingAfter).param("limit", limit)
                .query(mapper()).list();
    }

    private Optional<PaymentSession> lock(String id) {
        jdbc.sql("SELECT id FROM payment_sessions WHERE id = :id FOR UPDATE").param("id", id).query(String.class).optional();
        return find(id);
    }
}
