package com.cpg.gateway.merchant;

import java.time.Instant;

/**
 * @param playerBalances        {@code platform_managed} (the gateway keeps a balance per player) or
 *                              {@code merchant_managed} (the merchant, e.g. a game, keeps them)
 * @param requireSignedRequests require request signatures on test keys too (always required on live keys)
 * @param rateLimitPerSecond    sustained request rate allowed per API key
 */
public record Merchant(String id, String name, String email, String status, String kycStatus, String country,
                       String playerBalances, boolean requireSignedRequests, int rateLimitPerSecond,
                       Instant createdAt) {

    public boolean merchantManagedBalances() {
        return "merchant_managed".equals(playerBalances);
    }
}
