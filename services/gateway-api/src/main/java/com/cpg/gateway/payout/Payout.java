package com.cpg.gateway.payout;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;

/**
 * A cashout to a player's wallet, paid from the merchant's liquidity.
 *
 * @param amount the amount the player receives
 * @param fee    the platform fee, charged to the merchant's liquidity on top of {@code amount}
 */
public record Payout(
        String id,
        String merchantId,
        String playerRef,
        String merchantPayoutId,
        String chain,
        String family,
        String assetId,
        String assetSymbol,
        int decimals,
        String tokenContract,
        String destinationAddress,
        BigInteger amount,
        BigInteger fee,
        String status,
        String statusReason,
        String txHash,
        Long blockNumber,
        int confirmations,
        int requiredConfirmations,
        boolean reserved,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public BigInteger total() {
        return amount.add(fee);
    }

    public boolean terminal() {
        return switch (status) {
            case "completed", "failed", "cancelled" -> true;
            default -> false;
        };
    }
}
