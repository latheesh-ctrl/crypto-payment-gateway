package com.cpg.gateway.payout;

import com.cpg.gateway.apikey.MerchantPrincipal;
import com.cpg.gateway.audit.AuditLog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Merchant API for payouts (cashouts): create, look up by ID or merchant payout ID, list, cancel. */
@RestController
@Validated
@RequestMapping("/v1/payouts")
public class PayoutController {

    private final PayoutService payouts;

    public PayoutController(PayoutService payouts) {
        this.payouts = payouts;
    }

    /** 201 for a new payout; 200 with the existing payout when the same merchant payout ID is sent again. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@AuthenticationPrincipal MerchantPrincipal merchant,
                                                      @Valid @RequestBody CreatePayoutRequest request) {
        PayoutService.Created created = payouts.create(merchant.merchantId(),
                new PayoutService.CreateRequest(request.merchantPayoutId(), request.playerRef(), request.network(),
                        request.asset(), request.amount(), request.destinationAddress(), request.metadata()),
                AuditLog.Actor.merchantKey(merchant.apiKeyId()));
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(PayoutViews.merchantView(created.payout()));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable String id) {
        return PayoutViews.merchantView(payouts.requireForMerchant(merchant.merchantId(), id));
    }

    @GetMapping
    public PayoutList list(@AuthenticationPrincipal MerchantPrincipal merchant,
                           @RequestParam(name = "merchant_payout_id", required = false) String merchantPayoutId,
                           @RequestParam(name = "player_ref", required = false) String playerRef,
                           @RequestParam(required = false) String status,
                           @RequestParam(name = "starting_after", required = false) String startingAfter,
                           @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        if (merchantPayoutId != null) {
            return new PayoutList(payouts.findByMerchantPayoutId(merchant.merchantId(), merchantPayoutId)
                    .map(PayoutViews::merchantView).stream().toList(), false);
        }
        List<Payout> page = payouts.list(merchant.merchantId(), playerRef, status, startingAfter, limit + 1);
        boolean hasMore = page.size() > limit;
        return new PayoutList((hasMore ? page.subList(0, limit) : page).stream().map(PayoutViews::merchantView).toList(),
                hasMore);
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable String id) {
        return PayoutViews.merchantView(payouts.cancel(merchant.merchantId(), id,
                AuditLog.Actor.merchantKey(merchant.apiKeyId())));
    }

    public record CreatePayoutRequest(
            @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._:-]+",
                    message = "may contain letters, digits and . _ : -") String merchantPayoutId,
            @NotBlank @Size(max = 128) String playerRef,
            @NotBlank String network,
            @NotBlank String asset,
            @NotBlank @Pattern(regexp = "\\d+(\\.\\d+)?", message = "must be a positive decimal string") String amount,
            @NotBlank @Size(max = 128) String destinationAddress,
            Map<String, Object> metadata) {
    }

    public record PayoutList(List<Map<String, Object>> data, boolean hasMore) {
    }
}
