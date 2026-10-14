package com.cpg.gateway.payout;

import java.math.BigInteger;

/**
 * Signs transaction hashes for one hot wallet. The gateway never needs the key itself: production signers are a
 * custody provider or HSM; {@link DevKeySigner} exists only for local development and test networks.
 */
public interface TransactionSigner {

    /** Address of the wallet this signer controls, in the chain's native format. */
    String address();

    /** secp256k1 signature of a 32-byte hash, with low {@code s} and the public-key recovery ID (0 or 1). */
    Signature sign(byte[] hash);

    record Signature(BigInteger r, BigInteger s, int recoveryId) {
    }
}
