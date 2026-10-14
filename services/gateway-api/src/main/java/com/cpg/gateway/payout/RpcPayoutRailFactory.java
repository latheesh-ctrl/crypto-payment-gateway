package com.cpg.gateway.payout;

import com.cpg.gateway.chain.RpcTransport;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Node-backed rails. EVM chains only for now; Tron and Bitcoin payouts follow with the custody integration. */
@Component
class RpcPayoutRailFactory implements PayoutRailFactory {

    @Override
    public PayoutRail create(String chain, String family, Long evmChainId, PayoutProperties.Chain config) {
        if (!"evm".equals(family)) {
            return null;
        }
        if (config.rpcUrl() == null || config.rpcUrl().isBlank()) {
            throw new IllegalStateException("no payout RPC URL configured for " + chain);
        }
        return new EvmPayoutRail(chain, evmChainId, new RpcTransport(config.rpcUrl(), Map.of()), signer(chain, config),
                config);
    }

    static TransactionSigner signer(String chain, PayoutProperties.Chain config) {
        return switch (config.signer()) {
            case dev_key -> {
                if (config.devPrivateKey() == null || config.devPrivateKey().isBlank()) {
                    throw new IllegalStateException("no dev private key configured for " + chain + " payouts");
                }
                yield new DevKeySigner(config.devPrivateKey());
            }
        };
    }
}
