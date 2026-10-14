package com.cpg.gateway.deposit;

import com.cpg.common.Money;
import java.time.Instant;
import java.util.Map;

/** Public representation of a deposit: amounts as decimal strings in whole units of the asset. */
public record DepositView(
        String id,
        String type,
        String userId,
        String paymentSessionId,
        String chain,
        String asset,
        String address,
        String amount,
        String fee,
        String txHash,
        int outputIndex,
        int confirmations,
        int requiredConfirmations,
        String status,
        String statusReason,
        boolean simulated,
        Instant detectedAt,
        Instant completedAt) {

    public static DepositView of(Deposit d, int decimals) {
        String asset = d.assetId() == null ? "unknown" : d.assetId();
        return new DepositView(
                d.id(),
                switch (d.ownerType()) {
                    case "user" -> "user_deposit";
                    case "session" -> "session_payment";
                    default -> "merchant_payin";
                },
                d.ownerType().equals("user") ? d.ownerId() : null,
                d.ownerType().equals("session") ? d.ownerId() : null,
                d.chain(),
                d.assetId(),
                d.address(),
                new Money(asset, d.amount()).toDecimalString(decimals),
                new Money(asset, d.fee()).toDecimalString(decimals),
                d.txHash(),
                d.outputIndex(),
                d.confirmations(),
                d.requiredConfirmations(),
                d.status(),
                d.statusReason(),
                d.simulated(),
                d.detectedAt(),
                d.completedAt());
    }

    public Map<String, Object> toMap() {
        return DepositService.mapOf(
                "id", id, "type", type, "user_id", userId, "payment_session_id", paymentSessionId, "chain", chain, "asset", asset, "address", address,
                "amount", amount, "fee", fee, "tx_hash", txHash, "output_index", outputIndex,
                "confirmations", confirmations, "required_confirmations", requiredConfirmations, "status", status,
                "status_reason", statusReason, "simulated", simulated, "detected_at", String.valueOf(detectedAt),
                "completed_at", completedAt == null ? null : completedAt.toString());
    }
}
