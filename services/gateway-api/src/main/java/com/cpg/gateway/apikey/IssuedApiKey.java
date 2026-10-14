package com.cpg.gateway.apikey;

import java.time.Instant;
import java.util.List;

/** Returned once at creation. {@code secret} and {@code signingSecret} are never retrievable again. */
public record IssuedApiKey(String id, String merchantId, String mode, String name, String secret,
                           String signingSecret, String last4, List<String> scopes, List<String> ipAllowlist,
                           Instant expiresAt) {
}
