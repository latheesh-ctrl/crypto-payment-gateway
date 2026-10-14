package com.cpg.gateway.asset;

public record Asset(
        String id,
        String chain,
        String chainName,
        String symbol,
        String name,
        String contractAddress,
        int decimals,
        int confirmations,
        boolean acceptPayments) {
}
