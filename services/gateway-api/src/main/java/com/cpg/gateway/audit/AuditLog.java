package com.cpg.gateway.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the append-only audit trail. Called inside the transaction that makes the change, so an action is
 * audited if and only if it happened.
 */
@Component
public class AuditLog {

    public enum ActorType { merchant, operator, system }

    public record Actor(ActorType type, String id) {

        public static Actor operator(String id) {
            return new Actor(ActorType.operator, id);
        }

        public static Actor merchantKey(String apiKeyId) {
            return new Actor(ActorType.merchant, apiKeyId);
        }

        public static Actor system(String component) {
            return new Actor(ActorType.system, component);
        }
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AuditLog(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Actor actor, String action, String targetType, String targetId, String merchantId,
                       Map<String, ?> details) {
        HttpServletRequest request = currentRequest();
        jdbc.sql("""
                        INSERT INTO audit_log (actor_type, actor_id, action, target_type, target_id, merchant_id,
                                               details, ip, request_id)
                        VALUES (:actorType, :actorId, :action, :targetType, :targetId, :merchantId,
                                CAST(:details AS jsonb), :ip, :requestId)
                        """)
                .param("actorType", actor.type().name())
                .param("actorId", actor.id())
                .param("action", action)
                .param("targetType", targetType)
                .param("targetId", targetId)
                .param("merchantId", merchantId)
                .param("details", json.writeValueAsString(details))
                .param("ip", request == null ? null : request.getRemoteAddr())
                .param("requestId", request == null ? null : request.getHeader("X-Request-Id"))
                .update();
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest()
                : null;
    }
}
