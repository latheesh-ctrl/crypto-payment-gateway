package com.cpg.gateway.deposit;

import java.math.BigInteger;
import java.time.Instant;

public record Deposit(
        String id,
        String merchantId,
        String ownerType,
        String ownerId,
        String chain,
        String address,
        String assetId,
        String tokenContract,
        BigInteger amount,
        BigInteger fee,
        String txHash,
        int outputIndex,
        Long blockNumber,
        String blockHash,
        int confirmations,
        int requiredConfirmations,
        String status,
        String statusReason,
        boolean simulated,
        Instant detectedAt,
        Instant completedAt) {
}
