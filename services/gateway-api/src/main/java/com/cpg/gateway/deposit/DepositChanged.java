package com.cpg.gateway.deposit;

/**
 * Published inside the deposit's transaction whenever its status changes, so modules built on deposits (payment
 * sessions) update atomically with it.
 */
public record DepositChanged(Deposit deposit) {
}
