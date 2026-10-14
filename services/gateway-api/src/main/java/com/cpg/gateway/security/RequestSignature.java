package com.cpg.gateway.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Merchant request signatures.
 *
 * <pre>
 * CPG-Signature: v1=hex(HMAC-SHA256(signing_secret, canonical))
 * canonical = timestamp + "\n" + nonce + "\n" + METHOD + "\n" + path[?query] + "\n" + hex(SHA-256(body))
 * </pre>
 * The path and query are signed exactly as sent; an empty body hashes the empty string.
 */
public final class RequestSignature {

    public static final String SIGNATURE_HEADER = "CPG-Signature";
    public static final String TIMESTAMP_HEADER = "CPG-Timestamp";
    public static final String NONCE_HEADER = "CPG-Nonce";
    /** Requests signed more than this many seconds before or after the server's clock are refused. */
    public static final long TOLERANCE_SECONDS = 300;

    private RequestSignature() {
    }

    public static String canonical(long timestamp, String nonce, String method, String pathAndQuery, byte[] body) {
        return timestamp + "\n" + nonce + "\n" + method.toUpperCase() + "\n" + pathAndQuery + "\n" + sha256Hex(body);
    }

    /** The full header value, {@code v1=<hex>}. */
    public static String sign(String signingSecret, long timestamp, String nonce, String method, String pathAndQuery,
                              byte[] body) {
        return "v1=" + hmacHex(signingSecret, canonical(timestamp, nonce, method, pathAndQuery, body));
    }

    /** Constant-time comparison of a presented header against the expected one. */
    public static boolean matches(String presented, String expected) {
        return MessageDigest.isEqual(presented.strip().getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII));
    }

    static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hmacHex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
