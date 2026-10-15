package com.cpg.gateway.payout;

import com.cpg.common.ApiException;
import com.cpg.common.Money;
import com.cpg.gateway.audit.AuditLog;
import com.cpg.gateway.merchant.MerchantService;
import com.cpg.gateway.web.JsonMaps;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator endpoints: payout limits per merchant and asset, payout inspection, and review resolution. */
@RestController
@RequestMapping("/internal/v1")
public class InternalPayoutController {

    private static final AuditLog.Actor OPERATOR = AuditLog.Actor.operator("bootstrap-admin");

    private final JdbcClient jdbc;
    private final MerchantService merchants;
    private final PayoutService payouts;
    private final PayoutRails rails;
    private final AuditLog audit;

    public InternalPayoutController(JdbcClient jdbc, MerchantService merchants, PayoutService payouts,
                                    PayoutRails rails, AuditLog audit) {
        this.jdbc = jdbc;
        this.merchants = merchants;
        this.payouts = payouts;
        this.rails = rails;
        this.audit = audit;
    }

    /** Sets the limits for one asset; an omitted limit means none. */
    @PutMapping("/merchants/{merchantId}/payout_limits/{assetId}")
    @Transactional
    public Map<String, Object> setLimits(@PathVariable String merchantId, @PathVariable String assetId,
                                         @Valid @RequestBody LimitsRequest request) {
        merchants.require(merchantId);
        int decimals = jdbc.sql("SELECT decimals FROM assets WHERE id = :a").param("a", assetId)
                .query(Integer.class).optional().orElseThrow(() -> ApiException.notFound("Asset"));
        BigInteger perPayout = units(assetId, request.perPayout(), decimals);
        BigInteger perDay = units(assetId, request.perDay(), decimals);
        jdbc.sql("""
                        INSERT INTO merchant_payout_limits (merchant_id, asset_id, per_payout, per_day)
                        VALUES (:m, :a, :pp, :pd)
                        ON CONFLICT (merchant_id, asset_id) DO UPDATE SET per_payout = :pp, per_day = :pd
                        """)
                .param("m", merchantId).param("a", assetId)
                .param("pp", perPayout == null ? null : new BigDecimal(perPayout))
                .param("pd", perDay == null ? null : new BigDecimal(perDay)).update();
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("asset_id", assetId);
        changes.put("per_payout", request.perPayout());
        changes.put("per_day", request.perDay());
        audit.record(OPERATOR, "payout_limits.updated", "merchant", merchantId, merchantId, changes);
        return JsonMaps.ordered("merchant_id", merchantId, "asset_id", assetId,
                "per_payout", request.perPayout(), "per_day", request.perDay());
    }

    @DeleteMapping("/merchants/{merchantId}/payout_limits/{assetId}")
    public Map<String, Object> removeLimits(@PathVariable String merchantId, @PathVariable String assetId) {
        jdbc.sql("DELETE FROM merchant_payout_limits WHERE merchant_id = :m AND asset_id = :a")
                .param("m", merchantId).param("a", assetId).update();
        audit.record(OPERATOR, "payout_limits.removed", "merchant", merchantId, merchantId, Map.of("asset_id", assetId));
        return JsonMaps.ordered("merchant_id", merchantId, "asset_id", assetId, "deleted", true);
    }

    /** A payout with its status history and transaction, for investigation. */
    @GetMapping("/payouts/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        Payout p = payouts.require(id);
        Map<String, Object> view = new LinkedHashMap<>(PayoutViews.merchantView(p));
        view.put("merchant_id", p.merchantId());
        view.put("history", payouts.history(id));
        view.put("transaction", payouts.outbound(id).map(o -> JsonMaps.ordered(
                "tx_hash", o.txHash(), "from_address", o.fromAddress(), "nonce", o.nonce(),
                "broadcast_count", o.broadcastCount(),
                "first_broadcast_at", o.firstBroadcastAt() == null ? null : o.firstBroadcastAt().toString(),
                "last_broadcast_at", o.lastBroadcastAt() == null ? null : o.lastBroadcastAt().toString()))
                .orElse(null));
        return view;
    }

    /**
     * Fails a payout in review whose transaction can provably never be mined, releasing the merchant's funds.
     * The proof is checked live against the chain; anything less is refused.
     */
    @PostMapping("/payouts/{id}/resolve_failed")
    public Map<String, Object> resolveFailed(@PathVariable String id) {
        Payout p = payouts.require(id);
        PayoutService.Outbound out = payouts.outbound(id)
                .orElseThrow(() -> ApiException.conflict("payout_not_resolvable", "The payout has no transaction"));
        PayoutRail rail = rails.rail(p.chain())
                .orElseThrow(() -> new ApiException(503, "payouts_unavailable", "No payout rail for " + p.chain()));
        PayoutRail.TxStatus status = rail.status(out.txHash());
        long mined = rail.minedNonce();
        return PayoutViews.merchantView(payouts.resolveAsFailed(id, status, mined, OPERATOR));
    }

    private static BigInteger units(String assetId, String amount, int decimals) {
        if (amount == null) {
            return null;
        }
        try {
            BigInteger units = Money.parse(assetId, amount, decimals).minorUnits();
            if (units.signum() <= 0) {
                throw ApiException.badRequest("invalid_amount", "limits must be greater than zero");
            }
            return units;
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_amount", e.getMessage());
        }
    }

    public record LimitsRequest(
            @Pattern(regexp = "\\d+(\\.\\d+)?", message = "must be a positive decimal string") String perPayout,
            @Pattern(regexp = "\\d+(\\.\\d+)?", message = "must be a positive decimal string") String perDay) {
    }
}
