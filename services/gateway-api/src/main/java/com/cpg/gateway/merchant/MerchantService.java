package com.cpg.gateway.merchant;

import com.cpg.common.ApiException;
import com.cpg.common.Ids;
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
            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;

    public MerchantService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Merchant create(String name, String email, String country) {
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
        return find(id).orElseThrow();
    }

    public Optional<Merchant> find(String id) {
        return jdbc.sql("SELECT * FROM merchants WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public Merchant require(String id) {
        return find(id).orElseThrow(() -> ApiException.notFound("Merchant"));
    }
}
