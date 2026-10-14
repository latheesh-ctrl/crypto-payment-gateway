package com.cpg.gateway.session;

import com.cpg.gateway.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Tokens for the hosted payment page: {@code HMAC-SHA256(k, session id)} with a key derived from the data
 * encryption key. Nothing is stored, so the merchant can fetch the payment URL again at any time, and a token only
 * ever unlocks the read-only view of its own session.
 */
@Component
public class CheckoutTokens {

    private final SecretKeySpec key;

    public CheckoutTokens(GatewayProperties properties) {
        byte[] master = Base64.getDecoder().decode(properties.security().dataEncryptionKey());
        this.key = new SecretKeySpec(hmac(new SecretKeySpec(master, "HmacSHA256"), "checkout-token-v1"), "HmacSHA256");
    }

    public String tokenFor(String sessionId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(key, sessionId));
    }

    public boolean valid(String sessionId, String token) {
        return token != null && MessageDigest.isEqual(tokenFor(sessionId).getBytes(StandardCharsets.US_ASCII),
                token.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] hmac(SecretKeySpec key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
