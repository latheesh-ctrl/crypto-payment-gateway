package com.cpg.gateway.chain;

import java.math.BigInteger;

/** Hex helpers for node responses. */
public final class Hex {

    /** keccak256("Transfer(address,address,uint256)"), the ERC-20 / TRC-20 Transfer event topic. */
    static final String TRANSFER_TOPIC = "ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    private Hex() {
    }

    static String strip(String hex) {
        return hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
    }

    /** Unsigned integer from a 0x-quantity or plain hex word. */
    public static BigInteger uint(String hex) {
        String digits = strip(hex);
        if (digits.isEmpty() || !digits.matches("[0-9a-fA-F]+")) {
            throw new ChainRpcException("not a hex number: " + hex);
        }
        return new BigInteger(digits, 16);
    }

    public static long quantity(String hex) {
        return uint(hex).longValueExact();
    }

    public static String quantity(long value) {
        return "0x" + Long.toHexString(value);
    }

    /** Last 20 bytes (40 hex digits) of a 32-byte topic word, i.e. an indexed address argument. */
    static String topicAddress(String topic) {
        String digits = strip(topic);
        if (digits.length() != 64) {
            throw new ChainRpcException("not a 32-byte topic: " + topic);
        }
        return digits.substring(24).toLowerCase();
    }
}
