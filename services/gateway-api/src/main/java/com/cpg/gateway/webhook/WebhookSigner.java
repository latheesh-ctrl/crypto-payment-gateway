package com.cpg.gateway.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signature header: {@code CPG-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}.
 * Merchants verify the HMAC and reject timestamps older than five minutes to stop replays. While a secret is being
 * rotated the header carries a {@code v1} for the new and the previous secret; a match with either is valid.
 */
public final class WebhookSigner {

    public static final String HEADER = "CPG-Signature";

    private WebhookSigner() {
    }

    public static String header(String secret, long timestampSeconds, byte[] body) {
        return "t=" + timestampSeconds + ",v1=" + sign(secret, timestampSeconds, body);
    }

    /** During secret rotation: one {@code v1} per secret, so the merchant can verify with either. */
    public static String header(String secret, String previousSecret, long timestampSeconds, byte[] body) {
        return header(secret, timestampSeconds, body) + ",v1=" + sign(previousSecret, timestampSeconds, body);
    }

    public static String sign(String secret, long timestampSeconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestampSeconds + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
