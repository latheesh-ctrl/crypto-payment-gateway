package com.cpg.gateway.chain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cpg.gateway.deposit.ObservedTransfer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BitcoinChainClientTest {

    static final String OURS = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";
    static final String OTHER = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq";
    static final String HASH = "00000000000000000002" + "ab".repeat(22);

    FakeRpcServer server;
    BitcoinChainClient client;
    final ChainClient.AddressFilter owned = candidates -> {
        Set<String> out = new HashSet<>(candidates);
        out.retainAll(Set.of(OURS));
        return out;
    };

    static Map<String, Object> vout(int n, String value, String address) {
        return Map.of("n", n, "value", new BigDecimal(value),
                "scriptPubKey", address == null ? Map.of("type", "nulldata") : Map.of("address", address));
    }

    @BeforeEach
    void setUp() throws Exception {
        Map<String, Object> payment = Map.of("txid", "pay", "vin", List.of(Map.of("txid", "prev", "vout", 0)),
                "vout", List.of(vout(0, "0.00123456", OURS), vout(1, "0", null), vout(2, "1.5", OTHER),
                        vout(3, "21000000.00000001", OURS)));
        Map<String, Object> coinbase = Map.of("txid", "cb", "vin", List.of(Map.of("coinbase", "03")),
                "vout", List.of(vout(0, "3.125", OURS)));
        server = new FakeRpcServer()
                .method("getblockcount", p -> 840_005)
                .method("getblockhash", p -> HASH)
                .method("getblockheader", p -> Map.of("hash", HASH, "height", 840_000,
                        "previousblockhash", "prev-hash", "confirmations", 6))
                .method("getblock", p -> Map.of("hash", HASH, "height", 840_000, "previousblockhash", "prev-hash",
                        "tx", List.of(coinbase, payment)))
                .method("getrawtransaction", p -> payment);
        client = new BitcoinChainClient("BITCOIN", new RpcTransport(
                server.url().replace("http://", "http://rpcuser:p%40ss@"), Map.of()));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void blockYieldsOurOutputsInSatoshisAndSkipsCoinbase() {
        ChainClient.ChainBlock block = client.block(840_000, Set.of(), owned);

        assertThat(block.header()).isEqualTo(new ChainClient.BlockHeader(840_000, HASH, "prev-hash"));
        assertThat(block.transfers()).containsExactly(
                new ObservedTransfer("BITCOIN", "pay", 0, OURS, null, BigInteger.valueOf(123_456), 840_000L, HASH),
                new ObservedTransfer("BITCOIN", "pay", 3, OURS, null, new BigInteger("2100000000000001"), 840_000L, HASH));
        // Credentials from the URL go into a Basic auth header.
        assertThat(server.requestHeaders.getFirst().get("Authorization")).containsExactly(
                "Basic " + Base64.getEncoder().encodeToString("rpcuser:p@ss".getBytes()));
    }

    @Test
    void transactionLookupNeedsACanonicalBlock() {
        assertThat(client.transfers("pay", HASH, Set.of())).hasSize(3);
        assertThat(client.transfers("pay", null, Set.of())).isEmpty();
        server.method("getblockheader", p -> Map.of("hash", HASH, "height", 840_000, "confirmations", -1));
        assertThat(client.transfers("pay", HASH, Set.of())).isEmpty();
    }

    @Test
    void headAndHeader() {
        assertThat(client.head()).isEqualTo(840_005);
        assertThat(client.header(840_000).hash()).isEqualTo(HASH);
    }
}
