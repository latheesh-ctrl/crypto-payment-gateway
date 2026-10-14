package com.cpg.gateway.chain;

import com.cpg.gateway.deposit.DepositService;
import com.cpg.gateway.deposit.ObservedTransfer;
import com.cpg.gateway.wallet.DepositAddressService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Walks one chain block by block and feeds the deposit lifecycle.
 *
 * <ul>
 *   <li>Only one watcher instance scans a chain at a time (a lease on its cursor row), so replicas can run
 *       side by side.</li>
 *   <li>Each block is committed atomically with the cursor: its transfers are recorded, the block's hash is kept,
 *       and the cursor advances, or none of it happens.</li>
 *   <li>Every block must build on the previous one's hash. When it doesn't, the scanner walks back to the last
 *       block the provider still agrees on, reverses open deposits above it and rescans from there. A transfer
 *       mined again on the new branch is picked up again.</li>
 *   <li>Confirmations are counted against the last scanned block, never the provider's head, so only blocks
 *       whose ancestry was checked count.</li>
 * </ul>
 */
@Component
public class ChainScanner {

    private static final Logger log = LoggerFactory.getLogger(ChainScanner.class);

    /** Scanned block hashes kept per chain; also the deepest reorg handled automatically. */
    static final int KEPT_BLOCKS = 1_000;
    private static final String LEASE = "2 minutes";

    private final String instanceId = UUID.randomUUID().toString();
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ChainClients clients;
    private final DepositService deposits;
    private final DepositAddressService addresses;

