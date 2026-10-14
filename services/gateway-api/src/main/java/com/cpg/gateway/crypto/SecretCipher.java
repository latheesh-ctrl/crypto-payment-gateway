package com.cpg.gateway.crypto;

import com.cpg.gateway.config.GatewayProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for secrets the platform must read back, such as webhook signing secrets. Output layout is
 * {@code version(1) || iv(12) || ciphertext+tag}. The version byte allows key rotation later.
 *
 * <p>Wallet keys never pass through here; they live only in the custody provider or HSM.
 */
@Component
public class SecretCipher {

    private static final byte VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public SecretCipher(GatewayProperties properties) {
        byte[] raw = Base64.getDecoder().decode(properties.security().dataEncryptionKey());
        if (raw.length != 32) {
            throw new IllegalStateException("gateway.security.data-encryption-key must decode to 32 bytes");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public byte[] encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + IV_BYTES + ciphertext.length).put(VERSION).put(iv).put(ciphertext).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String decrypt(byte[] blob) {
        if (blob.length < 1 + IV_BYTES || blob[0] != VERSION) {
            throw new IllegalArgumentException("unsupported ciphertext");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, blob, 1, IV_BYTES));
            byte[] plaintext = cipher.doFinal(blob, 1 + IV_BYTES, blob.length - 1 - IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed", e);
        }
    }
}
