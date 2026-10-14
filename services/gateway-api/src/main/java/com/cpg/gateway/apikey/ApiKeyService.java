package com.cpg.gateway.apikey;

import com.cpg.common.Ids;
import com.cpg.gateway.audit.AuditLog;
import com.cpg.gateway.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and verifies secret API keys of the form {@code sk_<mode>_<43 base64url chars>} (256 bits of entropy).
 *
 * <p>Only {@code HMAC-SHA256(pepper, secret)} is stored, so a database leak alone does not reveal usable keys.
 * Because the secrets are long random values, a slow password hash is unnecessary and lookups stay O(1).
 */
@Service
public class ApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final GatewayProperties properties;
    private final AuditLog audit;
    private final SecretKeySpec pepper;

    public ApiKeyService(JdbcClient jdbc, GatewayProperties properties, AuditLog audit) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.audit = audit;
        this.pepper = new SecretKeySpec(
                properties.security().apiKeyPepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Transactional
    public IssuedApiKey issue(String merchantId, String name, AuditLog.Actor actor) {
        String mode = properties.mode().name();
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String secret = "sk_" + mode + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String last4 = secret.substring(secret.length() - 4);
        String id = Ids.next("key");

        jdbc.sql("""
                        INSERT INTO api_keys (id, merchant_id, mode, name, secret_hash, last4)
                        VALUES (:id, :merchantId, :mode, :name, :hash, :last4)
                        """)
                .param("id", id)
                .param("merchantId", merchantId)
                .param("mode", mode)
                .param("name", name.strip())
                .param("hash", hash(secret))
                .param("last4", last4)
                .update();
        audit.record(actor, "api_key.created", "api_key", id, merchantId, Map.of("mode", mode, "last4", last4));
        return new IssuedApiKey(id, merchantId, mode, name.strip(), secret, last4);
    }

    /** Resolves a presented secret to its merchant, if the key is valid, unrevoked and for this deployment. */
    public Optional<MerchantPrincipal> authenticate(String secret) {
        String expectedPrefix = "sk_" + properties.mode().name() + "_";
        if (secret == null || !secret.startsWith(expectedPrefix) || secret.length() > 128) {
            return Optional.empty();
        }
        Optional<MerchantPrincipal> principal = jdbc.sql("""
                        SELECT k.id, k.merchant_id, k.mode
                        FROM api_keys k JOIN merchants m ON m.id = k.merchant_id
                        WHERE k.secret_hash = :hash AND k.revoked_at IS NULL AND m.status = 'active'
                        """)
                .param("hash", hash(secret))
                .query((rs, i) -> new MerchantPrincipal(rs.getString("merchant_id"), rs.getString("id"),
                        rs.getString("mode")))
                .optional();

        // Throttled so hot keys don't turn every request into a row write.
        principal.ifPresent(p -> jdbc.sql("""
                        UPDATE api_keys SET last_used_at = now()
                        WHERE id = :id AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')
                        """)
                .param("id", p.apiKeyId())
                .update());
        return principal;
    }

    private byte[] hash(String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(pepper);
            return mac.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
