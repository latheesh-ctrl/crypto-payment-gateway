package com.cpg.gateway.wallet;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Per-chain extended public keys for deposit address derivation. Only public keys are configured here: the
 * matching private keys live in the custody provider or HSM, so deriving addresses never needs a key that can move
 * funds.
 *
 * @param chains keyed by chain code (TRON, ETHEREUM, BITCOIN, BSC, ARBITRUM)
 */
@ConfigurationProperties("gateway.wallets")
public record WalletProperties(@DefaultValue Map<String, Chain> chains) {

    /**
     * @param xpub    account-level extended public key (xpub/tpub, or zpub/vpub for Bitcoin SegWit)
     * @param mainnet whether addresses are encoded for mainnet (affects Bitcoin only)
     */
    public record Chain(String xpub, @DefaultValue("false") boolean mainnet) {
    }
}
