package com.cpg.gateway.chain;

/** The provider could not give a usable answer. Retried later; never treated as evidence about the chain. */
public class ChainRpcException extends RuntimeException {

    public ChainRpcException(String message) {
        super(message);
    }

    public ChainRpcException(String message, Throwable cause) {
        super(message, cause);
    }
}
