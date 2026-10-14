package com.cpg.gateway.asset;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AssetRepository {

    private static final String SELECT = """
            SELECT a.id, a.chain_code, c.name AS chain_name, a.symbol, a.name, a.contract_address, a.decimals,
                   c.default_confirmations, a.accept_payments
            FROM assets a JOIN chains c ON c.code = a.chain_code
            WHERE a.enabled AND c.enabled
            """;

    private static final RowMapper<Asset> MAPPER = (rs, i) -> new Asset(
            rs.getString("id"),
            rs.getString("chain_code"),
            rs.getString("chain_name"),
            rs.getString("symbol"),
            rs.getString("name"),
            rs.getString("contract_address"),
            rs.getInt("decimals"),
            rs.getInt("default_confirmations"),
            rs.getBoolean("accept_payments"));

    private final JdbcClient jdbc;

    public AssetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Asset> findEnabled() {
        return jdbc.sql(SELECT + " ORDER BY a.chain_code, a.symbol").query(MAPPER).list();
    }

    public Optional<Asset> findEnabled(String id) {
        return jdbc.sql(SELECT + " AND a.id = :id").param("id", id).query(MAPPER).optional();
    }
}
