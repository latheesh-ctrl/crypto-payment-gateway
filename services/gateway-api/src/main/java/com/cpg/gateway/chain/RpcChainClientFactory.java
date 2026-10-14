package com.cpg.gateway.chain;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class RpcChainClientFactory implements ChainClientFactory {

    @Override
    public ChainClient create(String chain, String family, String url, String apiKey) {
        return switch (family) {
            case "evm" -> new EvmChainClient(chain, new RpcTransport(url, Map.of()));
            case "tron" -> new TronChainClient(chain, new RpcTransport(url,
                    apiKey == null || apiKey.isBlank() ? Map.of() : Map.of("TRON-PRO-API-KEY", apiKey)));
            case "bitcoin" -> new BitcoinChainClient(chain, new RpcTransport(url, Map.of()));
            default -> throw new IllegalArgumentException("unsupported chain family " + family);
        };
    }
}
