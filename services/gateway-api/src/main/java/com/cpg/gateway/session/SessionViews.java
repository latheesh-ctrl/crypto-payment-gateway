package com.cpg.gateway.session;

import com.cpg.common.Money;
import com.cpg.gateway.web.JsonMaps;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** JSON shapes of a session: the full merchant view (API and webhooks) and the minimal hosted-page view. */
@Component
public class SessionViews {

    private final JdbcClient jdbc;
    private final CheckoutTokens tokens;
    private final CheckoutProperties checkout;

    public SessionViews(JdbcClient jdbc, CheckoutTokens tokens, CheckoutProperties checkout) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.checkout = checkout;
    }

    public String paymentUrl(PaymentSession s) {
        String base = checkout.baseUrl().endsWith("/") ? checkout.baseUrl() : checkout.baseUrl() + "/";
        return base + "s/" + s.id() + "#t=" + tokens.tokenFor(s.id());
    }

    public Map<String, Object> merchantView(PaymentSession s) {
        return JsonMaps.ordered(
                "id", s.id(),
                "object", "payment_session",
                "status", s.status(),
                "player_ref", s.playerRef(),
                "merchant_order_id", s.merchantOrderId(),
                "network", s.chain(),
                "asset", s.assetSymbol(),
                "asset_id", s.assetId(),
                "amount", amount(s, s.amountExpected()),
                "amount_received", amount(s, s.amountReceived()),
                "address", s.address(),
                "required_confirmations", s.requiredConfirmations(),
                "payment_url", paymentUrl(s),
                "return_url", s.returnUrl(),
                "metadata", s.metadata(),
                "payments", payments(s),
                "expires_at", s.expiresAt().toString(),
                "created_at", s.createdAt().toString(),
                "updated_at", s.updatedAt().toString(),
                "completed_at", s.completedAt() == null ? null : s.completedAt().toString());
    }

    /** What the hosted page may show: no merchant, player or order data. */
    public Map<String, Object> checkoutView(PaymentSession s) {
        int confirmations = jdbc.sql("""
                        SELECT COALESCE(max(confirmations), 0) FROM deposits
                        WHERE owner_type = 'session' AND owner_id = :id AND status IN ('detected', 'confirming')
                        """)
                .param("id", s.id()).query(Integer.class).single();
        String network = jdbc.sql("SELECT name FROM chains WHERE code = :c").param("c", s.chain())
                .query(String.class).single();
        return JsonMaps.ordered(
                "id", s.id(),
                "status", s.status(),
                "network", s.chain(),
                "network_name", network,
                "asset", s.assetSymbol(),
                "token_contract", tokenContract(s),
                "amount", amount(s, s.amountExpected()),
                "amount_received", amount(s, s.amountReceived()),
                "address", s.address(),
                "confirmations", confirmations,
                "required_confirmations", s.requiredConfirmations(),
                "expires_at", s.expiresAt().toString(),
                "return_url", s.returnUrl());
    }

    private String tokenContract(PaymentSession s) {
        return jdbc.sql("SELECT contract_address FROM assets WHERE id = :a").param("a", s.assetId())
                .query(String.class).optional().orElse(null);
    }

    private List<Map<String, Object>> payments(PaymentSession s) {
        return jdbc.sql("""
                        SELECT d.id, d.tx_hash, d.amount, d.asset_id, d.status, d.confirmations, d.detected_at,
                               COALESCE(a.decimals, 0) AS decimals
                        FROM deposits d LEFT JOIN assets a ON a.id = d.asset_id
                        WHERE d.owner_type = 'session' AND d.owner_id = :id
                        ORDER BY d.detected_at
                        """)
                .param("id", s.id())
                .query((rs, i) -> JsonMaps.ordered(
                        "deposit_id", rs.getString("id"),
                        "tx_hash", rs.getString("tx_hash"),
                        "asset_id", rs.getString("asset_id"),
                        "amount", new Money(String.valueOf(rs.getString("asset_id")),
                                rs.getBigDecimal("amount").toBigIntegerExact()).toDecimalString(rs.getInt("decimals")),
                        "status", rs.getString("status"),
                        "confirmations", rs.getInt("confirmations"),
                        "detected_at", rs.getTimestamp("detected_at").toInstant().toString()))
                .list();
    }

    private static String amount(PaymentSession s, java.math.BigInteger units) {
        return new Money(s.assetId(), units).toDecimalString(s.decimals());
    }
}
