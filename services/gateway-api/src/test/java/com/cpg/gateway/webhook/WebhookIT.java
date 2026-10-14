package com.cpg.gateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cpg.gateway.IntegrationTest;
import com.cpg.gateway.TestMerchants;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class WebhookIT {

    record Received(String signature, String eventId, byte[] body) {
    }

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired WebhookDispatcher dispatcher;
    @Autowired WebhookSender sender;

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger responseCode = new AtomicInteger(200);
    private TestMerchants.Onboarded merchant;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            received.add(new Received(exchange.getRequestHeaders().getFirst("CPG-Signature"),
                    exchange.getRequestHeaders().getFirst("CPG-Event-Id"), exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(responseCode.get(), -1);
            exchange.close();
        });
        server.start();
        merchant = TestMerchants.onboard(mvc, json);
        // Isolate from events left by other tests.
        jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE published_at IS NULL").update();
        jdbc.sql("UPDATE webhook_deliveries SET status = 'failed' WHERE status = 'pending'").update();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private String registerEndpoint(String eventTypesJson) throws Exception {
        String body = mvc.perform(merchant.post("/v1/webhook_endpoints",
                        "{\"url\":\"%s\",\"event_types\":%s}".formatted(url(), eventTypesJson)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").exists())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("secret").asString();
    }

    private void createUser(String ref) throws Exception {
        mvc.perform(merchant.post("/v1/users", "{\"external_ref\":\"%s\"}".formatted(ref))).andExpect(status().isCreated());
    }

    @Test
    void deliversSignedEventToSubscribedEndpoint() throws Exception {
        String secret = registerEndpoint("[\"user.created\"]");
        createUser("hooked");

        assertThat(dispatcher.dispatchBatch(100)).isPositive();
        assertThat(sender.sendDue(50)).isEqualTo(1);

        assertThat(received).hasSize(1);
        Received r = received.getFirst();
        JsonNode event = json.readTree(r.body());
        assertThat(event.get("type").asString()).isEqualTo("user.created");
        assertThat(event.get("data").get("external_ref").asString()).isEqualTo("hooked");
        assertThat(event.get("livemode").asBoolean()).isFalse();
        assertThat(r.eventId()).isEqualTo(event.get("id").asString());

        // Verify exactly as a merchant would.
        String[] parts = r.signature().split(",");
        long ts = Long.parseLong(parts[0].substring(2));
        assertThat(Math.abs(Instant.now().getEpochSecond() - ts)).isLessThan(60);
        assertThat(parts[1]).isEqualTo("v1=" + WebhookSigner.sign(secret, ts, r.body()));
        assertThat(WebhookSigner.sign("wrong-secret", ts, r.body())).isNotEqualTo(parts[1].substring(3));

        assertThat(jdbc.sql("SELECT status FROM webhook_deliveries WHERE event_id = :e")
                .param("e", r.eventId()).query(String.class).single()).isEqualTo("succeeded");
    }

    @Test
    void unsubscribedEventTypesAreNotDelivered() throws Exception {
        registerEndpoint("[\"deposit.completed\"]");
        createUser("ignored");
        dispatcher.dispatchBatch(100);
        assertThat(sender.sendDue(50)).isZero();
        assertThat(received).isEmpty();
    }

    @Test
    void failedDeliveryIsRetriedWithBackoff() throws Exception {
        registerEndpoint("[\"*\"]");
        responseCode.set(500);
        createUser("flaky");
        dispatcher.dispatchBatch(100);
        assertThat(sender.sendDue(50)).isEqualTo(1);

        record State(String status, int attempts, boolean scheduledLater) {
        }
        State state = jdbc.sql("""
                        SELECT d.status, d.attempts, d.next_attempt_at > now() + interval '5 seconds'
                        FROM webhook_deliveries d JOIN webhook_endpoints e ON e.id = d.endpoint_id
                        WHERE e.merchant_id = :m
                        """)
                .param("m", merchant.merchantId())
                .query((rs, i) -> new State(rs.getString(1), rs.getInt(2), rs.getBoolean(3)))
                .single();
        assertThat(state).isEqualTo(new State("pending", 1, true));
        // Not due yet, so nothing is resent immediately.
        assertThat(sender.sendDue(50)).isZero();

        // Once due again and the endpoint recovers, it succeeds.
        responseCode.set(204);
        jdbc.sql("UPDATE webhook_deliveries SET next_attempt_at = now() WHERE status = 'pending'").update();
        assertThat(sender.sendDue(50)).isEqualTo(1);
        assertThat(received).hasSize(2);
    }

    @Test
    void disabledEndpointsStopReceiving() throws Exception {
        registerEndpoint("[\"*\"]");
        String id = json.readTree(mvc.perform(merchant.authorize(get("/v1/webhook_endpoints")))
                .andReturn().getResponse().getContentAsString()).get("data").get(0).get("id").asString();
        mvc.perform(merchant.post("/v1/webhook_endpoints/" + id + "/disable", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("disabled"));
        createUser("after-disable");
        dispatcher.dispatchBatch(100);
        assertThat(sender.sendDue(50)).isZero();
    }

    @Test
    void secretIsNeverListedAndIsEncryptedAtRest() throws Exception {
        String secret = registerEndpoint("[\"*\"]");
        String listing = mvc.perform(merchant.authorize(get("/v1/webhook_endpoints")))
                .andReturn().getResponse().getContentAsString();
        assertThat(listing).doesNotContain(secret).doesNotContain("secret");
        byte[] stored = jdbc.sql("SELECT secret_encrypted FROM webhook_endpoints WHERE merchant_id = :m")
                .param("m", merchant.merchantId()).query(byte[].class).single();
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(secret);
    }

    @Test
    void rejectsNonHttpUrls() throws Exception {
        mvc.perform(merchant.post("/v1/webhook_endpoints", "{\"url\":\"ftp://x.test/hook\",\"event_types\":[\"*\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_url"));
    }

    @Test
    void rotatedSecretSignsAlongsideThePreviousOneDuringTheOverlap() throws Exception {
        String oldSecret = registerEndpoint("[\"user.created\"]");
        String id = json.readTree(mvc.perform(merchant.authorize(get("/v1/webhook_endpoints")))
                .andReturn().getResponse().getContentAsString()).get("data").get(0).get("id").asString();
        String newSecret = json.readTree(mvc.perform(merchant.post("/v1/webhook_endpoints/" + id + "/rotate_secret",
                        "{\"overlap_hours\":1}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("secret").asString();
        assertThat(newSecret).isNotEqualTo(oldSecret);

        createUser("rotating");
        dispatcher.dispatchBatch(100);
        sender.sendDue(50);
        Received r = received.getFirst();
        String[] parts = r.signature().split(",");
        long ts = Long.parseLong(parts[0].substring(2));
        assertThat(parts).containsExactly("t=" + ts, "v1=" + WebhookSigner.sign(newSecret, ts, r.body()),
                "v1=" + WebhookSigner.sign(oldSecret, ts, r.body()));

        // After the overlap only the new secret signs.
        jdbc.sql("UPDATE webhook_endpoints SET previous_secret_expires_at = now() WHERE id = :id").param("id", id).update();
        createUser("rotated");
        dispatcher.dispatchBatch(100);
        sender.sendDue(50);
        assertThat(received.get(1).signature().split(",")).hasSize(2);
    }

    @Test
    void eventLogShowsEveryAttemptAndCanRedeliver() throws Exception {
        registerEndpoint("[\"*\"]");
        responseCode.set(503);
        createUser("logged");
        dispatcher.dispatchBatch(100);
        sender.sendDue(50);

        JsonNode list = json.readTree(mvc.perform(merchant.authorize(get("/v1/webhook_events").param("type", "user.created")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String eventId = list.get("data").get(0).get("id").asString();
        assertThat(list.get("data").get(0).get("data").get("external_ref").asString()).isEqualTo("logged");

        mvc.perform(merchant.authorize(get("/v1/webhook_events/" + eventId)))
                .andExpect(jsonPath("$.deliveries[0].status").value("pending"))
                .andExpect(jsonPath("$.deliveries[0].attempts").value(1))
                .andExpect(jsonPath("$.deliveries[0].attempt_log[0].status_code").value(503));

        // The merchant fixes its endpoint and asks for the event again without waiting for the backoff.
        responseCode.set(200);
        mvc.perform(merchant.post("/v1/webhook_events/" + eventId + "/redeliver", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries[0].attempts").value(0));
        assertThat(sender.sendDue(50)).isEqualTo(1);
        mvc.perform(merchant.authorize(get("/v1/webhook_events/" + eventId)))
                .andExpect(jsonPath("$.deliveries[0].status").value("succeeded"))
                .andExpect(jsonPath("$.deliveries[0].attempt_log.length()").value(2));

        // Another merchant cannot see it.
        TestMerchants.Onboarded other = TestMerchants.onboard(mvc, json);
        mvc.perform(other.authorize(get("/v1/webhook_events/" + eventId))).andExpect(status().isNotFound());
    }
}
