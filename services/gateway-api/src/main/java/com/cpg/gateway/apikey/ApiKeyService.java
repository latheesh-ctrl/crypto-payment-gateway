package com.cpg.gateway.apikey;

import com.cpg.common.ApiException;
import com.cpg.common.Ids;
import com.cpg.gateway.audit.AuditLog;
import com.cpg.gateway.config.GatewayProperties;
import com.cpg.gateway.crypto.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, verifies, rolls and revokes merchant API keys.
 *
 * <p>A key has two secrets, both shown once: the bearer secret {@code sk_<mode>_…} identifies the caller and is
 * stored only as {@code HMAC-SHA256(pepper, secret)}; the signing secret {@code ss_…} signs requests and is
 * stored encrypted, because the server needs it to recompute signatures. Both carry 256 bits of entropy.
 */
@Service
public class ApiKeyService {

    public static final Set<String> ALL_SCOPES =
            Set.of("read", "deposits:write", "payouts:write", "webhooks:manage", "keys:manage");
    /** New keys get everything except payouts unless asked otherwise: moving money out is opt-in per key. */
    public static final Set<String> DEFAULT_SCOPES = Set.of("read", "deposits:write", "webhooks:manage", "keys:manage");
    /** Longest time a rolled key keeps working next to its replacement. */
    public static final Duration MAX_ROLL_OVERLAP = Duration.ofDays(7);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final GatewayProperties properties;
    private final SecretCipher cipher;
    private final AuditLog audit;
    private final SecretKeySpec pepper;

