package com.cpg.gateway.payout.evm;

import com.cpg.gateway.payout.TransactionSigner;
import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import org.bouncycastle.jcajce.provider.digest.Keccak;

/**
 * Builds and signs legacy EIP-155 transactions: a native transfer, or an ERC-20 {@code transfer(to, amount)} call.
 * Legacy (type 0) transactions are accepted on Ethereum, BNB Smart Chain and Arbitrum, need no fee-market
 * estimation, and replay protection comes from the chain ID in the signature.
 */
public final class EvmTransactions {

    /** {@code bytes4(keccak256("transfer(address,uint256)"))}. */
    private static final String TRANSFER_SELECTOR = "a9059cbb";
    private static final HexFormat HEX = HexFormat.of();

    private EvmTransactions() {
    }

    /**
     * @param to    recipient for native transfers, token contract for ERC-20 transfers
     * @param value wei sent with the transaction (zero for token transfers)
     * @param data  call data (empty for native transfers)
     */
    public record Unsigned(long chainId, long nonce, BigInteger gasPrice, long gasLimit, String to, BigInteger value,
                           byte[] data) {

        public Unsigned {
            if (chainId <= 0 || nonce < 0 || gasPrice.signum() <= 0 || gasLimit <= 0 || value.signum() < 0) {
                throw new IllegalArgumentException("invalid transaction fields");
            }
            data = data.clone();
        }
    }

    public record Signed(String txHash, String rawTx) {
    }

    public static Unsigned nativeTransfer(long chainId, long nonce, BigInteger gasPrice, long gasLimit, String to,
                                          BigInteger amount) {
        return new Unsigned(chainId, nonce, gasPrice, gasLimit, to, amount, new byte[0]);
    }

    public static Unsigned tokenTransfer(long chainId, long nonce, BigInteger gasPrice, long gasLimit,
                                         String tokenContract, String to, BigInteger amount) {
        return new Unsigned(chainId, nonce, gasPrice, gasLimit, tokenContract, BigInteger.ZERO, transferData(to, amount));
    }

    /** ABI-encoded {@code transfer(address,uint256)} call data. */
    public static byte[] transferData(String to, BigInteger amount) {
        if (amount.signum() <= 0 || amount.bitLength() > 256) {
            throw new IllegalArgumentException("amount out of range");
        }
        String amountHex = amount.toString(16);
        return HEX.parseHex(TRANSFER_SELECTOR + "0".repeat(24) + addressHex(to)
                + "0".repeat(64 - amountHex.length()) + amountHex);
    }

    /** keccak256 of the EIP-155 signing payload {@code rlp([nonce, gasPrice, gas, to, value, data, chainId, 0, 0])}. */
    public static byte[] signingHash(Unsigned tx) {
        return keccak256(Rlp.list(List.of(
                Rlp.uint(tx.nonce()), Rlp.uint(tx.gasPrice()), Rlp.uint(tx.gasLimit()),
                Rlp.bytes(HEX.parseHex(addressHex(tx.to()))), Rlp.uint(tx.value()), Rlp.bytes(tx.data()),
                Rlp.uint(tx.chainId()), Rlp.uint(0), Rlp.uint(0))));
    }

    public static Signed sign(Unsigned tx, TransactionSigner signer) {
        TransactionSigner.Signature sig = signer.sign(signingHash(tx));
        BigInteger v = BigInteger.valueOf(tx.chainId()).multiply(BigInteger.TWO)
                .add(BigInteger.valueOf(35 + sig.recoveryId()));
        byte[] raw = Rlp.list(List.of(
                Rlp.uint(tx.nonce()), Rlp.uint(tx.gasPrice()), Rlp.uint(tx.gasLimit()),
                Rlp.bytes(HEX.parseHex(addressHex(tx.to()))), Rlp.uint(tx.value()), Rlp.bytes(tx.data()),
                Rlp.uint(v), Rlp.uint(sig.r()), Rlp.uint(sig.s())));
        return new Signed("0x" + HEX.formatHex(keccak256(raw)), "0x" + HEX.formatHex(raw));
    }

    private static String addressHex(String address) {
        String hex = address.startsWith("0x") || address.startsWith("0X") ? address.substring(2) : address;
        if (!hex.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("not an EVM address: " + address);
        }
        return hex.toLowerCase();
    }

    public static byte[] keccak256(byte[] input) {
        return new Keccak.Digest256().digest(input);
    }
}
