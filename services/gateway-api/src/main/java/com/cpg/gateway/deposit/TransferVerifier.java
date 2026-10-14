package com.cpg.gateway.deposit;

/**
 * Independent re-check of a deposit against the chain (through a different RPC provider than the indexer used)
 * before any credit. A forged or mistaken indexer event can at most trigger a check that fails.
 */
public interface TransferVerifier {

    enum Result {
        /** Recipient, token, amount and finality all match on an independent source. */
        CONFIRMED,
        /** The independent source disagrees; the deposit must be reviewed and is never credited automatically. */
        MISMATCH,
        /** The check could not run right now; try again later. */
        UNAVAILABLE
    }

    Result verify(Deposit deposit);
}
