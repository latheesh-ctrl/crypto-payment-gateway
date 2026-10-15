package com.cpg.gateway.payout;

import com.cpg.common.Money;
import com.cpg.gateway.web.JsonMaps;
import java.math.BigInteger;
import java.util.Map;

/** JSON shape of a payout for the API and webhooks. Amounts are decimal strings in the asset's units. */
public final class PayoutViews {

    private PayoutViews() {
    }

    public static Map<String, Object> merchantView(Payout p) {
        return JsonMaps.ordered(
                "id", p.id(),
                "object", "payout",
                "status", p.status(),
                "status_reason", p.statusReason(),
                "merchant_payout_id", p.merchantPayoutId(),
                "player_ref", p.playerRef(),
                "network", p.chain(),
                "asset", p.assetSymbol(),
                "asset_id", p.assetId(),
                "amount", amount(p, p.amount()),
                "fee", amount(p, p.fee()),
                "total_debited", amount(p, p.total()),
                "destination_address", p.destinationAddress(),
                "tx_hash", p.txHash(),
                "confirmations", p.confirmations(),
                "required_confirmations", p.requiredConfirmations(),
                "metadata", p.metadata(),
                "created_at", p.createdAt().toString(),
                "updated_at", p.updatedAt().toString(),
                "completed_at", p.completedAt() == null ? null : p.completedAt().toString());
    }

    private static String amount(Payout p, BigInteger units) {
        return new Money(p.assetId(), units).toDecimalString(p.decimals());
    }
}
