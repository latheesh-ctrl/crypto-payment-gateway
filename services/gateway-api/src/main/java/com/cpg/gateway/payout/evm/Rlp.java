package com.cpg.gateway.payout.evm;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/** Recursive Length Prefix encoding (Ethereum yellow paper, appendix B); encode only. */
public final class Rlp {

    private Rlp() {
    }

    /** A byte string item. */
    public static byte[] bytes(byte[] value) {
        if (value.length == 1 && (value[0] & 0xff) < 0x80) {
            return value.clone();
        }
        return concat(header(0x80, value.length), value);
    }

    /** An unsigned integer as its minimal big-endian bytes; zero is the empty string. */
    public static byte[] uint(BigInteger value) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException("RLP integers are unsigned");
        }
        return bytes(minimal(value));
    }

    public static byte[] uint(long value) {
        return uint(BigInteger.valueOf(value));
    }

    /** A list of already-encoded items. */
    public static byte[] list(List<byte[]> encodedItems) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        encodedItems.forEach(payload::writeBytes);
        byte[] body = payload.toByteArray();
        return concat(header(0xc0, body.length), body);
    }

    static byte[] minimal(BigInteger value) {
        if (value.signum() == 0) {
            return new byte[0];
        }
        byte[] raw = value.toByteArray();
        return raw[0] == 0 ? Arrays.copyOfRange(raw, 1, raw.length) : raw;
    }

    private static byte[] header(int offset, int length) {
        if (length <= 55) {
            return new byte[] {(byte) (offset + length)};
        }
        byte[] len = minimal(BigInteger.valueOf(length));
        return concat(new byte[] {(byte) (offset + 55 + len.length)}, len);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
