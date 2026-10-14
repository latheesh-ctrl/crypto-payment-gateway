package com.cpg.gateway.payout;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Hot wallets that send payouts, per chain.
 *
 * @param chains           keyed by chain code; a chain without an enabled entry refuses payouts
 * @param stuckAfter       a transaction neither mined nor final this long after its first broadcast is flagged
 *                         {@code needs_review} (and still tracked)
 * @param rebroadcastEvery how often a transaction the node no longer knows is sent again (same bytes)
 * @param pollInterval     how often a payout in flight is checked
 */
@ConfigurationProperties("gateway.payouts")
public record PayoutProperties(
        @DefaultValue Map<String, Chain> chains,
        @DefaultValue("PT30M") Duration stuckAfter,
        @DefaultValue("PT2M") Duration rebroadcastEvery,
        @DefaultValue("PT10S") Duration pollInterval) {

    public enum SignerType { dev_key }

    /**
     * @param enabled          send payouts on this chain
     * @param rpcUrl           node used to read nonces and gas prices, broadcast and track transactions
     * @param signer           how transactions are signed; only {@code dev_key} exists until a custody provider is
     *                         integrated, and it is refused in live mode
     * @param devPrivateKey    hex private key for {@code dev_key}; local and test networks only
     * @param maxGasPriceWei   payouts wait instead of signing while the network gas price is above this
     * @param gasPricePercent  percentage of the node's suggested gas price to bid
     * @param tokenGasLimit    gas limit for token transfers
     */
    public record Chain(
            @DefaultValue("false") boolean enabled,
            String rpcUrl,
            @DefaultValue("dev_key") SignerType signer,
            String devPrivateKey,
            BigInteger maxGasPriceWei,
            @DefaultValue("125") int gasPricePercent,
            @DefaultValue("100000") long tokenGasLimit) {
    }

    public Chain chain(String code) {
        return chains.get(code);
    }
}
