package com.cpg.gateway.ledger;

import com.cpg.common.Money;
import com.cpg.gateway.apikey.MerchantPrincipal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BalanceController {

    private final JdbcClient jdbc;

    public BalanceController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/balances")
    public BalanceList balances(@AuthenticationPrincipal MerchantPrincipal merchant) {
        List<Balance> rows = jdbc.sql("""
                        SELECT a.id AS asset_id, a.decimals,
                               coalesce(sum(b.balance) FILTER (WHERE la.kind = 'available'), 0) AS available,
                               coalesce(sum(b.balance) FILTER (WHERE la.kind = 'pending'), 0)   AS pending
                        FROM ledger_accounts la
                        JOIN ledger_balances b ON b.account_id = la.id
                        JOIN assets a ON a.id = la.asset_id
                        WHERE la.owner_type = 'merchant' AND la.owner_id = :merchant
                        GROUP BY a.id, a.decimals
                        ORDER BY a.id
                        """)
                .param("merchant", merchant.merchantId())
                .query((rs, i) -> {
                    String asset = rs.getString("asset_id");
                    int decimals = rs.getInt("decimals");
                    return new Balance(asset,
                            new Money(asset, rs.getBigDecimal("available").toBigIntegerExact()).toDecimalString(decimals),
                            new Money(asset, rs.getBigDecimal("pending").toBigIntegerExact()).toDecimalString(decimals));
                })
                .list();
        return new BalanceList(rows);
    }

    /** Amounts are decimal strings in whole units of the asset, to avoid float rounding in clients. */
    public record Balance(String asset, String available, String pending) {
    }

    public record BalanceList(List<Balance> data) {
    }
}
