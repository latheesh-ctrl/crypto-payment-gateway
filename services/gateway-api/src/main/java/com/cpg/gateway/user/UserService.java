package com.cpg.gateway.user;

import com.cpg.common.ApiException;
import com.cpg.common.Ids;
import com.cpg.gateway.audit.AuditLog;
import com.cpg.gateway.outbox.OutboxWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Every query is scoped by merchant, so one merchant can never see or act on another merchant's users. */
@Service
public class UserService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AuditLog audit;
    private final OutboxWriter outbox;
    private final RowMapper<User> mapper;

    public UserService(JdbcClient jdbc, JsonMapper json, AuditLog audit, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
        this.outbox = outbox;
        this.mapper = (rs, i) -> new User(
                rs.getString("id"),
                rs.getString("merchant_id"),
                rs.getString("external_ref"),
                rs.getString("status"),
                rs.getString("kyc_status"),
                json.readValue(rs.getString("metadata"), MAP),
                rs.getTimestamp("created_at").toInstant());
    }

    @Transactional
    public User create(String merchantId, String externalRef, Map<String, Object> metadata, AuditLog.Actor actor) {
        String id = Ids.next("usr");
        try {
            jdbc.sql("""
                            INSERT INTO users (id, merchant_id, external_ref, metadata)
                            VALUES (:id, :merchantId, :externalRef, CAST(:metadata AS jsonb))
                            """)
                    .param("id", id)
                    .param("merchantId", merchantId)
                    .param("externalRef", externalRef.strip())
                    .param("metadata", json.writeValueAsString(metadata == null ? Map.of() : metadata))
                    .update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("user_exists", "A user with this external_ref already exists");
        }
        User user = require(merchantId, id);
        audit.record(actor, "user.created", "user", id, merchantId, Map.of("external_ref", user.externalRef()));
        outbox.write(merchantId, "user", id, "user.created", toEvent(user));
        return user;
    }

    public Optional<User> find(String merchantId, String id) {
        return jdbc.sql("SELECT * FROM users WHERE merchant_id = :merchantId AND id = :id")
                .param("merchantId", merchantId)
                .param("id", id)
                .query(mapper)
                .optional();
    }

    public User require(String merchantId, String id) {
        return find(merchantId, id).orElseThrow(() -> ApiException.notFound("User"));
    }

    public Optional<User> findByExternalRef(String merchantId, String externalRef) {
        return jdbc.sql("SELECT * FROM users WHERE merchant_id = :merchantId AND external_ref = :ref")
                .param("merchantId", merchantId)
                .param("ref", externalRef)
                .query(mapper)
                .optional();
    }

    /** Keyset pagination: newest first, starting after {@code startingAfter} when given. */
    public List<User> list(String merchantId, String startingAfter, int limit) {
        return jdbc.sql("""
                        SELECT * FROM users
                        WHERE merchant_id = :merchantId AND (CAST(:after AS text) IS NULL OR id < :after)
                        ORDER BY id DESC
                        LIMIT :limit
                        """)
                .param("merchantId", merchantId)
                .param("after", startingAfter)
                .param("limit", limit)
                .query(mapper)
                .list();
    }

    private static Map<String, Object> toEvent(User user) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", user.id());
        event.put("external_ref", user.externalRef());
        event.put("status", user.status());
        event.put("metadata", user.metadata());
        event.put("created_at", user.createdAt().toString());
        return event;
    }
}