    public ApiKeyService(JdbcClient jdbc, GatewayProperties properties, SecretCipher cipher, AuditLog audit) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.cipher = cipher;
        this.audit = audit;
        this.pepper = new SecretKeySpec(
                properties.security().apiKeyPepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** What a new key may do. Null scopes mean {@link #DEFAULT_SCOPES}; null allowlist means any address. */
    public record KeySpec(String name, Set<String> scopes, List<String> ipAllowlist, Instant expiresAt) {
    }

    /** A key as listed to its merchant; never includes secrets. */
    public record ApiKey(String id, String mode, String name, String last4, List<String> scopes,
                         List<String> ipAllowlist, Instant createdAt, Instant lastUsedAt, Instant expiresAt,
                         Instant revokedAt) {
    }

    /** Everything request authentication needs about the presented key. */
    public record Authenticated(MerchantPrincipal principal, List<String> ipAllowlist, byte[] signingSecretEncrypted,
                                boolean signatureRequired, int rateLimitPerSecond) {
    }

    /**
     * Issues a key. {@code grantable} bounds the scopes: a merchant key can only create keys with scopes it has
     * itself, so a leaked key cannot mint a more powerful one.
     */
    @Transactional
    public IssuedApiKey issue(String merchantId, KeySpec spec, Set<String> grantable, AuditLog.Actor actor) {
        Set<String> scopes = spec.scopes() == null ? DEFAULT_SCOPES : new LinkedHashSet<>(spec.scopes());
        if (scopes.isEmpty() || !ALL_SCOPES.containsAll(scopes)) {
            throw ApiException.badRequest("invalid_scopes", "Scopes must be a non-empty subset of " + sorted(ALL_SCOPES));
        }
        if (!grantable.containsAll(scopes)) {
            throw ApiException.forbidden("scope_not_grantable", "A key cannot grant scopes it does not have");
        }
        List<String> allowlist = validAllowlist(spec.ipAllowlist());
        String mode = properties.mode().name();
        if (scopes.contains("payouts:write") && mode.equals("live") && allowlist == null) {
            throw ApiException.badRequest("ip_allowlist_required", "Live keys with payouts:write need an IP allowlist");
        }

        String secret = "sk_" + mode + "_" + randomToken();
        String signingSecret = "ss_" + randomToken();
        String last4 = secret.substring(secret.length() - 4);
        String id = Ids.next("key");
        String name = spec.name().strip();
        jdbc.sql("""
                        INSERT INTO api_keys (id, merchant_id, mode, name, secret_hash, last4, scopes, ip_allowlist,
                                              expires_at, signing_secret_encrypted)
                        VALUES (:id, :merchantId, :mode, :name, :hash, :last4, :scopes, :allowlist, :expires, :signing)
                        """)
                .param("id", id)
                .param("merchantId", merchantId)
                .param("mode", mode)
                .param("name", name)
                .param("hash", hash(secret))
                .param("last4", last4)
                .param("scopes", sorted(scopes).toArray(String[]::new))
                .param("allowlist", allowlist == null ? null : allowlist.toArray(String[]::new))
                .param("expires", spec.expiresAt() == null ? null : Timestamp.from(spec.expiresAt()))
                .param("signing", cipher.encrypt(signingSecret))
                .update();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mode", mode);
        details.put("last4", last4);
        details.put("scopes", sorted(scopes));
        details.put("ip_allowlist", allowlist);
        audit.record(actor, "api_key.created", "api_key", id, merchantId, details);
        return new IssuedApiKey(id, merchantId, mode, name, secret, signingSecret, last4, sorted(scopes), allowlist,
                spec.expiresAt());
    }

    /** Resolves a presented bearer secret to its key, if valid, unexpired, unrevoked and for this deployment. */
    public Optional<Authenticated> authenticate(String secret) {
        String expectedPrefix = "sk_" + properties.mode().name() + "_";
        if (secret == null || !secret.startsWith(expectedPrefix) || secret.length() > 128) {
            return Optional.empty();
        }
        Optional<Authenticated> found = jdbc.sql("""
                        SELECT k.id, k.merchant_id, k.mode, k.scopes, k.ip_allowlist, k.signing_secret_encrypted,
                               m.require_signed_requests, m.rate_limit_per_second
                        FROM api_keys k JOIN merchants m ON m.id = k.merchant_id
                        WHERE k.secret_hash = :hash AND k.revoked_at IS NULL AND m.status = 'active'
                          AND (k.expires_at IS NULL OR k.expires_at > now())
                        """)
                .param("hash", hash(secret))
                .query((rs, i) -> {
                    String mode = rs.getString("mode");
                    return new Authenticated(
                            new MerchantPrincipal(rs.getString("merchant_id"), rs.getString("id"), mode,
                                    Set.copyOf(strings(rs, "scopes"))),
                            strings(rs, "ip_allowlist"),
                            rs.getBytes("signing_secret_encrypted"),
                            mode.equals("live") || rs.getBoolean("require_signed_requests"),
                            rs.getInt("rate_limit_per_second"));
                })
                .optional();

        // Throttled so hot keys don't turn every request into a row write.
        found.ifPresent(a -> jdbc.sql("""
                        UPDATE api_keys SET last_used_at = now()
                        WHERE id = :id AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')
                        """)
                .param("id", a.principal().apiKeyId())
                .update());
        return found;
    }

    public String signingSecret(byte[] encrypted) {
        return cipher.decrypt(encrypted);
    }

    public List<ApiKey> list(String merchantId) {
        return jdbc.sql("SELECT * FROM api_keys WHERE merchant_id = :m AND mode = :mode ORDER BY created_at DESC")
                .param("m", merchantId).param("mode", properties.mode().name())
                .query((rs, i) -> map(rs)).list();
    }

    @Transactional
    public ApiKey revoke(String merchantId, String keyId, AuditLog.Actor actor) {
        ApiKey key = require(merchantId, keyId);
        if (key.revokedAt() == null) {
            jdbc.sql("UPDATE api_keys SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL")
                    .param("id", keyId).update();
            audit.record(actor, "api_key.revoked", "api_key", keyId, merchantId, Map.of("last4", key.last4()));
        }
        return require(merchantId, keyId);
    }

    /**
     * Replaces a key with a new one carrying the same name, scopes and allowlist. The old key keeps working for
     * {@code overlap} (at most {@link #MAX_ROLL_OVERLAP}) so the merchant can deploy the new secrets first.
     */
    @Transactional
    public IssuedApiKey roll(String merchantId, String keyId, Duration overlap, Set<String> grantable,
                             AuditLog.Actor actor) {
        if (overlap.isNegative() || overlap.compareTo(MAX_ROLL_OVERLAP) > 0) {
            throw ApiException.badRequest("invalid_overlap", "Overlap must be between 0 and 7 days");
        }
        ApiKey old = jdbc.sql("SELECT * FROM api_keys WHERE id = :id AND merchant_id = :m FOR UPDATE")
                .param("id", keyId).param("m", merchantId).query((rs, i) -> map(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("API key"));
        if (old.revokedAt() != null || (old.expiresAt() != null && old.expiresAt().isBefore(Instant.now()))) {
            throw ApiException.conflict("key_inactive", "Only an active key can be rolled");
        }
        IssuedApiKey replacement = issue(merchantId, new KeySpec(old.name(), Set.copyOf(old.scopes()),
                old.ipAllowlist(), null), grantable, actor);
        Instant oldExpires = Instant.now().plus(overlap);
        if (old.expiresAt() == null || old.expiresAt().isAfter(oldExpires)) {
            jdbc.sql("UPDATE api_keys SET expires_at = :e WHERE id = :id")
                    .param("e", Timestamp.from(oldExpires)).param("id", keyId).update();
        }
        audit.record(actor, "api_key.rolled", "api_key", keyId, merchantId,
                Map.of("replaced_by", replacement.id(), "expires_at", oldExpires.toString()));
        return replacement;
    }

    private ApiKey require(String merchantId, String keyId) {
        return jdbc.sql("SELECT * FROM api_keys WHERE id = :id AND merchant_id = :m")
                .param("id", keyId).param("m", merchantId).query((rs, i) -> map(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("API key"));
    }

    private static ApiKey map(ResultSet rs) throws SQLException {
        return new ApiKey(rs.getString("id"), rs.getString("mode"), rs.getString("name"), rs.getString("last4"),
                strings(rs, "scopes"), strings(rs, "ip_allowlist"), instant(rs, "created_at"),
                instant(rs, "last_used_at"), instant(rs, "expires_at"), instant(rs, "revoked_at"));
    }

    private static List<String> validAllowlist(List<String> allowlist) {
        if (allowlist == null) {
            return null;
        }
        if (allowlist.isEmpty()) {
            throw ApiException.badRequest("invalid_ip_allowlist", "Omit ip_allowlist to allow any address");
        }
        for (String entry : allowlist) {
            try {
                new IpAddressMatcher(entry);
                if (!entry.matches("[0-9a-fA-F:.]+(/\\d{1,3})?")) {
                    throw new IllegalArgumentException(entry);
                }
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("invalid_ip_allowlist", "Not an IP address or CIDR range: " + entry);
            }
        }
        return List.copyOf(allowlist);
    }

    private static List<String> strings(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        return array == null ? null : Arrays.asList((String[]) array.getArray());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static String randomToken() {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
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
