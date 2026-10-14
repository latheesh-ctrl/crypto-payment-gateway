package com.cpg.gateway.merchant;

import com.cpg.gateway.apikey.ApiKeyService;
import com.cpg.gateway.apikey.IssuedApiKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator endpoints used to bootstrap merchants until self-serve signup and the admin console exist.
 * Protected by the admin credential; never exposed on the public ingress.
 */
@RestController
@RequestMapping("/internal/v1/merchants")
public class InternalMerchantController {

    private final MerchantService merchants;
    private final ApiKeyService apiKeys;

    public InternalMerchantController(MerchantService merchants, ApiKeyService apiKeys) {
        this.merchants = merchants;
        this.apiKeys = apiKeys;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Merchant create(@Valid @RequestBody CreateMerchantRequest request) {
        return merchants.create(request.name(), request.email(), request.country());
    }

    @GetMapping("/{id}")
    public Merchant get(@PathVariable String id) {
        return merchants.require(id);
    }

    @PostMapping("/{id}/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedApiKey createApiKey(@PathVariable String id, @Valid @RequestBody CreateApiKeyRequest request) {
        merchants.require(id);
        return apiKeys.issue(id, request.name());
    }

    public record CreateMerchantRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Email @Size(max = 320) String email,
            @Pattern(regexp = "[A-Z]{2}", message = "must be an ISO 3166-1 alpha-2 code") String country) {
    }

    public record CreateApiKeyRequest(@NotBlank @Size(max = 100) String name) {
    }
}
