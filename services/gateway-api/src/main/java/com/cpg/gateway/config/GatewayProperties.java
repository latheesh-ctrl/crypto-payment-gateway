package com.cpg.gateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param mode which API keys this deployment accepts. Sandbox and live run as separate deployments with
 *             separate databases, so test money can never touch live balances.
 */
@Validated
@ConfigurationProperties("gateway")
public record GatewayProperties(
        @NotNull @DefaultValue("test") Mode mode,
        @NotNull Security security,
        @NotNull @DefaultValue Webhooks webhooks) {

    public enum Mode { test, live }

    /**
     * @param apiKeyPepper server-side secret mixed into API key hashes
     * @param adminToken   temporary bootstrap credential for {@code /internal/**}; blank disables those routes
     * @param dataEncryptionKey base64 AES-256 key for secrets the platform must read back (webhook secrets)
     */
    public record Security(@NotBlank String apiKeyPepper, String adminToken, @NotBlank String dataEncryptionKey) {
    }

    /**
     * @param schedulerEnabled run the dispatch and delivery loops in this process
     * @param maxAttempts      deliveries are abandoned after this many failed attempts (about three days)
     */
    public record Webhooks(@DefaultValue("true") boolean schedulerEnabled, @DefaultValue("16") int maxAttempts) {
    }
}
