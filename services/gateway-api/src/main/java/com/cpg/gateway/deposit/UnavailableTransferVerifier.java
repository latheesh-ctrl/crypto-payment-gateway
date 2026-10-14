package com.cpg.gateway.deposit;

/**
 * Fallback used until a chain verifier is configured: never confirms, so nothing is ever credited without
 * independent verification.
 */
public class UnavailableTransferVerifier implements TransferVerifier {

    @Override
    public Result verify(Deposit deposit) {
        return Result.UNAVAILABLE;
    }
}
