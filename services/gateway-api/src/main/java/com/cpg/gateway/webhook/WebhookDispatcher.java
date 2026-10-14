package com.cpg.gateway.webhook;

import com.cpg.common.Ids;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fans unpublished outbox events out into one delivery per subscribed endpoint. Uses SKIP LOCKED so several
 * instances can run it concurrently without double-dispatching.
 */
@Component
public class WebhookDispatcher {

    private final JdbcClient jdbc;

    public WebhookDispatcher(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return number of events dispatched */
    @Transactional
    public int dispatchBatch(int limit) {
        record Event(String id, String merchantId, String type) {
        }
        List<Event> events = jdbc.sql("""
                        SELECT id, merchant_id, event_type FROM outbox_events
                        WHERE published_at IS NULL
                        ORDER BY created_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query((rs, i) -> new Event(rs.getString(1), rs.getString(2), rs.getString(3)))
                .list();

        for (Event event : events) {
            if (event.merchantId() != null) {
                List<String> endpointIds = jdbc.sql("""
                                SELECT id FROM webhook_endpoints
                                WHERE merchant_id = :m AND status = 'enabled'
                                  AND ('*' = ANY (event_types) OR :type = ANY (event_types))
                                """)
                        .param("m", event.merchantId())
                        .param("type", event.type())
                        .query(String.class)
                        .list();
                for (String endpointId : endpointIds) {
                    jdbc.sql("""
                                    INSERT INTO webhook_deliveries (id, endpoint_id, event_id)
                                    VALUES (:id, :endpoint, :event)
                                    ON CONFLICT (endpoint_id, event_id) DO NOTHING
                                    """)
                            .param("id", Ids.next("whd"))
                            .param("endpoint", endpointId)
                            .param("event", event.id())
                            .update();
                }
            }
            jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE id = :id").param("id", event.id()).update();
        }
        return events.size();
    }
}
