package com.cpg.gateway.idempotency;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Idempotency keys, each written in its own short transaction outside the request's business transaction. */
@Component
public class IdempotencyStore {

    public record Record(byte[] requestHash, Integer responseStatus, byte[] responseBody, String responseType) {
    }

    private final JdbcClient jdbc;

    public IdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this caller now owns the key and should execute the request */
    public boolean claim(String merchantId, String key, String method, String path, byte[] hash) {
        return jdbc.sql("""
                        INSERT INTO idempotency_keys (merchant_id, key, request_method, request_path, request_hash)
                        VALUES (:merchantId, :key, :method, :path, :hash)
                        ON CONFLICT (merchant_id, key) DO NOTHING
                        """)
                .param("merchantId", merchantId)
                .param("key", key)
                .param("method", method)
                .param("path", path)
                .param("hash", hash)
                .update() == 1;
    }

    public void complete(String merchantId, String key, int status, byte[] body, String contentType) {
        jdbc.sql("""
                        UPDATE idempotency_keys
                        SET response_status = :status, response_body = :body, response_type = :type, completed_at = now()
                        WHERE merchant_id = :merchantId AND key = :key
                        """)
                .param("status", status)
                .param("body", body)
                .param("type", contentType)
                .param("merchantId", merchantId)
                .param("key", key)
                .update();
    }

    public void release(String merchantId, String key) {
        jdbc.sql("DELETE FROM idempotency_keys WHERE merchant_id = :merchantId AND key = :key AND response_status IS NULL")
                .param("merchantId", merchantId)
                .param("key", key)
                .update();
    }

    public Optional<Record> find(String merchantId, String key) {
        return jdbc.sql("""
                        SELECT request_hash, response_status, response_body, response_type
                        FROM idempotency_keys WHERE merchant_id = :merchantId AND key = :key
                        """)
                .param("merchantId", merchantId)
                .param("key", key)
                .query((rs, i) -> new Record(rs.getBytes(1), (Integer) rs.getObject(2), rs.getBytes(3), rs.getString(4)))
                .optional();
    }

    /** Keys are honoured for 24 hours, then expire. */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    public void purgeExpired() {
        jdbc.sql("DELETE FROM idempotency_keys WHERE created_at < now() - interval '24 hours'").update();
    }
}
