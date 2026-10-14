package com.cpg.gateway.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.Arrays;
import org.bitcoinj.base.Base58;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.junit.jupiter.api.Test;

class AddressCodecTest {

    @Test
    void evmAddressMatchesKnownVector() {
        // Widely published example key/address pair (web3.js documentation).
        ECKey key = ECKey.fromPrivate(new BigInteger("4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318", 16));
        assertThat(AddressCodec.evm(key)).isEqualTo("0x2c7536E3605D9C16a7a3D7b1898e529396a65c23");
    }

    @Test
    void tronAddressEncodesSameAccountBytesWithPrefix41() {
        ECKey key = ECKey.fromPrivate(new BigInteger("4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318", 16));
        String tron = AddressCodec.tron(key);
        assertThat(tron).startsWith("T").hasSize(34);
        byte[] decoded = Base58.decodeChecked(tron);
        assertThat(decoded[0]).isEqualTo((byte) 0x41);
        assertThat(Arrays.copyOfRange(decoded, 1, 21)).isEqualTo(AddressCodec.accountBytes(key));
    }

    @Test
    void bitcoinMatchesBip84TestVector() {
        // BIP-84 test vector: mnemonic "abandon x11 about", account 0 zpub, first receive address.
        DeterministicKey account = DeterministicKey.deserializeB58(
                "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs",
                BitcoinNetwork.MAINNET);
        DeterministicKey first = HDKeyDerivation.deriveChildKey(
                HDKeyDerivation.deriveChildKey(account, ChildNumber.ZERO), ChildNumber.ZERO);
        assertThat(AddressCodec.encode("bitcoin", first, true)).isEqualTo("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu");
    }

    @Test
    void normalisesEvmAddresses() {
        assertThat(AddressCodec.normalizeEvm("0x2c7536e3605d9c16a7a3d7b1898e529396a65c23"))
                .isEqualTo("0x2c7536E3605D9C16a7a3D7b1898e529396a65c23");
    }
}
