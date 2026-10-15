package com.cpg.gateway.payout;

import com.cpg.gateway.config.GatewayProperties;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The payout rail of each chain, built on first use from {@link PayoutProperties}. A live deployment refuses to
 * start with an in-process dev key: live payouts must be signed by a custody provider or HSM.
 */
@Component
public class PayoutRails {

    private final JdbcClient jdbc;
    private final PayoutProperties properties;
    private final PayoutRailFactory factory;
    private final Map<String, Optional<PayoutRail>> rails = new ConcurrentHashMap<>();

    public PayoutRails(JdbcClient jdbc, PayoutProperties properties, PayoutRailFactory factory,
                       GatewayProperties gateway) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.factory = factory;
        if (gateway.mode() == GatewayProperties.Mode.live) {
            properties.chains().forEach((code, c) -> {
                if (c.enabled() && c.signer() == PayoutProperties.SignerType.dev_key) {
                    throw new IllegalStateException(code + " payouts use a dev key, which is not allowed in live mode");
                }
            });
        }
    }

    /** The rail for a chain, or empty when payouts are not enabled or not supported there. */
    public Optional<PayoutRail> rail(String chain) {
        return rails.computeIfAbsent(chain, code -> {
            PayoutProperties.Chain config = properties.chain(code);
            if (config == null || !config.enabled()) {
                return Optional.empty();
            }
            record ChainRow(String family, Long evmChainId) {
            }
            ChainRow row = jdbc.sql("SELECT family, evm_chain_id FROM chains WHERE code = :c AND enabled")
                    .param("c", code)
                    .query((rs, i) -> new ChainRow(rs.getString(1), (Long) rs.getObject(2)))
                    .optional().orElse(null);
            return row == null ? Optional.empty()
                    : Optional.ofNullable(factory.create(code, row.family(), row.evmChainId(), config));
        });
    }
}
