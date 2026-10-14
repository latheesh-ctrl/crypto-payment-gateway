package com.cpg.gateway.apikey;

import com.cpg.gateway.audit.AuditLog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service key management (scope {@code keys:manage}). Rotation without downtime: roll the key, deploy the
 * new secrets, and the old key stops working when its overlap ends (or revoke it at once).
 */
@RestController
@RequestMapping("/v1/api_keys")
public class ApiKeyController {

    private final ApiKeyService apiKeys;

    public ApiKeyController(ApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
    }

    @GetMapping
    public KeyList list(@AuthenticationPrincipal MerchantPrincipal merchant) {
        return new KeyList(apiKeys.list(merchant.merchantId()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedApiKey create(@AuthenticationPrincipal MerchantPrincipal merchant,
                               @Valid @RequestBody CreateKeyRequest request) {
        Instant expires = request.expiresInDays() == null ? null
                : Instant.now().plus(Duration.ofDays(request.expiresInDays()));
        return apiKeys.issue(merchant.merchantId(),
                new ApiKeyService.KeySpec(request.name(), request.scopes(), request.ipAllowlist(), expires),
                merchant.scopes(), AuditLog.Actor.merchantKey(merchant.apiKeyId()));
    }

    @PostMapping("/{id}/roll")
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedApiKey roll(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable String id,
                             @Valid @RequestBody(required = false) RollKeyRequest request) {
        int hours = request == null || request.overlapHours() == null ? 24 : request.overlapHours();
        return apiKeys.roll(merchant.merchantId(), id, Duration.ofHours(hours), merchant.scopes(),
                AuditLog.Actor.merchantKey(merchant.apiKeyId()));
    }

    @PostMapping("/{id}/revoke")
    public ApiKeyService.ApiKey revoke(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable String id) {
        return apiKeys.revoke(merchant.merchantId(), id, AuditLog.Actor.merchantKey(merchant.apiKeyId()));
    }

    public record CreateKeyRequest(@NotBlank @Size(max = 100) String name, Set<String> scopes,
                                   @Size(max = 50) List<String> ipAllowlist,
                                   @Min(1) @Max(3650) Integer expiresInDays) {
    }

    public record RollKeyRequest(@Min(0) @Max(168) Integer overlapHours) {
    }

    public record KeyList(List<ApiKeyService.ApiKey> data) {
    }
}