    public ChainScanner(JdbcClient jdbc, TransactionTemplate tx, ChainClients clients, DepositService deposits,
                        DepositAddressService addresses) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clients = clients;
        this.deposits = deposits;
        this.addresses = addresses;
    }

    /**
     * @param lastBlock  last scanned block after this poll
     * @param scanned    blocks committed in this poll
     * @param reorgFrom  fork point when a reorganisation was handled, else null
     */
    public record PollResult(long head, long lastBlock, int scanned, Long reorgFrom) {
    }

    private record Cursor(Long lastBlock, String lastHash) {
    }

    /** One scan pass. Returns empty when another instance holds this chain's lease. */
    public Optional<PollResult> poll(String chain) {
        Optional<Cursor> leased = acquire(chain);
        if (leased.isEmpty()) {
            return Optional.empty();
        }
        ChainClient client = clients.scanner(chain);
        IndexerProperties.Chain config = clients.config(chain);
        long head = client.head();
        Cursor cursor = leased.get();
        if (cursor.lastBlock() == null) {
            cursor = start(chain, client, config.startBlock() != null ? config.startBlock() : head);
        }

        Set<String> contracts = clients.tokenContracts(chain);
        ChainClient.AddressFilter owned = candidates -> addresses.ownedAmong(chain, candidates);
        long to = Math.min(head, cursor.lastBlock() + Math.max(1, config.maxBlocksPerPoll()));
        int scanned = 0;
        Long reorgFrom = null;
        for (long n = cursor.lastBlock() + 1; n <= to; n++) {
            ChainClient.ChainBlock block = client.block(n, contracts, owned);
            if (!block.header().parentHash().equalsIgnoreCase(cursor.lastHash())) {
                reorgFrom = rewind(chain, client, cursor.lastBlock());
                cursor = new Cursor(reorgFrom, storedHash(chain, reorgFrom).orElseThrow());
                break;
            }
            cursor = commit(chain, block);
            scanned++;
        }
        updateConfirmations(chain, cursor.lastBlock());
        return Optional.of(new PollResult(head, cursor.lastBlock(), scanned, reorgFrom));
    }

    // ---- Lease and cursor ------------------------------------------------------------------------------------

    private Optional<Cursor> acquire(String chain) {
        jdbc.sql("INSERT INTO chain_scan_cursors (chain_code) VALUES (:c) ON CONFLICT DO NOTHING")
                .param("c", chain).update();
        return jdbc.sql("""
                        UPDATE chain_scan_cursors
                        SET lease_owner = :me, lease_until = now() + CAST(:lease AS interval), updated_at = now()
                        WHERE chain_code = :c AND (lease_owner IS NULL OR lease_owner = :me OR lease_until < now())
                        RETURNING last_block, last_hash
                        """)
                .param("me", instanceId).param("lease", LEASE).param("c", chain)
                .query((rs, i) -> new Cursor((Long) rs.getObject(1), rs.getString(2)))
                .optional();
    }

    /** First scan of a chain: anchor on the block before {@code first} so the first block's parent is checked. */
    private Cursor start(String chain, ChainClient client, long first) {
        if (first < 1) {
            throw new IllegalStateException(chain + " scanning must start after the genesis block");
        }
        ChainClient.BlockHeader anchor = client.header(first - 1);
        log.info("Starting {} scan at block {}", chain, first);
        return tx.execute(s -> {
            jdbc.sql("""
                            INSERT INTO chain_blocks (chain_code, block_number, block_hash, parent_hash)
                            VALUES (:c, :n, :h, :p)
                            """)
                    .param("c", chain).param("n", anchor.number()).param("h", anchor.hash())
                    .param("p", anchor.parentHash()).update();
            moveCursor(chain, null, anchor.number(), anchor.hash());
            return new Cursor(anchor.number(), anchor.hash());
        });
    }

    private void moveCursor(String chain, Long expectedLast, long last, String hash) {
        int updated = jdbc.sql("""
                        UPDATE chain_scan_cursors
                        SET last_block = :last, last_hash = :hash, lease_until = now() + CAST(:lease AS interval),
                            updated_at = now()
                        WHERE chain_code = :c AND lease_owner = :me
                          AND last_block IS NOT DISTINCT FROM CAST(:expected AS bigint)
                        """)
                .param("last", last).param("hash", hash).param("lease", LEASE).param("c", chain)
                .param("me", instanceId).param("expected", expectedLast).update();
        if (updated != 1) {
            throw new IllegalStateException("lost the " + chain + " scan lease; another watcher took over");
        }
    }

    // ---- Blocks ----------------------------------------------------------------------------------------------

    private Cursor commit(String chain, ChainClient.ChainBlock block) {
        ChainClient.BlockHeader h = block.header();
        return tx.execute(s -> {
            for (ObservedTransfer t : block.transfers()) {
                deposits.onTransferObserved(t);
            }
            jdbc.sql("""
                            INSERT INTO chain_blocks (chain_code, block_number, block_hash, parent_hash, transfers)
                            VALUES (:c, :n, :h, :p, :t)
                            """)
                    .param("c", chain).param("n", h.number()).param("h", h.hash()).param("p", h.parentHash())
                    .param("t", block.transfers().size()).update();
            moveCursor(chain, h.number() - 1, h.number(), h.hash());
            if (h.number() % 100 == 0) {
                jdbc.sql("DELETE FROM chain_blocks WHERE chain_code = :c AND block_number < :keep")
                        .param("c", chain).param("keep", h.number() - KEPT_BLOCKS).update();
            }
            if (!block.transfers().isEmpty()) {
                log.info("{} block {}: {} transfer(s) to our addresses", chain, h.number(), block.transfers().size());
            }
            return new Cursor(h.number(), h.hash());
        });
    }

    /**
     * Finds the newest kept block the provider still has, drops everything above it and reverses open deposits in
     * the dropped blocks. Returns the fork point.
     */
    private long rewind(String chain, ChainClient client, long last) {
        long fork = last;
        while (true) {
            Optional<String> kept = storedHash(chain, fork);
            if (kept.isEmpty()) {
                log.error("{} reorganised deeper than the {} kept blocks; scanning stopped for operator review",
                        chain, KEPT_BLOCKS);
                throw new IllegalStateException(chain + " reorg below block " + fork + " needs operator review");
            }
            if (client.header(fork).hash().equalsIgnoreCase(kept.get())) {
                break;
            }
            fork--;
        }
        long forkPoint = fork;
        log.warn("{} reorganised: blocks {}..{} replaced", chain, forkPoint + 1, last);
        tx.executeWithoutResult(s -> {
            record Open(String txHash, int outputIndex, long block) {
            }
            List<Open> dropped = jdbc.sql("""
                            SELECT tx_hash, output_index, block_number FROM deposits
                            WHERE chain_code = :c AND block_number > :fork AND NOT simulated
                              AND status IN ('detected', 'confirming', 'completed')
                            """)
                    .param("c", chain).param("fork", forkPoint)
                    .query((rs, i) -> new Open(rs.getString(1), rs.getInt(2), rs.getLong(3))).list();
            for (Open o : dropped) {
                deposits.onReorged(chain, o.txHash(), o.outputIndex(),
                        "block " + o.block() + " was replaced in a chain reorganisation");
            }
            jdbc.sql("DELETE FROM chain_blocks WHERE chain_code = :c AND block_number > :fork")
                    .param("c", chain).param("fork", forkPoint).update();
            moveCursor(chain, last, forkPoint, storedHash(chain, forkPoint).orElseThrow());
        });
        return forkPoint;
    }

    private Optional<String> storedHash(String chain, long number) {
        return jdbc.sql("SELECT block_hash FROM chain_blocks WHERE chain_code = :c AND block_number = :n")
                .param("c", chain).param("n", number).query(String.class).optional();
    }

    // ---- Confirmations ---------------------------------------------------------------------------------------

    private void updateConfirmations(String chain, long lastScanned) {
        record Open(String txHash, int outputIndex, long block, String hash, int confirmations) {
        }
        List<Open> open = jdbc.sql("""
                        SELECT d.tx_hash, d.output_index, d.block_number, b.block_hash, d.confirmations
                        FROM deposits d
                        JOIN chain_blocks b ON b.chain_code = d.chain_code AND b.block_number = d.block_number
                                           AND lower(b.block_hash) = lower(d.block_hash)
                        WHERE d.chain_code = :c AND d.status IN ('detected', 'confirming') AND NOT d.simulated
                          AND d.block_number <= :last
                        """)
                .param("c", chain).param("last", lastScanned)
                .query((rs, i) -> new Open(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getString(4), rs.getInt(5)))
                .list();
        for (Open o : open) {
            int confirmations = (int) Math.min(Integer.MAX_VALUE, lastScanned - o.block() + 1);
            if (confirmations > o.confirmations()) {
                try {
                    deposits.onConfirmations(chain, o.txHash(), o.outputIndex(), o.block(), o.hash(), confirmations);
                } catch (RuntimeException e) {
                    log.warn("Confirmation update failed for {} {}:{}", chain, o.txHash(), o.outputIndex(), e);
                }
            }
        }
    }
}
