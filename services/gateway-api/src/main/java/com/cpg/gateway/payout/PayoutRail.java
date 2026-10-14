package com.cpg.gateway.payout;

import java.math.BigInteger;

/**
 * Sends payouts on one chain from one hot wallet: builds and signs a transfer, broadcasts it, and reports what
 * the chain says about it. Every method that talks to a node may throw
 * {@link com.cpg.gateway.chain.ChainRpcException}, which callers treat as "unknown, try again later".
 */
public interface PayoutRail {

    String chain();

    /** Hot wallet the payouts are sent from. */
    String fromAddress();

    /** Next nonce the node would accept from the hot wallet, counting transactions still in its mempool. */
    long pendingNonce();

    /** Number of hot-wallet transactions already in blocks: any nonce below it is used. */
    long minedNonce();

    long head();

    /**
     * Builds and signs the transfer without sending it.
     *
     * @throws NotReady when the transfer cannot be signed right now (for example gas above the configured cap)
     */
    Signed sign(Transfer transfer, long nonce);

    /** Sends signed bytes. Re-sending the same bytes is always safe: a chain mines a transaction at most once. */
    Broadcast broadcast(String rawTx);

    TxStatus status(String txHash);

    /** @param tokenContract null for the chain's native coin */
    record Transfer(String to, String tokenContract, BigInteger amount) {
    }

    record Signed(String txHash, String rawTx) {
    }

    enum BroadcastResult {
        /** The node accepted the transaction into its mempool. */
        ACCEPTED,
        /** The node already has this exact transaction (pending or mined). */
        ALREADY_KNOWN,
        /** The nonce is already used; whether by this transaction or another one needs a status check. */
        NONCE_USED,
        /** The node refused it (for example the hot wallet cannot cover the transfer or gas). */
        REJECTED
    }

    record Broadcast(BroadcastResult result, String error) {
    }

    enum State { NOT_FOUND, PENDING, SUCCEEDED, REVERTED }

    /**
     * @param blockNumber block the transaction is in (SUCCEEDED or REVERTED)
     * @param networkFee  native-coin fee the transaction paid, in base units (SUCCEEDED or REVERTED)
     */
    record TxStatus(State state, Long blockNumber, BigInteger networkFee) {

        public static TxStatus notFound() {
            return new TxStatus(State.NOT_FOUND, null, null);
        }

        public static TxStatus pending() {
            return new TxStatus(State.PENDING, null, null);
        }
    }

    /** A transfer that cannot be signed yet; the payout is retried later. */
    class NotReady extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public NotReady(String message) {
            super(message);
        }
    }
}
