package com.cpg.gateway.merchant;

import com.cpg.common.ApiException;
import com.cpg.common.Ids;
import com.cpg.gateway.audit.AuditLog;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantService {

    private static final RowMapper<Merchant> MAPPER = (rs, i) -> new Merchant(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("email"),
            rs.getString("status"),
            rs.getString("kyc_status"),
            rs.getString("country"),
            rs.getString("player_balances"),
            rs.getBoolean("require_signed_requests"),
            rs.getInt("rate_limit_per_second"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;
    private final AuditLog audit;

    public MerchantService(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public Merchant create(String name, String email, String country, AuditLog.Actor actor) {
        String id = Ids.next("mch");
        try {
            jdbc.sql("INSERT INTO merchants (id, name, email, country) VALUES (:id, :name, :email, :country)")
                    .param("id", id)
                    .param("name", name.strip())
                    .param("email", email.strip())
                    .param("country", country)
                    .update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("email_taken", "A merchant with this email already exists");
        }
        Merchant merchant = find(id).orElseThrow();
        audit.record(actor, "merchant.created", "merchant", id, id, Map.of("email", merchant.email()));
        return merchant;
    }

    /** Updates operator-controlled settings; null leaves a setting unchanged. */
    @Transactional
    public Merchant updateSettings(String id, String playerBalances, Boolean requireSignedRequests,
                                   Integer rateLimitPerSecond, AuditLog.Actor actor) {
        Merchant before = require(id);
        jdbc.sql("""
                        UPDATE merchants SET
                            player_balances = COALESCE(:balances, player_balances),
                            require_signed_requests = COALESCE(:signed, require_signed_requests),
                            rate_limit_per_second = COALESCE(:rate, rate_limit_per_second),
                            updated_at = now()
                        WHERE id = :id
                        """)
                .param("balances", playerBalances)
                .param("signed", requireSignedRequests)
                .param("rate", rateLimitPerSecond)
                .param("id", id)
                .update();
        Merchant after = require(id);
        Map<String, Object> changes = new LinkedHashMap<>();
        if (!before.playerBalances().equals(after.playerBalances())) {
            changes.put("player_balances", after.playerBalances());
        }
        if (before.requireSignedRequests() != after.requireSignedRequests()) {
            changes.put("require_signed_requests", after.requireSignedRequests());
        }
        if (before.rateLimitPerSecond() != after.rateLimitPerSecond()) {
            changes.put("rate_limit_per_second", after.rateLimitPerSecond());
        }
        if (!changes.isEmpty()) {
            audit.record(actor, "merchant.settings_updated", "merchant", id, id, changes);
        }
        return after;
    }

    public Optional<Merchant> find(String id) {
        return jdbc.sql("SELECT * FROM merchants WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public Merchant require(String id) {
        return find(id).orElseThrow(() -> ApiException.notFound("Merchant"));
    }
}
