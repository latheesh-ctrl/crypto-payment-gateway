package com.cpg.gateway.payout;

import com.cpg.gateway.wallet.AddressCodec;
import java.math.BigInteger;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.crypto.ECKey;

/**
 * Signs with a private key held in this process's memory. For local development and public test networks only:
 * the gateway refuses to start a dev-key signer in live mode (see {@link PayoutRails}).
 */
public final class DevKeySigner implements TransactionSigner {

    private final ECKey key;
    private final String address;

    public DevKeySigner(String privateKeyHex) {
        String hex = privateKeyHex.startsWith("0x") ? privateKeyHex.substring(2) : privateKeyHex;
        if (!hex.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("dev private key must be 32 bytes of hex");
        }
        this.key = ECKey.fromPrivate(new BigInteger(hex, 16), false);
        this.address = AddressCodec.evm(key);
    }

    @Override
    public String address() {
        return address;
    }

    @Override
    public Signature sign(byte[] hash) {
        if (hash.length != 32) {
            throw new IllegalArgumentException("expected a 32-byte hash");
        }
        Sha256Hash digest = Sha256Hash.wrap(hash);
        // bitcoinj signs deterministically (RFC 6979) and returns a canonical low-s signature.
        ECKey.ECDSASignature sig = key.sign(digest);
        return new Signature(sig.r, sig.s, key.findRecoveryId(digest, sig));
    }

    @Override
    public String toString() {
        return "DevKeySigner[" + address + "]";
    }
}
