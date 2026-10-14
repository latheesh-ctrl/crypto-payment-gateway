package com.cpg.common;

import java.security.SecureRandom;
import java.time.Clock;

/**
 * Generates prefixed, time-ordered identifiers such as {@code mch_01J9Z3K8Q4V7X2N5R6T8W0Y1A3}.
 *
 * <p>The suffix is a 26-character ULID (48-bit millisecond timestamp + 80 random bits, Crockford base32),
 * so ids sort by creation time, index well in PostgreSQL B-trees and reveal no sequence information.
 */
public final class Ids {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String next(String prefix) {
        return next(prefix, Clock.systemUTC());
    }

    static String next(String prefix, Clock clock) {
        if (prefix == null || !prefix.matches("[a-z]{2,8}")) {
            throw new IllegalArgumentException("prefix must be 2-8 lowercase letters: " + prefix);
        }
        return prefix + "_" + ulid(clock.millis());
    }

    private static String ulid(long millis) {
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);

        char[] out = new char[26];
        // 48-bit timestamp -> 10 chars (50 bits, top 2 always zero)
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (millis & 31)];
            millis >>>= 5;
        }
        // 80 random bits -> 16 chars
        long hi = 0;
        for (int i = 0; i < 5; i++) {
            hi = (hi << 8) | (random[i] & 0xff);
        }
        long lo = 0;
        for (int i = 5; i < 10; i++) {
            lo = (lo << 8) | (random[i] & 0xff);
        }
        for (int i = 17; i >= 10; i--) {
            out[i] = ALPHABET[(int) (hi & 31)];
            hi >>>= 5;
        }
        for (int i = 25; i >= 18; i--) {
            out[i] = ALPHABET[(int) (lo & 31)];
            lo >>>= 5;
        }
        return new String(out);
    }
}
