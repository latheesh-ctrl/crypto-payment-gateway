package com.cpg.gateway.webhook;

import com.cpg.common.ApiException;
import com.cpg.gateway.apikey.MerchantPrincipal;
import com.cpg.gateway.audit.AuditLog;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import com.cpg.gateway.web.JsonMaps;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The merchant's event log and delivery log: every event sent (or to be sent), each delivery to each endpoint
 * with every attempt, and a redeliver action for events the merchant missed.
 */
@RestController
@Validated
@RequestMapping("/v1/webhook_events")
public class WebhookEventController {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AuditLog audit;

    public WebhookEventController(JdbcClient jdbc, JsonMapper json, AuditLog audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    @GetMapping
    public EventList list(@AuthenticationPrincipal MerchantPrincipal merchant,
                          @RequestParam(required = false) String type,
                          @RequestParam(name = "starting_after", required = false) String startingAfter,
                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        List<Map<String, Object>> page = jdbc.sql("""
                        SELECT id, event_type, aggregate_id, payload::text, created_at FROM outbox_events
                        WHERE merchant_id = :m
                          AND (CAST(:type AS text) IS NULL OR event_type = :type)
                          AND (CAST(:after AS text) IS NULL OR id < :after)
                        ORDER BY id DESC LIMIT :limit
                        """)
                .param("m", merchant.merchantId()).param("type", type).param("after", startingAfter)
                .param("limit", limit + 1)
                .query((rs, i) -> event(rs.getString(1), rs.getString(2), rs.getString(4), rs.getTimestamp(5)))
                .list();
        boolean hasMore = page.size() > limit;
        return new EventList(hasMore ? page.subList(0, limit) : page, hasMore);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable String id) {
        Map<String, Object> event = jdbc.sql("""
                        SELECT id, event_type, aggregate_id, payload::text, created_at FROM outbox_events
                        WHERE id = :id AND merchant_id = :m
                        """)
                .param("id", id).param("m", merchant.merchantId())
                .query((rs, i) -> event(rs.getString(1), rs.getString(2), rs.getString(4), rs.getTimestamp(5)))
                .optional().orElseThrow(() -> ApiException.notFound("Event"));
        event.put("deliveries", deliveries(id));
        return event;
    }

    /** Queues the event again for every endpoint it was delivered to (or failed to reach). */
    @PostMapping("/{id}/redeliver")
    @Transactional
    public Map<String, Object> redeliver(@AuthenticationPrincipal MerchantPrincipal merchant, @PathVariable String id) {
        get(merchant, id);
        int queued = jdbc.sql("""
                        UPDATE webhook_deliveries d SET status = 'pending', attempts = 0, next_attempt_at = now(),
                               completed_at = NULL
                        FROM webhook_endpoints e
                        WHERE d.event_id = :id AND e.id = d.endpoint_id AND e.status = 'enabled'
                        """)
                .param("id", id).update();
        if (queued == 0) {
            throw ApiException.conflict("nothing_to_redeliver", "No enabled endpoint has a delivery for this event");
        }
        audit.record(AuditLog.Actor.merchantKey(merchant.apiKeyId()), "webhook_event.redelivered", "webhook_event",
                id, merchant.merchantId(), Map.of("deliveries", queued));
        return get(merchant, id);
    }

    private Map<String, Object> event(String id, String type, String payload, Timestamp createdAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("created_at", createdAt.toInstant().toString());
        m.put("data", json.readValue(payload, MAP));
        return m;
    }

    private List<Map<String, Object>> deliveries(String eventId) {
        return jdbc.sql("""
                        SELECT d.id, d.endpoint_id, e.url, d.status, d.attempts, d.next_attempt_at, d.last_status_code,
                               d.last_error, d.completed_at
                        FROM webhook_deliveries d JOIN webhook_endpoints e ON e.id = d.endpoint_id
                        WHERE d.event_id = :id ORDER BY d.created_at
                        """)
                .param("id", eventId)
                .query((rs, i) -> JsonMaps.ordered(
                        "id", rs.getString("id"),
                        "endpoint_id", rs.getString("endpoint_id"),
                        "url", rs.getString("url"),
                        "status", rs.getString("status"),
                        "attempts", rs.getInt("attempts"),
                        "next_attempt_at", rs.getString("status").equals("pending")
                                ? rs.getTimestamp("next_attempt_at").toInstant().toString() : null,
                        "last_status_code", rs.getObject("last_status_code"),
                        "last_error", rs.getString("last_error"),
                        "completed_at", rs.getTimestamp("completed_at") == null ? null
                                : rs.getTimestamp("completed_at").toInstant().toString(),
                        "attempt_log", attempts(rs.getString("id"))))
                .list();
    }

    private List<Map<String, Object>> attempts(String deliveryId) {
        return jdbc.sql("""
                        SELECT attempt, status_code, error, duration_ms, attempted_at FROM webhook_delivery_attempts
                        WHERE delivery_id = :id ORDER BY id
                        """)
                .param("id", deliveryId)
                .query((rs, i) -> JsonMaps.ordered(
                        "attempt", rs.getInt(1), "status_code", rs.getObject(2), "error", rs.getString(3),
                        "duration_ms", rs.getInt(4), "attempted_at", rs.getTimestamp(5).toInstant().toString()))
                .list();
    }

    public record EventList(List<Map<String, Object>> data, boolean hasMore) {
    }
}
