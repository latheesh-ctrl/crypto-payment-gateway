package com.cpg.gateway.deposit;

import java.math.BigInteger;
import java.util.Objects;

/**
 * A transfer to one of our addresses as reported by a chain indexer. Native coins and tokens are reported
 * separately by the indexer (transaction value or UTXO output vs. token Transfer event), but arrive here in one
 * shape.
 *
 * @param outputIndex   log index for EVM/Tron token transfers, 0 for native EVM/Tron transfers, vout for Bitcoin
 * @param tokenContract null for the chain's native coin
 * @param blockNumber   null while only seen in the mempool
 */
public record ObservedTransfer(String chain, String txHash, int outputIndex, String toAddress, String tokenContract,
                               BigInteger amount, Long blockNumber, String blockHash) {

    public ObservedTransfer {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(txHash, "txHash");
        Objects.requireNonNull(toAddress, "toAddress");
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
    }
}
