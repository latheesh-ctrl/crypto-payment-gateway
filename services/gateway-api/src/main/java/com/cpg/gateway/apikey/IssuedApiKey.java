package com.cpg.gateway.apikey;

/** Returned once at creation. {@code secret} is never retrievable again. */
public record IssuedApiKey(String id, String merchantId, String mode, String name, String secret, String last4) {
}
