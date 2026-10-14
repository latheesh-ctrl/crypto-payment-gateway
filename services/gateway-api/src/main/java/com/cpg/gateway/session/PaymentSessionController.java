package com.cpg.gateway.session;

import com.cpg.gateway.apikey.MerchantPrincipal;
import com.cpg.gateway.audit.AuditLog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
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

/** Merchant API for payment sessions: create, look up by ID or merchant order ID, list, cancel. */
@RestController
@Validated
@RequestMapping("/v1/payment_sessions")
public class PaymentSessionController {

    private final PaymentSessionService sessions;
    private final SessionViews views;

    public PaymentSessionController(PaymentSessionService sessions, SessionViews views) {
        this.sessions = sessions;
        this.views = views;
    }

    /** 201 for a new session; 200 with the existing session when the same order is submitted again. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@AuthenticationPrincipal MerchantPrincipal merchant,
                                                      @Valid @RequestBody CreateSessionRequest request) {
        PaymentSessionService.Created created = sessions.create(merchant.merchantId(),
                new PaymentSessionService.CreateRequest(request.playerRef(), request.merchantOrderId(),
                        request.network(), request.asset(), request.amount(),
                        request.expiresInSeconds() == null ? null : Duration.ofSeconds(request.expiresInSeconds()),
                        request.returnUrl(), request.metadata()),
                AuditLog.Actor.merchantKey(merchant.apiKeyId()));
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(views.merchantView(created.session()));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable String id) {
        return views.merchantView(sessions.requireForMerchant(merchant.merchantId(), id));
    }

    @GetMapping
    public SessionList list(@AuthenticationPrincipal MerchantPrincipal merchant,
                            @RequestParam(name = "merchant_order_id", required = false) String merchantOrderId,
                            @RequestParam(name = "player_ref", required = false) String playerRef,
                            @RequestParam(required = false) String status,
                            @RequestParam(name = "starting_after", required = false) String startingAfter,
                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        if (merchantOrderId != null) {
            return new SessionList(sessions.findByOrder(merchant.merchantId(), merchantOrderId)
                    .map(views::merchantView).stream().toList(), false);
        }
        List<PaymentSession> page = sessions.list(merchant.merchantId(), playerRef, status, startingAfter, limit + 1);
        boolean hasMore = page.size() > limit;
        return new SessionList((hasMore ? page.subList(0, limit) : page).stream().map(views::merchantView).toList(),
                hasMore);
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable String id) {
        return views.merchantView(sessions.cancel(merchant.merchantId(), id,
                AuditLog.Actor.merchantKey(merchant.apiKeyId())));
    }

    public record CreateSessionRequest(
            @NotBlank @Size(max = 128) String playerRef,
            @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9._:-]+",
                    message = "may contain letters, digits and . _ : -") String merchantOrderId,
            @NotBlank String network,
            @NotBlank String asset,
            @NotBlank @Pattern(regexp = "\\d+(\\.\\d+)?", message = "must be a positive decimal string") String amount,
            Integer expiresInSeconds,
            @Size(max = 2048) @Pattern(regexp = "https?://.+", message = "must be an http(s) URL") String returnUrl,
            Map<String, Object> metadata) {
    }

    public record SessionList(List<Map<String, Object>> data, boolean hasMore) {
    }
}
