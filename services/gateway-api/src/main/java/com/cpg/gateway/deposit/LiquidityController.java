package com.cpg.gateway.deposit;

import com.cpg.common.Money;
import com.cpg.gateway.apikey.MerchantPrincipal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A merchant's liquidity per asset: {@code available} funds its users' cashouts, {@code allocated} is earmarked
 * for cashouts in flight, and {@code owed_to_users} is the total of its users' balances.
 */
@RestController
public class LiquidityController {

    private final JdbcClient jdbc;

    public LiquidityController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/liquidity")
    public LiquidityList liquidity(@AuthenticationPrincipal MerchantPrincipal merchant) {
        return new LiquidityList(jdbc.sql("""
                        SELECT a.id, a.decimals,
                               coalesce(sum(b.balance) FILTER (WHERE la.kind = 'liquidity'), 0)           AS available,
                               coalesce(sum(b.balance) FILTER (WHERE la.kind = 'liquidity_allocated'), 0) AS allocated,
                               coalesce(sum(b.balance) FILTER (WHERE la.kind = 'user_obligations'), 0)    AS owed
                        FROM ledger_accounts la
                        JOIN ledger_balances b ON b.account_id = la.id
                        JOIN assets a ON a.id = la.asset_id
                        WHERE la.owner_type = 'merchant' AND la.owner_id = :m
                          AND la.kind IN ('liquidity', 'liquidity_allocated', 'user_obligations')
                        GROUP BY a.id, a.decimals ORDER BY a.id
                        """)
                .param("m", merchant.merchantId())
                .query((rs, i) -> {
                    String asset = rs.getString(1);
                    int decimals = rs.getInt(2);
                    return new Liquidity(asset,
                            new Money(asset, rs.getBigDecimal(3).toBigIntegerExact()).toDecimalString(decimals),
                            new Money(asset, rs.getBigDecimal(4).toBigIntegerExact()).toDecimalString(decimals),
                            new Money(asset, rs.getBigDecimal(5).toBigIntegerExact()).toDecimalString(decimals));
                })
                .list());
    }

    public record Liquidity(String asset, String available, String allocated, String owedToUsers) {
    }

    public record LiquidityList(List<Liquidity> data) {
    }
}
