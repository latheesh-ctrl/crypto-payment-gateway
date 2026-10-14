package com.cpg.gateway.wallet;

import com.cpg.common.ApiException;
import com.cpg.common.Ids;
import com.cpg.gateway.audit.AuditLog;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Network;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands out deposit addresses: one permanent address per (owner, chain), derived as {@code xpub/0/index}.
 *
 * <p>An address is a mapping to an internal owner, not a separate wallet: all addresses on a chain are children
 * of one custody-held key, and funds are swept into the platform's treasury wallets.
 */
@Service
public class DepositAddressService {

    public enum OwnerType { user, merchant, session }

    public record DepositAddress(String id, String merchantId, OwnerType ownerType, String ownerId, String chain,
                                 String address, long derivationIndex, Instant createdAt) {
    }

    private static final RowMapper<DepositAddress> MAPPER = (rs, i) -> new DepositAddress(
            rs.getString("id"),
            rs.getString("merchant_id"),
            OwnerType.valueOf(rs.getString("owner_type")),
            rs.getString("owner_id"),
            rs.getString("chain_code"),
            rs.getString("address"),
            rs.getLong("derivation_index"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;
    private final WalletProperties wallets;
    private final AuditLog audit;
    private final Map<String, DeterministicKey> accountKeys = new ConcurrentHashMap<>();

    public DepositAddressService(JdbcClient jdbc, WalletProperties wallets, AuditLog audit) {
        this.jdbc = jdbc;
        this.wallets = wallets;
        this.audit = audit;
    }

    /** Returns the owner's address on this chain, creating it on first request. */
    @Transactional
    public DepositAddress getOrCreate(String merchantId, OwnerType ownerType, String ownerId, String chainCode,
                                      AuditLog.Actor actor) {
        Optional<DepositAddress> existing = find(ownerType, ownerId, chainCode);
        if (existing.isPresent()) {
            return existing.get();
        }
        String family = jdbc.sql("SELECT family FROM chains WHERE code = :c AND enabled")
                .param("c", chainCode).query(String.class).optional()
                .orElseThrow(() -> ApiException.badRequest("unsupported_chain", "Chain " + chainCode + " is not supported"));
        WalletProperties.Chain config = wallets.chains().get(chainCode);
        if (config == null || config.xpub() == null || config.xpub().isBlank()) {
            throw new ApiException(503, "chain_unavailable", "Deposits on " + chainCode + " are not configured yet");
        }

        // Serialises allocation per chain and guarantees each index is used once.
        long index = jdbc.sql("""
                        INSERT INTO address_derivation_counters (chain_code, next_index) VALUES (:c, 1)
                        ON CONFLICT (chain_code) DO UPDATE SET next_index = address_derivation_counters.next_index + 1
                        RETURNING next_index - 1
                        """)
                .param("c", chainCode).query(Long.class).single();
        String address = derive(chainCode, family, config, index);

        String id = Ids.next("addr");
        int inserted = jdbc.sql("""
                        INSERT INTO deposit_addresses (id, merchant_id, owner_type, owner_id, chain_code, address, derivation_index)
                        VALUES (:id, :merchantId, :ownerType, :ownerId, :chain, :address, :index)
                        ON CONFLICT (owner_type, owner_id, chain_code) DO NOTHING
                        """)
                .param("id", id)
                .param("merchantId", merchantId)
                .param("ownerType", ownerType.name())
                .param("ownerId", ownerId)
                .param("chain", chainCode)
                .param("address", address)
                .param("index", index)
                .update();
        if (inserted == 1) {
            audit.record(actor, "deposit_address.created", "deposit_address", id, merchantId,
                    Map.of("chain", chainCode, "address", address, "owner_type", ownerType.name()));
        }
        // A concurrent request may have won; the index it skipped is simply never used.
        return find(ownerType, ownerId, chainCode).orElseThrow();
    }

    public Optional<DepositAddress> find(OwnerType ownerType, String ownerId, String chainCode) {
        return jdbc.sql("""
                        SELECT * FROM deposit_addresses WHERE owner_type = :t AND owner_id = :o AND chain_code = :c
                        """)
                .param("t", ownerType.name()).param("o", ownerId).param("c", chainCode)
                .query(MAPPER).optional();
    }

    public Optional<DepositAddress> findByAddress(String chainCode, String address) {
        return jdbc.sql("SELECT * FROM deposit_addresses WHERE chain_code = :c AND address = :a")
                .param("c", chainCode).param("a", address).query(MAPPER).optional();
    }

    /** The subset of {@code candidates} that are our deposit addresses on this chain (one query per chunk). */
    public Set<String> ownedAmong(String chainCode, Collection<String> candidates) {
        Set<String> owned = new HashSet<>();
        List<String> all = List.copyOf(candidates);
        for (int from = 0; from < all.size(); from += 5_000) {
            owned.addAll(jdbc.sql("SELECT address FROM deposit_addresses WHERE chain_code = :c AND address IN (:a)")
                    .param("c", chainCode)
                    .param("a", all.subList(from, Math.min(all.size(), from + 5_000)))
                    .query(String.class).list());
        }
        return owned;
    }

    public List<DepositAddress> listForOwner(OwnerType ownerType, String ownerId) {
        return jdbc.sql("SELECT * FROM deposit_addresses WHERE owner_type = :t AND owner_id = :o ORDER BY chain_code")
                .param("t", ownerType.name()).param("o", ownerId).query(MAPPER).list();
    }

    String derive(String chainCode, String family, WalletProperties.Chain config, long index) {
        if (index > Integer.MAX_VALUE) {
            throw new IllegalStateException("derivation index space exhausted for " + chainCode);
        }
        DeterministicKey account = accountKeys.computeIfAbsent(chainCode, c -> parseXpub(config.xpub()));
        DeterministicKey external = HDKeyDerivation.deriveChildKey(account, ChildNumber.ZERO);
        DeterministicKey child = HDKeyDerivation.deriveChildKey(external, new ChildNumber((int) index, false));
        return AddressCodec.encode(family, child, config.mainnet());
    }

    private static DeterministicKey parseXpub(String xpub) {
        Network network = xpub.startsWith("xpub") || xpub.startsWith("zpub") ? BitcoinNetwork.MAINNET : BitcoinNetwork.TESTNET;
        DeterministicKey key = DeterministicKey.deserializeB58(xpub, network);
        if (key.hasPrivKey()) {
            throw new IllegalStateException("A private extended key was configured; only public keys are allowed");
        }
        return key;
    }
}
