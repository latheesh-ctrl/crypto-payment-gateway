package com.cpg.gateway.webhook;

import com.cpg.gateway.apikey.MerchantPrincipal;
import com.cpg.gateway.audit.AuditLog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/webhook_endpoints")
public class WebhookEndpointController {

    private final WebhookEndpointService endpoints;

    public WebhookEndpointController(WebhookEndpointService endpoints) {
        this.endpoints = endpoints;
    }

    /** The signing secret is returned only in this response. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedResponse create(@AuthenticationPrincipal MerchantPrincipal merchant,
                                  @Valid @RequestBody CreateRequest request) {
        var created = endpoints.create(merchant.merchantId(), request.url(), request.eventTypes(),
                AuditLog.Actor.merchantKey(merchant.apiKeyId()));
        WebhookEndpoint e = created.endpoint();
        return new CreatedResponse(e.id(), e.url(), e.eventTypes(), e.status(), e.createdAt(), created.secret());
    }

    @GetMapping
    public ListResponse list(@AuthenticationPrincipal MerchantPrincipal merchant) {
        return new ListResponse(endpoints.list(merchant.merchantId()));
    }

    @PostMapping("/{id}/disable")
    public WebhookEndpoint disable(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable String id) {
        return endpoints.disable(merchant.merchantId(), id, AuditLog.Actor.merchantKey(merchant.apiKeyId()));
    }

    /** The new secret is returned only in this response; the old one keeps signing for {@code overlap_hours}. */
    @PostMapping("/{id}/rotate_secret")
    public CreatedResponse rotateSecret(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable String id,
                                        @Valid @RequestBody(required = false) RotateRequest request) {
        int hours = request == null || request.overlapHours() == null ? 24 : request.overlapHours();
        var rotated = endpoints.rotateSecret(merchant.merchantId(), id, java.time.Duration.ofHours(hours),
                AuditLog.Actor.merchantKey(merchant.apiKeyId()));
        WebhookEndpoint e = rotated.endpoint();
        return new CreatedResponse(e.id(), e.url(), e.eventTypes(), e.status(), e.createdAt(), rotated.secret());
    }

    public record RotateRequest(@jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(168)
                                Integer overlapHours) {
    }

    public record CreateRequest(
            @NotBlank @Size(max = 2048) String url,
            @NotEmpty @Size(max = 50)
            List<@Pattern(regexp = "\\*|[a-z_]+\\.[a-z_]+", message = "must be '*' or an event type like user.created")
                    String> eventTypes) {
    }

    public record CreatedResponse(String id, String url, List<String> eventTypes, String status, Instant createdAt,
                                  String secret) {
    }

    public record ListResponse(List<WebhookEndpoint> data) {
    }
}
