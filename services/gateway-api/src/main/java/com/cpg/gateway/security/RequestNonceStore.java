package com.cpg.gateway.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Remembers nonces of signed requests long enough to refuse any replay inside the timestamp window. */
@Component
public class RequestNonceStore {

    private final JdbcClient jdbc;

    public RequestNonceStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false when this key already used the nonce */
    public boolean claim(String apiKeyId, String nonce) {
        return jdbc.sql("INSERT INTO request_nonces (api_key_id, nonce) VALUES (:k, :n) ON CONFLICT DO NOTHING")
                .param("k", apiKeyId).param("n", nonce).update() == 1;
    }

    /** Nonces older than twice the timestamp tolerance can no longer be replayed: their timestamps are refused. */
    public int purge() {
        return jdbc.sql("DELETE FROM request_nonces WHERE seen_at < now() - CAST(:age AS interval)")
                .param("age", (2 * RequestSignature.TOLERANCE_SECONDS) + " seconds").update();
    }

    @Component
    @ConditionalOnProperty(name = "gateway.jobs.enabled", havingValue = "true", matchIfMissing = true)
    static class Purger {

        private final RequestNonceStore store;

        Purger(RequestNonceStore store) {
            this.store = store;
        }

        @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
        void purge() {
            store.purge();
        }
    }
}
