package com.cpg.gateway.webhook;

import com.cpg.common.ApiException;
import com.cpg.common.Ids;
import com.cpg.gateway.audit.AuditLog;
import com.cpg.gateway.config.GatewayProperties;
import com.cpg.gateway.crypto.SecretCipher;
import java.net.URI;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookEndpointService {

    public static final int MAX_ENDPOINTS_PER_MERCHANT = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Created(WebhookEndpoint endpoint, String secret) {
    }

    private static final RowMapper<WebhookEndpoint> MAPPER = (rs, i) -> new WebhookEndpoint(
            rs.getString("id"),
            rs.getString("url"),
            textArray(rs.getArray("event_types")),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;
    private final SecretCipher cipher;
    private final AuditLog audit;
    private final GatewayProperties properties;

    public WebhookEndpointService(JdbcClient jdbc, SecretCipher cipher, AuditLog audit,
                                  GatewayProperties properties) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.audit = audit;
        this.properties = properties;
    }

    @Transactional
    public Created create(String merchantId, String url, List<String> eventTypes, AuditLog.Actor actor) {
        validateUrl(url);
        long existing = jdbc.sql("SELECT count(*) FROM webhook_endpoints WHERE merchant_id = :m AND status = 'enabled'")
                .param("m", merchantId).query(Long.class).single();
        if (existing >= MAX_ENDPOINTS_PER_MERCHANT) {
            throw ApiException.badRequest("too_many_webhook_endpoints",
                    "At most " + MAX_ENDPOINTS_PER_MERCHANT + " enabled webhook endpoints are allowed");
        }
        String secret = newSecret();
        String id = Ids.next("we");
        jdbc.sql("""
                        INSERT INTO webhook_endpoints (id, merchant_id, url, secret_encrypted, event_types)
                        VALUES (:id, :merchantId, :url, :secret, :types)
                        """)
                .param("id", id)
                .param("merchantId", merchantId)
                .param("url", url)
                .param("secret", cipher.encrypt(secret))
                .param("types", eventTypes.toArray(String[]::new))
                .update();
        audit.record(actor, "webhook_endpoint.created", "webhook_endpoint", id, merchantId,
                Map.of("url", url, "event_types", eventTypes));
        return new Created(require(merchantId, id), secret);
    }

    public List<WebhookEndpoint> list(String merchantId) {
        return jdbc.sql("SELECT * FROM webhook_endpoints WHERE merchant_id = :m ORDER BY created_at DESC")
                .param("m", merchantId).query(MAPPER).list();
    }

    public WebhookEndpoint require(String merchantId, String id) {
        return jdbc.sql("SELECT * FROM webhook_endpoints WHERE merchant_id = :m AND id = :id")
                .param("m", merchantId).param("id", id).query(MAPPER).optional()
                .orElseThrow(() -> ApiException.notFound("Webhook endpoint"));
    }

    /**
     * Issues a new signing secret. The previous one keeps signing alongside it for {@code overlap}, so the merchant
     * can deploy the new secret without rejecting deliveries in between.
     */
    @Transactional
    public Created rotateSecret(String merchantId, String id, java.time.Duration overlap, AuditLog.Actor actor) {
        if (overlap.isNegative() || overlap.compareTo(java.time.Duration.ofDays(7)) > 0) {
            throw ApiException.badRequest("invalid_overlap", "Overlap must be between 0 and 7 days");
        }
        require(merchantId, id);
        String secret = newSecret();
        jdbc.sql("""
                        UPDATE webhook_endpoints
                        SET previous_secret_encrypted = secret_encrypted,
                            previous_secret_expires_at = now() + CAST(:overlap AS interval),
                            secret_encrypted = :secret
                        WHERE merchant_id = :m AND id = :id
                        """)
                .param("overlap", overlap.toSeconds() + " seconds")
                .param("secret", cipher.encrypt(secret))
                .param("m", merchantId).param("id", id).update();
        audit.record(actor, "webhook_endpoint.secret_rotated", "webhook_endpoint", id, merchantId,
                Map.of("overlap_seconds", overlap.toSeconds()));
        return new Created(require(merchantId, id), secret);
    }

    @Transactional
    public WebhookEndpoint disable(String merchantId, String id, AuditLog.Actor actor) {
        require(merchantId, id);
        jdbc.sql("UPDATE webhook_endpoints SET status = 'disabled' WHERE merchant_id = :m AND id = :id")
                .param("m", merchantId).param("id", id).update();
        audit.record(actor, "webhook_endpoint.disabled", "webhook_endpoint", id, merchantId, Map.of());
        return require(merchantId, id);
    }

    private static String newSecret() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        return "whsec_" + HexFormat.of().formatHex(raw);
    }

    private void validateUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_url", "url is not a valid URL");
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean httpAllowed = properties.mode() == GatewayProperties.Mode.test && "http".equalsIgnoreCase(uri.getScheme());
        if (uri.getHost() == null || !(https || httpAllowed)) {
            throw ApiException.badRequest("invalid_url",
                    "url must be an absolute https URL" + (httpAllowed ? " (http is allowed in sandbox)" : ""));
        }
        if (uri.getUserInfo() != null) {
            throw ApiException.badRequest("invalid_url", "url must not contain credentials");
        }
    }

    private static List<String> textArray(Array array) throws SQLException {
        return Arrays.asList((String[]) array.getArray());
    }
}
