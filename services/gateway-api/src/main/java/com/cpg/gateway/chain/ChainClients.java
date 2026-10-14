package com.cpg.gateway.chain;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Provider clients per chain, built lazily from {@link IndexerProperties}: one scanning client and the
 * independent verification clients. A verification URL equal to the scanning URL is rejected, because a check
 * against the same provider proves nothing.
 */
@Component
public class ChainClients {

    private final JdbcClient jdbc;
    private final IndexerProperties properties;
    private final ChainClientFactory factory;
    private final Map<String, ChainClient> scanners = new ConcurrentHashMap<>();
    private final Map<String, List<ChainClient>> verifiers = new ConcurrentHashMap<>();
    private final Map<String, String> families = new ConcurrentHashMap<>();

    public ChainClients(JdbcClient jdbc, IndexerProperties properties, ChainClientFactory factory) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.factory = factory;
    }

    /** Chains configured for scanning, in a stable order. */
    public Set<String> watchedChains() {
        Set<String> out = new TreeSet<>();
        properties.chains().forEach((code, c) -> {
            if (c.enabled() && c.rpcUrl() != null && !c.rpcUrl().isBlank()) {
                out.add(code);
            }
        });
        return out;
    }

    public IndexerProperties.Chain config(String chain) {
        IndexerProperties.Chain c = properties.chain(chain);
        if (c == null) {
            throw new IllegalStateException("no indexer configuration for " + chain);
        }
        return c;
    }

    public ChainClient scanner(String chain) {
        return scanners.computeIfAbsent(chain, code -> {
            IndexerProperties.Chain c = config(code);
            if (c.rpcUrl() == null || c.rpcUrl().isBlank()) {
                throw new IllegalStateException("no RPC URL configured for " + code);
            }
            return factory.create(code, family(code), c.rpcUrl(), c.apiKey());
        });
    }

    /** Independent verification clients; empty when none are configured, which means deposits are never credited. */
    public List<ChainClient> verifiers(String chain) {
        return verifiers.computeIfAbsent(chain, code -> {
            IndexerProperties.Chain c = properties.chain(code);
            if (c == null) {
                return List.of();
            }
            return c.verifyRpcUrls().stream()
                    .filter(url -> !url.isBlank())
                    .peek(url -> {
                        if (url.equals(c.rpcUrl())) {
                            throw new IllegalStateException(code + " verification URL must differ from the scanning URL");
                        }
                    })
                    .map(url -> factory.create(code, family(code), url, c.apiKey()))
                    .toList();
        });
    }

    /** Contract addresses of the chain's enabled tokens, in the format stored in {@code assets}. */
    public Set<String> tokenContracts(String chain) {
        return Set.copyOf(jdbc.sql("""
                        SELECT contract_address FROM assets
                        WHERE chain_code = :c AND enabled AND contract_address IS NOT NULL
                        """)
                .param("c", chain).query(String.class).list());
    }

    private String family(String chain) {
        return families.computeIfAbsent(chain, code -> jdbc.sql("SELECT family FROM chains WHERE code = :c")
                .param("c", code).query(String.class).optional()
                .orElseThrow(() -> new IllegalStateException("unknown chain " + code)));
    }
}
