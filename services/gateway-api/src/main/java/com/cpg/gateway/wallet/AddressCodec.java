package com.cpg.gateway.wallet;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import org.bitcoinj.base.Base58;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.crypto.ECKey;
import org.bouncycastle.jcajce.provider.digest.Keccak;

/** Encodes a secp256k1 public key as a deposit address for each supported chain family. */
public final class AddressCodec {

    private static final byte TRON_PREFIX = 0x41;

    private AddressCodec() {
    }

    public static String encode(String family, ECKey publicKey, boolean mainnet) {
        return switch (family) {
            case "evm" -> evm(publicKey);
            case "tron" -> tron(publicKey);
            case "bitcoin" -> publicKey.toAddress(ScriptType.P2WPKH, mainnet ? BitcoinNetwork.MAINNET : BitcoinNetwork.TESTNET)
                    .toString();
            default -> throw new IllegalArgumentException("unsupported chain family: " + family);
        };
    }

    /** EIP-55 checksummed address: last 20 bytes of keccak256(uncompressed public key without prefix). */
    public static String evm(ECKey publicKey) {
        return checksum(HexFormat.of().formatHex(accountBytes(publicKey)));
    }

    /** Base58Check(0x41 || account bytes), the same 20 bytes an EVM address uses. */
    public static String tron(ECKey publicKey) {
        return Base58.encodeChecked(TRON_PREFIX, accountBytes(publicKey));
    }

    /** Normalises an EVM address for comparison and storage lookups. */
    public static String normalizeEvm(String address) {
        String hex = address.startsWith("0x") || address.startsWith("0X") ? address.substring(2) : address;
        if (!hex.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("not an EVM address: " + address);
        }
        return checksum(hex.toLowerCase());
    }

    static byte[] accountBytes(ECKey publicKey) {
        byte[] uncompressed = publicKey.getPubKeyPoint().getEncoded(false);
        byte[] hash = keccak256(Arrays.copyOfRange(uncompressed, 1, uncompressed.length));
        return Arrays.copyOfRange(hash, 12, 32);
    }

    private static String checksum(String lowerHex) {
        String hash = HexFormat.of().formatHex(keccak256(lowerHex.getBytes(StandardCharsets.US_ASCII)));
        StringBuilder out = new StringBuilder("0x");
        for (int i = 0; i < lowerHex.length(); i++) {
            char c = lowerHex.charAt(i);
            out.append(Character.digit(hash.charAt(i), 16) >= 8 ? Character.toUpperCase(c) : c);
        }
        return out.toString();
    }

    private static byte[] keccak256(byte[] input) {
        return new Keccak.Digest256().digest(input);
    }
}
