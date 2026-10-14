package com.cpg.gateway.chain;

import com.cpg.gateway.deposit.ObservedTransfer;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Read-only access to one chain through one node or RPC provider. Implementations never hold keys and never send
 * transactions. Every method may throw {@link ChainRpcException} when the provider is unreachable or answers
 * something unexpected; callers treat that as "try again later", never as an answer.
 */
public interface ChainClient {

    /** Chain code this client reads (TRON, ETHEREUM, ...). */
    String chain();

    /** Number of the newest block the provider knows. */
    long head();

    /** Header of the canonical block at this height. */
    BlockHeader header(long number);

    /**
     * Canonical block at this height with the transfers in it that pay one of our addresses: successful native
     * transfers and token Transfer events from {@code tokenContracts}.
     */
    ChainBlock block(long number, Set<String> tokenContracts, AddressFilter owned);

    /**
     * Transfers in this transaction as the provider sees it now, with the block it is in. Empty when the provider
     * does not know the transaction or it failed.
     *
     * @param blockHashHint block the transaction was reported in; Bitcoin nodes without a full index need it
     */
    List<ObservedTransfer> transfers(String txHash, String blockHashHint, Set<String> tokenContracts);

    record BlockHeader(long number, String hash, String parentHash) {
    }

    record ChainBlock(BlockHeader header, List<ObservedTransfer> transfers) {
    }

    /** Batch membership test so a block's recipients are checked against our addresses in one query. */
    @FunctionalInterface
    interface AddressFilter {
        Set<String> owned(Collection<String> addresses);
    }
}
