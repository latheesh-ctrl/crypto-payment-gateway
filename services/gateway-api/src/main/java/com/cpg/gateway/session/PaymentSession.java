package com.cpg.gateway.session;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;

/** A deposit request bound to one merchant order: player, amount, asset, network and its own address. */
public record PaymentSession(
        String id,
        String merchantId,
        String playerRef,
        String merchantOrderId,
        String chain,
        String assetId,
        String assetSymbol,
        int decimals,
        BigInteger amountExpected,
        BigInteger amountReceived,
        String address,
        int requiredConfirmations,
        String status,
        Instant expiresAt,
        String returnUrl,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    /** States in which the player has paid and the merchant should credit {@code amountReceived}. */
    public boolean paid() {
        return switch (status) {
            case "completed", "underpaid", "overpaid", "late_payment" -> true;
            default -> false;
        };
    }
}
