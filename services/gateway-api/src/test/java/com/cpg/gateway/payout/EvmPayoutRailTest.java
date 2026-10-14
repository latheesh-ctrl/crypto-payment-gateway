package com.cpg.gateway.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cpg.gateway.chain.FakeRpcServer;
import com.cpg.gateway.chain.RpcTransport;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The EVM rail against a local JSON-RPC stub: gas and balance checks, broadcast outcomes, receipt parsing. */
class EvmPayoutRailTest {

    private static final String KEY = "0x" + "46".repeat(32);
    private static final String USDC = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";
    private static final String PLAYER = "0x" + "ab".repeat(20);

    FakeRpcServer server;
    EvmPayoutRail rail;
    final AtomicReference<String> gasPrice = new AtomicReference<>("0x3b9aca00"); // 1 gwei
    final AtomicReference<String> balance = new AtomicReference<>("0xde0b6b3a7640000"); // 1 ETH
    final AtomicReference<String> tokenBalance = new AtomicReference<>("0x" + String.format("%064x", 50_000_000));
    final AtomicReference<RuntimeException> sendError = new AtomicReference<>();
    final List<String> sent = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeRpcServer()
                .method("eth_gasPrice", p -> gasPrice.get())
                .method("eth_getBalance", p -> balance.get())
                .method("eth_call", p -> {
                    assertThat(p.get(0).get("to").asString()).isEqualTo(USDC);
                    assertThat(p.get(0).get("data").asString()).startsWith("0x70a08231");
                    return tokenBalance.get();
                })
                .method("eth_getTransactionCount", p -> "latest".equals(p.get(1).asString()) ? "0x5" : "0x7")
                .method("eth_blockNumber", p -> "0x3e8")
                .method("eth_sendRawTransaction", p -> {
                    if (sendError.get() != null) {
                        throw sendError.get();
                    }
                    sent.add(p.get(0).asString());
                    return "0xhash";
                })
                .method("eth_getTransactionReceipt", p -> switch (p.get(0).asString()) {
                    case "0xok" -> Map.of("status", "0x1", "blockNumber", "0x3e0", "gasUsed", "0x5208",
                            "effectiveGasPrice", "0x3b9aca00");
                    case "0xreverted" -> Map.of("status", "0x0", "blockNumber", "0x3e1", "gasUsed", "0x7530");
                    default -> null;
                })
                .method("eth_getTransactionByHash", p -> switch (p.get(0).asString()) {
                    case "0xpending" -> Map.of("hash", "0xpending");
                    case "0xreverted" -> Map.of("hash", "0xreverted", "gasPrice", "0x2");
                    default -> null;
                });
        PayoutProperties.Chain config = new PayoutProperties.Chain(true, server.url(),
                PayoutProperties.SignerType.dev_key, KEY, BigInteger.valueOf(2_000_000_000L), 125, 100_000);
        rail = new EvmPayoutRail("ETHEREUM", 1L, new RpcTransport(server.url(), Map.of()),
                RpcPayoutRailFactory.signer("ETHEREUM", config), config);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void readsNoncesAndHead() {
        assertThat(rail.fromAddress()).isEqualTo("0x9d8A62f656a8d1615C1294fd71e9CFb3E4855A4F");
        assertThat(rail.pendingNonce()).isEqualTo(7);
        assertThat(rail.minedNonce()).isEqualTo(5);
        assertThat(rail.head()).isEqualTo(1_000);
    }

    @Test
    void signsNativeAndTokenTransfersAtTheBidGasPrice() {
        PayoutRail.Signed eth = rail.sign(new PayoutRail.Transfer(PLAYER, null, BigInteger.TEN.pow(17)), 7);
        // gasPrice 1.25 gwei = 0x4a817c80, gas 21000 = 0x5208
        assertThat(eth.rawTx()).startsWith("0xf86b07844a817c80825208");
        PayoutRail.Signed usdc = rail.sign(new PayoutRail.Transfer(PLAYER, USDC, BigInteger.valueOf(25_000_000)), 8);
        assertThat(usdc.rawTx()).contains("a9059cbb").contains(USDC.substring(2).toLowerCase());
        assertThat(usdc.txHash()).matches("0x[0-9a-f]{64}");
    }

    @Test
    void gasAboveTheCapIsClampedOrRefused() {
        gasPrice.set("0x77359400"); // 2 gwei: bid 2.5 gwei is clamped to the 2 gwei cap
        assertThat(rail.sign(new PayoutRail.Transfer(PLAYER, null, BigInteger.ONE), 0).rawTx())
                .startsWith("0xf863" + "80" + "8477359400" + "825208");
        gasPrice.set("0xb2d05e00"); // 3 gwei: above the cap
        assertThatThrownBy(() -> rail.sign(new PayoutRail.Transfer(PLAYER, null, BigInteger.ONE), 0))
                .isInstanceOf(PayoutRail.NotReady.class).hasMessageContaining("above the cap");
    }

    @Test
    void insufficientHotWalletFundsAreNotSigned() {
        tokenBalance.set("0x" + String.format("%064x", 1));
        assertThatThrownBy(() -> rail.sign(new PayoutRail.Transfer(PLAYER, USDC, BigInteger.TWO), 0))
                .isInstanceOf(PayoutRail.NotReady.class).hasMessageContaining("token balance");
        balance.set("0x1");
        assertThatThrownBy(() -> rail.sign(new PayoutRail.Transfer(PLAYER, null, BigInteger.ONE), 0))
                .isInstanceOf(PayoutRail.NotReady.class).hasMessageContaining("cannot cover");
    }

    @Test
    void classifiesBroadcastOutcomes() {
        assertThat(rail.broadcast("0xf8").result()).isEqualTo(PayoutRail.BroadcastResult.ACCEPTED);
        assertThat(sent).containsExactly("0xf8");
        sendError.set(new FakeRpcServer.RpcError("already known"));
        assertThat(rail.broadcast("0xf8").result()).isEqualTo(PayoutRail.BroadcastResult.ALREADY_KNOWN);
        sendError.set(new FakeRpcServer.RpcError("nonce too low: next nonce 9, tx nonce 7"));
        assertThat(rail.broadcast("0xf8").result()).isEqualTo(PayoutRail.BroadcastResult.NONCE_USED);
        sendError.set(new FakeRpcServer.RpcError("insufficient funds for gas * price + value"));
        PayoutRail.Broadcast rejected = rail.broadcast("0xf8");
        assertThat(rejected.result()).isEqualTo(PayoutRail.BroadcastResult.REJECTED);
        assertThat(rejected.error()).contains("insufficient funds");
    }

    @Test
    void readsTransactionStatusAndNetworkFee() {
        PayoutRail.TxStatus ok = rail.status("0xok");
        assertThat(ok.state()).isEqualTo(PayoutRail.State.SUCCEEDED);
        assertThat(ok.blockNumber()).isEqualTo(992);
        assertThat(ok.networkFee()).isEqualTo(BigInteger.valueOf(21_000L * 1_000_000_000L));
        PayoutRail.TxStatus reverted = rail.status("0xreverted");
        assertThat(reverted.state()).isEqualTo(PayoutRail.State.REVERTED);
        assertThat(reverted.networkFee()).isEqualTo(BigInteger.valueOf(30_000L * 2));
        assertThat(rail.status("0xpending").state()).isEqualTo(PayoutRail.State.PENDING);
        assertThat(rail.status("0xunknown").state()).isEqualTo(PayoutRail.State.NOT_FOUND);
    }
}
