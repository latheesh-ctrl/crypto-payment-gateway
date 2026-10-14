package com.cpg.gateway.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cpg.gateway.deposit.ObservedTransfer;
import com.cpg.gateway.wallet.AddressCodec;
import java.math.BigInteger;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bitcoinj.base.Base58;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TronChainClientTest {

    static final String USDT = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";
    static final String USDT_HEX = "a614f803b6fd780986a42c78ec9c7f77e6ded13c";
    static final String OURS = AddressCodec.tronFromHex("41" + "12".repeat(20));
    static final String OTHER = AddressCodec.tronFromHex("34".repeat(20));
    static final String BLOCK_ID = "0000000003e8" + "ab".repeat(26);

    FakeRpcServer server;
    TronChainClient client;
    final ChainClient.AddressFilter owned = candidates -> {
        Set<String> out = new HashSet<>(candidates);
        out.retainAll(Set.of(OURS));
        return out;
    };

    static String hex(String base58) {
        return HexFormat.of().formatHex(Base58.decodeChecked(base58));
    }

    static Map<String, Object> transfer(String txId, String to, long amount, String result) {
        return Map.of("txID", txId, "ret", List.of(Map.of("contractRet", result)),
                "raw_data", Map.of("contract", List.of(Map.of("type", "TransferContract",
                        "parameter", Map.of("value", Map.of("to_address", hex(to), "amount", amount, "owner_address", hex(OTHER)))))));
    }

    static Map<String, Object> trc20Info(String txId, String to, long amount, String result) {
        return Map.of("id", txId, "blockNumber", 1000, "receipt", Map.of("result", result),
                "log", List.of(
                        Map.of("address", "11".repeat(20), "topics", List.of(Hex.TRANSFER_TOPIC, "0".repeat(64), "0".repeat(64)),
                                "data", "0".repeat(64)),
                        Map.of("address", USDT_HEX, "topics", List.of(Hex.TRANSFER_TOPIC,
                                        "0".repeat(24) + hex(OTHER).substring(2), "0".repeat(24) + hex(to).substring(2)),
                                "data", String.format("%064x", amount))));
    }

    @BeforeEach
    void setUp() throws Exception {
        Map<String, Object> header = Map.of("blockID", BLOCK_ID,
                "block_header", Map.of("raw_data", Map.of("number", 1000, "parentHash", "00".repeat(32))));
        server = new FakeRpcServer()
                .path("/wallet/getblock", body -> header)
                .path("/wallet/getblockbynum", body -> Map.of("blockID", BLOCK_ID,
                        "block_header", Map.of("raw_data", Map.of("number", body.get("num").asLong(), "parentHash", "00".repeat(32))),
                        "transactions", List.of(
                                transfer("trx-ok", OURS, 5_000_000, "SUCCESS"),
                                transfer("trx-failed", OURS, 1, "REVERT"),
                                transfer("trx-other", OTHER, 7, "SUCCESS"),
                                Map.of("txID", "usdt-ok", "ret", List.of(Map.of("contractRet", "SUCCESS")),
                                        "raw_data", Map.of("contract", List.of(Map.of("type", "TriggerSmartContract")))),
                                Map.of("txID", "usdt-failed", "ret", List.of(Map.of("contractRet", "REVERT")),
                                        "raw_data", Map.of("contract", List.of(Map.of("type", "TriggerSmartContract")))))))
                .path("/wallet/gettransactioninfobyblocknum", body -> List.of(
                        trc20Info("usdt-ok", OURS, 12_000_000, "SUCCESS"),
                        trc20Info("usdt-failed", OURS, 99, "REVERT")))
                .path("/wallet/gettransactioninfobyid", body -> body.get("value").asString().equals("unknown")
                        ? Map.of() : trc20Info(body.get("value").asString(), OURS, 12_000_000, "SUCCESS"))
                .path("/wallet/gettransactionbyid", body -> Map.of("txID", body.get("value").asString(),
                        "ret", List.of(Map.of("contractRet", "SUCCESS")),
                        "raw_data", Map.of("contract", List.of(Map.of("type", "TriggerSmartContract")))));
        client = new TronChainClient("TRON", new RpcTransport(server.url(), Map.of("TRON-PRO-API-KEY", "k")));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void blockYieldsSuccessfulTrxAndTrc20TransfersToOurAddresses() {
        ChainClient.ChainBlock block = client.block(1000, Set.of(USDT), owned);

        assertThat(block.header()).isEqualTo(new ChainClient.BlockHeader(1000, BLOCK_ID, "00".repeat(32)));
        assertThat(block.transfers()).containsExactlyInAnyOrder(
                new ObservedTransfer("TRON", "trx-ok", 0, OURS, null, BigInteger.valueOf(5_000_000), 1000L, BLOCK_ID),
                new ObservedTransfer("TRON", "usdt-ok", 1, OURS, USDT, BigInteger.valueOf(12_000_000), 1000L, BLOCK_ID));
        assertThat(server.requestHeaders.getFirst().get("Tron-pro-api-key")).containsExactly("k");
    }

    @Test
    void transactionLookupForVerification() {
        assertThat(client.transfers("usdt-ok", null, Set.of(USDT))).containsExactly(
                new ObservedTransfer("TRON", "usdt-ok", 1, OURS, USDT, BigInteger.valueOf(12_000_000), 1000L, BLOCK_ID));
        assertThat(client.transfers("unknown", null, Set.of(USDT))).isEmpty();
    }

    @Test
    void blockThatChangesBetweenCallsIsRetried() {
        server.path("/wallet/gettransactioninfobyblocknum", body -> List.of(trc20Info("not-in-block", OURS, 1, "SUCCESS")));
        assertThatThrownBy(() -> client.block(1000, Set.of(USDT), owned)).isInstanceOf(ChainRpcException.class);
    }

    @Test
    void contractHexMatchesTheBase58Address() {
        assertThat(AddressCodec.tronFromHex(USDT_HEX)).isEqualTo(USDT);
    }

    @Test
    void headerNumberIsChecked() {
        assertThat(client.head()).isEqualTo(1000);
        assertThatThrownBy(() -> client.header(999)).isInstanceOf(ChainRpcException.class);
    }
}
