package com.cpg.gateway.chain;

/** Builds a client for one provider endpoint. Replaced by an in-memory chain in tests. */
public interface ChainClientFactory {

    ChainClient create(String chain, String family, String url, String apiKey);
}
