package com.cpg.gateway.payout;

/** Creates the rail for a chain; tests replace it with in-memory rails. */
@FunctionalInterface
public interface PayoutRailFactory {

    /**
     * @param family     chain family (evm, tron, bitcoin)
     * @param evmChainId EIP-155 chain ID for EVM chains, else null
     * @return null when this family cannot send payouts yet
     */
    PayoutRail create(String chain, String family, Long evmChainId, PayoutProperties.Chain config);
}
