package com.cpg.gateway.ledger;

import com.cpg.common.Money;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Read-side projection of an owner's balances per asset, in decimal units. */
@Component
public class BalanceQuery {

    /** Amounts are decimal strings in whole units of the asset, to avoid float rounding in clients. */
    public record Balance(String asset, String available, String pending, String reserved) {
    }

    private final JdbcClient jdbc;

    public BalanceQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Balance> forOwner(AccountKind.Owner owner, String ownerId) {
        return jdbc.sql("""
                        SELECT a.id AS asset_id, a.decimals,
                               coalesce(sum(b.balance) FILTER (WHERE la.kind = 'available'), 0) AS available,
                               coalesce(sum(b.balance) FILTER (WHERE la.kind = 'pending'), 0)   AS pending,
                               coalesce(sum(b.balance) FILTER (WHERE la.kind = 'reserved'), 0)  AS reserved
                        FROM ledger_accounts la
                        JOIN ledger_balances b ON b.account_id = la.id
                        JOIN assets a ON a.id = la.asset_id
                        WHERE la.owner_type = :ownerType AND la.owner_id = :ownerId
                        GROUP BY a.id, a.decimals
                        ORDER BY a.id
                        """)
                .param("ownerType", owner.name().toLowerCase())
                .param("ownerId", ownerId)
                .query((rs, i) -> {
                    String asset = rs.getString("asset_id");
                    int decimals = rs.getInt("decimals");
                    return new Balance(asset,
                            format(asset, rs.getBigDecimal("available"), decimals),
                            format(asset, rs.getBigDecimal("pending"), decimals),
                            format(asset, rs.getBigDecimal("reserved"), decimals));
                })
                .list();
    }

    private static String format(String asset, java.math.BigDecimal minorUnits, int decimals) {
        return new Money(asset, minorUnits.toBigIntegerExact()).toDecimalString(decimals);
    }
}
