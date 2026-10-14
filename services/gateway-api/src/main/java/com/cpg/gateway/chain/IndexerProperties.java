package com.cpg.gateway.chain;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Node and RPC provider settings for the chain watchers.
 *
 * @param chains keyed by chain code (TRON, ETHEREUM, BITCOIN, BSC, ARBITRUM)
 */
@ConfigurationProperties("gateway.indexer")
public record IndexerProperties(@DefaultValue Map<String, Chain> chains) {

    /**
     * @param enabled          scan this chain for deposits
     * @param rpcUrl           node or provider the watcher scans; credentials may go in the URL's user-info
     * @param apiKey           provider API key, sent as the provider's key header (Tron: TRON-PRO-API-KEY)
     * @param verifyRpcUrls    independent providers that must all confirm a deposit before it is credited; with
     *                         none configured, deposits wait at finality and are never credited
     * @param pollInterval     time between scans; about one block time
     * @param startBlock       first block to scan when the chain has never been scanned; default the current head
     * @param maxBlocksPerPoll catch-up batch size
     */
    public record Chain(
            @DefaultValue("false") boolean enabled,
            String rpcUrl,
            String apiKey,
            @DefaultValue List<String> verifyRpcUrls,
            @DefaultValue("PT10S") Duration pollInterval,
            Long startBlock,
            @DefaultValue("50") int maxBlocksPerPoll) {
    }

    public Chain chain(String code) {
        return chains.get(code);
    }
}
