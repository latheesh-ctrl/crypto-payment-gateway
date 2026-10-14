package com.cpg.gateway.webhook;

import com.cpg.gateway.config.GatewayProperties;
import com.cpg.gateway.crypto.SecretCipher;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers due webhooks with at-least-once semantics. Each delivery is leased before sending so concurrent
 * workers never send the same attempt twice. Failures back off exponentially (10s doubling, capped at 6h)
 * until {@code maxAttempts}, then the delivery is marked failed and can be replayed by the merchant.
 */
@Component
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final SecretCipher cipher;
    private final JsonMapper json;
    private final GatewayProperties properties;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public WebhookSender(JdbcClient jdbc, SecretCipher cipher, JsonMapper json, GatewayProperties properties) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.json = json;
        this.properties = properties;
        this.clock = Clock.systemUTC();
    }

    record Due(String deliveryId, int attempts, String url, byte[] secret, String eventId, String eventType,
               String payload, java.time.Instant createdAt) {
    }

    /** @return number of deliveries attempted */
    public int sendDue(int limit) {
        List<Due> due = jdbc.sql("""
                        UPDATE webhook_deliveries d
                        SET next_attempt_at = now() + CAST(:lease AS interval)
                        FROM webhook_endpoints e, outbox_events ev
                        WHERE d.id IN (
                                SELECT id FROM webhook_deliveries
                                WHERE status = 'pending' AND next_attempt_at <= now()
                                ORDER BY next_attempt_at
                                LIMIT :limit
                                FOR UPDATE SKIP LOCKED)
                          AND e.id = d.endpoint_id AND ev.id = d.event_id
                        RETURNING d.id, d.attempts, e.url, e.secret_encrypted, ev.id, ev.event_type,
                                  ev.payload::text, ev.created_at
                        """)
                .param("lease", LEASE.toSeconds() + " seconds")
                .param("limit", limit)
                .query((rs, i) -> new Due(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getBytes(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant()))
                .list();
        due.forEach(this::attempt);
        return due.size();
    }

    private void attempt(Due d) {
        Integer statusCode = null;
        String error = null;
        try {
            URI uri = URI.create(d.url());
            guardAgainstInternalTargets(uri);
            byte[] body = body(d);
            long ts = clock.instant().getEpochSecond();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "CPG-Webhooks/1.0")
                    .header("CPG-Event-Id", d.eventId())
                    .header(WebhookSigner.HEADER, WebhookSigner.header(cipher.decrypt(d.secret()), ts, body))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            statusCode = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException | IllegalArgumentException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "interrupted";
        }

        int attempts = d.attempts() + 1;
        if (statusCode != null && statusCode >= 200 && statusCode < 300) {
            jdbc.sql("""
                            UPDATE webhook_deliveries SET status = 'succeeded', attempts = :a, last_status_code = :code,
                                   last_error = NULL, completed_at = now()
                            WHERE id = :id
                            """)
                    .param("a", attempts).param("code", statusCode).param("id", d.deliveryId()).update();
            return;
        }
        boolean giveUp = attempts >= properties.webhooks().maxAttempts();
        long backoffSeconds = Math.min(10L << Math.min(attempts - 1, 20), Duration.ofHours(6).toSeconds());
        jdbc.sql("""
                        UPDATE webhook_deliveries
                        SET attempts = :a, last_status_code = :code, last_error = :error,
                            status = CASE WHEN :giveUp THEN 'failed' ELSE 'pending' END,
                            next_attempt_at = now() + CAST(:backoff AS interval),
                            completed_at = CASE WHEN :giveUp THEN now() END
                        WHERE id = :id
                        """)
                .param("a", attempts)
                .param("code", statusCode)
                .param("error", error == null ? "HTTP " + statusCode : truncate(error))
                .param("giveUp", giveUp)
                .param("backoff", backoffSeconds + " seconds")
                .param("id", d.deliveryId())
                .update();
        log.info("Webhook delivery {} attempt {} failed ({}); {}", d.deliveryId(), attempts,
                error == null ? "HTTP " + statusCode : error, giveUp ? "giving up" : "retrying in " + backoffSeconds + "s");
    }

    private byte[] body(Due d) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", d.eventId());
        envelope.put("type", d.eventType());
        envelope.put("created_at", d.createdAt().toString());
        envelope.put("livemode", properties.mode() == GatewayProperties.Mode.live);
        envelope.put("data", json.readValue(d.payload(), MAP));
        return json.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8);
    }

    /** In live mode, never let a merchant-supplied URL reach our own network (SSRF). */
    private void guardAgainstInternalTargets(URI uri) throws UnknownHostException {
        if (properties.mode() != GatewayProperties.Mode.live) {
            return;
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress() || address.isMulticastAddress()) {
                throw new IllegalArgumentException("webhook URL resolves to a non-public address");
            }
        }
    }

    private static String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
