package com.cpg.gateway.outbox;

import com.cpg.common.Ids;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records a domain event in the same database transaction as the change that caused it. A separate relay
 * publishes unpublished rows to the event bus, so state and events can never disagree.
 */
@Component
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OutboxWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String aggregateType, String aggregateId, String eventType, Map<String, ?> payload) {
        write(null, aggregateType, aggregateId, eventType, payload);
    }

    /** Records an event that concerns one merchant; it is delivered to that merchant's webhook endpoints. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String merchantId, String aggregateType, String aggregateId, String eventType,
                      Map<String, ?> payload) {
        jdbc.sql("""
                        INSERT INTO outbox_events (id, merchant_id, aggregate_type, aggregate_id, event_type, payload)
                        VALUES (:id, :merchantId, :aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb))
                        """)
                .param("id", Ids.next("evt"))
                .param("merchantId", merchantId)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", json.writeValueAsString(payload))
                .update();
    }
}
