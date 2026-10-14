package com.cpg.gateway.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cpg.gateway.deposit.ObservedTransfer;
import java.math.BigInteger;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EvmChainClientTest {

    static final String USDC = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";
    static final String OURS = "0x2c7536E3605D9C16a7a3D7b1898e529396a65c23";
    static final String OTHER = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed";
    static final String TOPIC = "0x" + Hex.TRANSFER_TOPIC;
    static final String BLOCK_HASH = "0x" + "ab".repeat(32);

    FakeRpcServer server;
    EvmChainClient client;
    List<Collection<String>> filterCalls = new java.util.ArrayList<>();
    final ChainClient.AddressFilter owned = candidates -> {
        filterCalls.add(Set.copyOf(candidates));
        Set<String> out = new HashSet<>(candidates);
        out.retainAll(Set.of(OURS));
        return out;
    };

    static String word(String address) {
        return "0x" + "0".repeat(24) + address.substring(2).toLowerCase();
    }

    static Map<String, Object> log(String contract, int index, String to, long amount, int topics) {
        List<String> t = topics == 3
                ? List.of(TOPIC, word(OTHER), word(to))
                : List.of(TOPIC, word(OTHER), word(to), "0x" + "0".repeat(63) + "1");
        Map<String, Object> log = new HashMap<>();
        log.put("address", contract.toLowerCase());
        log.put("topics", t);
        log.put("data", topics == 3 ? "0x" + String.format("%064x", amount) : "0x");
        log.put("logIndex", "0x" + Integer.toHexString(index));
        log.put("transactionHash", "0xtoken" + index);
        log.put("blockHash", BLOCK_HASH);
        log.put("removed", false);
        return log;
    }

    @BeforeEach
    void setUp() throws Exception {
        Map<String, Object> receiptOk = Map.of("status", "0x1", "blockHash", BLOCK_HASH, "blockNumber", "0x64",
                "logs", List.of(log(USDC, 7, OURS, 2_500_000, 3)));
        Map<String, Object> receiptFailed = Map.of("status", "0x0", "blockHash", BLOCK_HASH, "blockNumber", "0x64",
                "logs", List.of());
        server = new FakeRpcServer()
                .method("eth_blockNumber", p -> "0x6a")
                .method("eth_getBlockByNumber", p -> Map.of(
                        "number", p.get(0).asString(), "hash", BLOCK_HASH, "parentHash", "0x" + "cd".repeat(32),
                        "transactions", List.of(
                                Map.of("hash", "0xnative-ok", "to", OURS.toLowerCase(), "value", "0xde0b6b3a7640000"),
                                Map.of("hash", "0xnative-failed", "to", OURS, "value", "0x1"),
                                Map.of("hash", "0xnative-other", "to", OTHER, "value", "0x5"),
                                Map.of("hash", "0xcall", "to", USDC, "value", "0x0"))))
                .method("eth_getLogs", p -> {
                    assertThat(p.get(0).get("blockHash").asString()).isEqualTo(BLOCK_HASH);
                    return List.of(log(USDC, 3, OURS, 2_500_000, 3), log(USDC, 4, OTHER, 9, 3),
                            log(USDC, 5, OURS, 0, 4), log("0x" + "11".repeat(20), 6, OURS, 1, 3));
                })
                .method("eth_getTransactionReceipt", p -> switch (p.get(0).asString()) {
                    case "0xnative-failed" -> receiptFailed;
                    case "0xmissing" -> null;
                    default -> receiptOk;
                })
                .method("eth_getTransactionByHash", p -> Map.of("hash", p.get(0).asString(), "to", USDC, "value", "0x0"));
        client = new EvmChainClient("ETHEREUM", new RpcTransport(server.url(), Map.of()));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void blockYieldsSuccessfulNativeAndErc20TransfersToOurAddressesOnly() {
        ChainClient.ChainBlock block = client.block(100, Set.of(USDC), owned);

        assertThat(block.header().number()).isEqualTo(100);
        assertThat(block.header().hash()).isEqualTo(BLOCK_HASH);
        assertThat(block.transfers()).containsExactlyInAnyOrder(
                new ObservedTransfer("ETHEREUM", "0xnative-ok", 0, OURS, null,
                        new BigInteger("1000000000000000000"), 100L, BLOCK_HASH),
                new ObservedTransfer("ETHEREUM", "0xtoken3", 3, OURS, USDC, BigInteger.valueOf(2_500_000), 100L,
                        BLOCK_HASH));
        // One batched ownership lookup per block, with checksummed addresses.
        assertThat(filterCalls).hasSize(1);
        assertThat(filterCalls.getFirst()).contains(OURS, OTHER);
    }

    @Test
    void headAndHeader() {
        assertThat(client.head()).isEqualTo(106);
        assertThat(client.header(5).parentHash()).isEqualTo("0x" + "cd".repeat(32));
    }

    @Test
    void transactionLookupForVerification() {
        assertThat(client.transfers("0xany", null, Set.of(USDC))).containsExactly(
                new ObservedTransfer("ETHEREUM", "0xany", 7, OURS, USDC, BigInteger.valueOf(2_500_000), 100L, BLOCK_HASH));
        assertThat(client.transfers("0xmissing", null, Set.of(USDC))).isEmpty();
        assertThat(client.transfers("0xnative-failed", null, Set.of(USDC))).isEmpty();
    }

    @Test
    void unknownMethodIsAnRpcError() {
        server.method("eth_getLogs", p -> List.of(Map.of("address", USDC, "topics", List.of(TOPIC, word(OTHER), word(OURS)),
                "data", "0x" + "0".repeat(63) + "1", "logIndex", "0x1", "transactionHash", "0xt",
                "blockHash", "0x" + "ee".repeat(32))));
        assertThatThrownBy(() -> client.block(100, Set.of(USDC), owned))
                .isInstanceOf(ChainRpcException.class).hasMessageContaining("expected");
    }

    @Test
    void rpcErrorsSurfaceAsRetryableExceptions() {
        assertThatThrownBy(() -> new EvmChainClient("ETHEREUM", new RpcTransport("http://127.0.0.1:1", Map.of())).head())
                .isInstanceOf(ChainRpcException.class);
        assertThatThrownBy(() -> client.block(100, Set.of(USDC), c -> {
            throw new ChainRpcException("lookup unavailable");
        })).isInstanceOf(ChainRpcException.class);
    }
}
