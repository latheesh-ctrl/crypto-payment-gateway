package com.cpg.gateway.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cpg.gateway.IntegrationTest;
import com.cpg.gateway.TestMerchants;
import com.cpg.gateway.audit.AuditLog;
import com.cpg.gateway.ledger.AccountKind;
import com.cpg.gateway.ledger.AccountRef;
import com.cpg.gateway.ledger.LedgerService;
import com.cpg.gateway.ledger.Posting;
import com.cpg.gateway.wallet.AddressCodec;
import com.cpg.gateway.wallet.DepositAddressService;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Cashouts from merchant liquidity, end to end against an in-memory chain with real transaction signing. */
@IntegrationTest
@Import(FakePayoutRail.Config.class)
@TestPropertySource(properties = {
        "gateway.payouts.chains.ETHEREUM.enabled=true",
        "gateway.payouts.chains.ETHEREUM.rpc-url=fake://payout",
        "gateway.payouts.stuck-after=PT1H",
        "gateway.payouts.rebroadcast-every=PT0S"})
class PayoutIT {

    private static final String USDC = "USDC_ETHEREUM";
    private static final String USDC_CONTRACT = "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48";
    private static final String PLAYER_WALLET = "0x" + "ab".repeat(20);
    private static final BigInteger GAS_FEE = BigInteger.valueOf(21_000_000_000_000L);

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired LedgerService ledger;
    @Autowired PayoutService payouts;
    @Autowired PayoutProcessor processor;
    @Autowired DepositAddressService addresses;
    @Autowired FakePayoutRail.Registry rails;

    private FakePayoutRail rail;
    private TestMerchants.Onboarded merchant;

    @BeforeEach
    void setUp() throws Exception {
        rail = rails.get("ETHEREUM");
        rail.reset();
        merchant = gamingMerchant();
    }

    /** A merchant that keeps its players' balances, with a 1% payout fee and a key allowed to send payouts. */
    private TestMerchants.Onboarded gamingMerchant() throws Exception {
        TestMerchants.Onboarded m = TestMerchants.onboard(mvc, json);
        mvc.perform(patch("/internal/v1/merchants/" + m.merchantId() + "/settings")
                        .header("X-Admin-Token", TestMerchants.ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"player_balances\":\"merchant_managed\",\"payout_fee_bps\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payout_fee_bps").value(100));
        JsonNode key = TestMerchants.adminPost(mvc, json, "/internal/v1/merchants/" + m.merchantId() + "/api-keys",
                "{\"name\":\"cashier\",\"scopes\":[\"read\",\"payouts:write\"]}");
        return new TestMerchants.Onboarded(m.merchantId(), key.get("secret").asString(),
                key.get("signing_secret").asString(), key.get("id").asString());
    }

    private void fund(String merchantId, long units) {
        ledger.post(new Posting("test_funding", "test:fund:" + UUID.randomUUID(), "test liquidity", List.of(
                Posting.Entry.debit(AccountRef.platform(AccountKind.PLATFORM_DEPOSIT_WALLETS, USDC), BigInteger.valueOf(units)),
                Posting.Entry.credit(AccountRef.merchant(AccountKind.MERCHANT_LIQUIDITY, merchantId, USDC),
                        BigInteger.valueOf(units)))));
    }

    private long balance(AccountKind kind, String merchantId) {
        return ledger.balance(AccountRef.merchant(kind, merchantId, USDC)).longValueExact();
    }

    private static String body(String payoutId, String amount, String destination) {
        return """
                {"merchant_payout_id":"%s","player_ref":"gk_player_8812","network":"ethereum","asset":"USDC",
                 "amount":"%s","destination_address":"%s","metadata":{"withdrawal":"W-1"}}
                """.formatted(payoutId, amount, destination);
    }

    private JsonNode create(String payoutId, String amount) throws Exception {
        return json.readTree(mvc.perform(merchant.post("/v1/payouts", body(payoutId, amount, PLAYER_WALLET)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private static String newId() {
        return "GK-WD-" + UUID.randomUUID();
    }

    private Payout payout(String id) {
        return payouts.require(id);
    }

    private List<String> events(String id) {
        return jdbc.sql("SELECT event_type FROM outbox_events WHERE aggregate_id = :id ORDER BY created_at, id")
                .param("id", id).query(String.class).list();
    }

    private PayoutService.Outbound outbound(String id) {
        return payouts.outbound(id).orElseThrow();
    }

    @Test
    void cashoutIsReservedSignedOnceBroadcastConfirmedAndSettled() throws Exception {
        fund(merchant.merchantId(), 100_000_000);
        rail.pendingNonce = 7;
        String mpid = newId();
        JsonNode created = create(mpid, "25");
        String id = created.get("id").asString();
        assertThat(id).startsWith("po_");
        assertThat(created.get("status").asString()).isEqualTo("processing");
        assertThat(created.get("fee").asString()).isEqualTo("0.25");
        assertThat(created.get("total_debited").asString()).isEqualTo("25.25");
        assertThat(created.get("destination_address").asString()).isEqualTo(AddressCodec.normalizeEvm(PLAYER_WALLET));
        assertThat(balance(AccountKind.MERCHANT_LIQUIDITY, merchant.merchantId())).isEqualTo(74_750_000);
        assertThat(balance(AccountKind.MERCHANT_LIQUIDITY_ALLOCATED, merchant.merchantId())).isEqualTo(25_250_000);

        // The same request again is the same payout; the same ID with other details is refused.
        mvc.perform(merchant.post("/v1/payouts", body(mpid, "25.000000", PLAYER_WALLET)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(merchant.post("/v1/payouts", body(mpid, "26", PLAYER_WALLET)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("payout_conflict"));
        assertThat(balance(AccountKind.MERCHANT_LIQUIDITY_ALLOCATED, merchant.merchantId())).isEqualTo(25_250_000);

        processor.process(id);
        Payout sent = payout(id);
        assertThat(sent.status()).isEqualTo("broadcast");
        assertThat(rail.signedNonces).containsExactly(7L);
        PayoutService.Outbound tx = outbound(id);
        assertThat(tx.nonce()).isEqualTo(7);
        assertThat(rail.broadcasts).containsExactly(tx.rawTx());
        assertThat(sent.txHash()).isEqualTo(tx.txHash());
        // A token transfer call to the USDC contract paying the player exactly 25 USDC.
        assertThat(tx.rawTx()).contains(USDC_CONTRACT.substring(2))
                .contains("a9059cbb000000000000000000000000" + "ab".repeat(20)
                        + "00000000000000000000000000000000000000000000000000000000017d7840");
        assertThat(tx.txHash()).isEqualTo("0x" + HexFormat.of().formatHex(
                com.cpg.gateway.payout.evm.EvmTransactions.keccak256(HexFormat.of().parseHex(tx.rawTx().substring(2)))));

        processor.process(id);
        assertThat(payout(id).status()).isEqualTo("broadcast");

        rail.mine(tx.txHash(), 1_001, true, GAS_FEE);
        rail.head = 1_005;
        processor.process(id);
        assertThat(payout(id).status()).isEqualTo("confirming");
        assertThat(payout(id).confirmations()).isEqualTo(5);

        rail.head = 1_012;
        processor.process(id);
        Payout done = payout(id);
        assertThat(done.status()).isEqualTo("completed");
        assertThat(done.confirmations()).isEqualTo(12);
        assertThat(done.completedAt()).isNotNull();
        assertThat(rail.signedNonces).hasSize(1);

        assertThat(balance(AccountKind.MERCHANT_LIQUIDITY_ALLOCATED, merchant.merchantId())).isZero();
        assertThat(balance(AccountKind.MERCHANT_LIQUIDITY, merchant.merchantId())).isEqualTo(74_750_000);
        assertThat(ledger.balance(AccountRef.platform(AccountKind.PLATFORM_FEES, USDC))).isGreaterThanOrEqualTo(BigInteger.valueOf(250_000));
        assertThat(ledger.balance(AccountRef.platform(AccountKind.PLATFORM_NETWORK_FEE_EXPENSE, "ETH_ETHEREUM")))
                .isGreaterThanOrEqualTo(GAS_FEE);
        assertThat(events(id)).containsExactly("payout.processing", "payout.broadcast", "payout.confirming",
                "payout.completed");

        mvc.perform(merchant.authorize(get("/v1/payouts/" + id)))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.tx_hash").value(tx.txHash()))
                .andExpect(jsonPath("$.metadata.withdrawal").value("W-1"));
        mvc.perform(merchant.authorize(get("/v1/payouts").param("merchant_payout_id", mpid)))
                .andExpect(jsonPath("$.data[0].id").value(id));
        TestMerchants.Onboarded other = gamingMerchant();
        mvc.perform(other.authorize(get("/v1/payouts/" + id))).andExpect(status().isNotFound());
    }

    @Test
    void payoutWaitsForLiquidityAndCancellingReleasesIt() throws Exception {
        String id = create(newId(), "10").get("id").asString();
        assertThat(payout(id).status()).isEqualTo("awaiting_liquidity");
        assertThat(payout(id).reserved()).isFalse();
        assertThat(payouts.tryReserve(id)).isFalse();
        processor.process(id);
        assertThat(rail.signedNonces).isEmpty();
        assertThat(payout(id).status()).isEqualTo("awaiting_liquidity");

        fund(merchant.merchantId(), 10_100_000);
        assertThat(payouts.tryReserve(id)).isTrue();
        assertThat(payout(id).status()).isEqualTo("processing");
        assertThat(balance(AccountKind.MERCHANT_LIQUIDITY, merchant.merchantId())).isZero();

        mvc.perform(merchant.post("/v1/payouts/" + id + "/cancel", ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("cancelled"));
        assertThat(balance(AccountKind.MERCHANT_LIQUIDITY, merchant.merchantId())).isEqualTo(10_100_000);
        assertThat(balance(AccountKind.MERCHANT_LIQUIDITY_ALLOCATED, merchant.merchantId())).isZero();
        assertThat(events(id)).containsExactly("payout.awaiting_liquidity", "payout.processing", "payout.cancelled");
        processor.process(id);
        assertThat(rail.signedNonces).isEmpty();
    }

    @Test
    void signedPayoutCannotBeCancelled() throws Exception {
        fund(merchant.merchantId(), 5_000_000);
        String id = create(newId(), "1").get("id").asString();
        processor.process(id);
        mvc.perform(merchant.post("/v1/payouts/" + id + "/cancel", ""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("payout_not_cancellable"));
    }

    @Test
    void revertedTransactionFailsThePayoutAndReleasesFunds() throws Exception {
        fund(merchant.merchantId(), 5_000_000);
        String id = create(newId(), "2").get("id").asString();
        processor.process(id);
        rail.mine(outbound(id).txHash(), 1_001, false, GAS_FEE);
        rail.head = 1_003;
        processor.process(id);
        assertThat(payout(id).status()).isEqualTo("confirming");
        rail.head = 1_020;
        processor.process(id);
        Payout failed = payout(id);
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.statusReason()).contains("reverted");
        assertThat(balance(AccountKind.MERCHANT_LIQUIDITY, merchant.merchantId())).isEqualTo(5_000_000);
        assertThat(balance(AccountKind.MERCHANT_LIQUIDITY_ALLOCATED, merchant.merchantId())).isZero();
        assertThat(events(id)).endsWith("payout.failed");
    }

    @Test
    void uncertainBroadcastsResendTheSameBytesAndNeverSignAgain() throws Exception {
        fund(merchant.merchantId(), 5_000_000);
        String id = create(newId(), "3").get("id").asString();

        // Node down after signing: the signed transaction is stored and the payout waits in signing.
        rail.nextBroadcast = new PayoutRail.Broadcast(PayoutRail.BroadcastResult.REJECTED, "connection reset");
        processor.process(id);
        assertThat(payout(id).status()).isEqualTo("signing");
        String raw = outbound(id).rawTx();
        assertThat(rail.signedNonces).hasSize(1);

        rail.down = true;
        processor.process(id);
        assertThat(payout(id).status()).isEqualTo("signing");
        rail.down = false;

        processor.process(id);
        assertThat(payout(id).status()).isEqualTo("broadcast");

        // The node forgets it: the same bytes go out again.
        rail.forget(outbound(id).txHash());
        processor.process(id);
        assertThat(rail.broadcasts).hasSizeGreaterThanOrEqualTo(3).allMatch(raw::equals);
        assertThat(rail.signedNonces).hasSize(1);
        assertThat(outbound(id).broadcastCount()).isEqualTo(2);

        // Already known to the node is a success too.
        rail.forget(outbound(id).txHash());
        rail.nextBroadcast = new PayoutRail.Broadcast(PayoutRail.BroadcastResult.ALREADY_KNOWN, null);
        processor.process(id);
        assertThat(payout(id).status()).isEqualTo("broadcast");
        assertThat(rail.signedNonces).hasSize(1);
    }

    @Test
    void nonceTakenByAnotherTransactionGoesToReviewAndOnlyAProvenFailureReleasesFunds() throws Exception {
        fund(merchant.merchantId(), 5_000_000);
        String id = create(newId(), "4").get("id").asString();
        processor.process(id);
        PayoutService.Outbound tx = outbound(id);

        // While the transaction is pending, an operator cannot fail it.
        rail.minedNonce = tx.nonce() + 1;
        mvc.perform(post("/internal/v1/payouts/" + id + "/resolve_failed").header("X-Admin-Token", TestMerchants.ADMIN_TOKEN))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("payout_not_resolvable"));

        rail.forget(tx.txHash());
        processor.process(id);
        Payout review = payout(id);
        assertThat(review.status()).isEqualTo("needs_review");
        assertThat(review.statusReason()).contains("different transaction");
        assertThat(balance(AccountKind.MERCHANT_LIQUIDITY_ALLOCATED, merchant.merchantId())).isEqualTo(4_040_000);

        rail.minedNonce = tx.nonce();
        mvc.perform(post("/internal/v1/payouts/" + id + "/resolve_failed").header("X-Admin-Token", TestMerchants.ADMIN_TOKEN))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("outcome_not_proven"));

        rail.minedNonce = tx.nonce() + 1;
        mvc.perform(post("/internal/v1/payouts/" + id + "/resolve_failed").header("X-Admin-Token", TestMerchants.ADMIN_TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("failed"));
        assertThat(balance(AccountKind.MERCHANT_LIQUIDITY, merchant.merchantId())).isEqualTo(5_000_000);
        assertThat(balance(AccountKind.MERCHANT_LIQUIDITY_ALLOCATED, merchant.merchantId())).isZero();
        assertThat(events(id)).containsSubsequence("payout.needs_review", "payout.failed");
    }

    @Test
    void reviewedPayoutStillCompletesWhenItsTransactionIsMined() throws Exception {
        fund(merchant.merchantId(), 5_000_000);
        String id = create(newId(), "1").get("id").asString();
        processor.process(id);
        payouts.flagForReview(id, "operator check");
        assertThat(payout(id).status()).isEqualTo("needs_review");
        rail.mine(outbound(id).txHash(), 990, true, GAS_FEE);
        rail.head = 1_020;
        processor.process(id);
        assertThat(payout(id).status()).isEqualTo("completed");
    }

    @Test
    void reorganisedTransactionReturnsToBroadcastAndCompletesLater() throws Exception {
        fund(merchant.merchantId(), 5_000_000);
        String id = create(newId(), "1").get("id").asString();
        processor.process(id);
        String hash = outbound(id).txHash();
        rail.mine(hash, 1_001, true, GAS_FEE);
        rail.head = 1_002;
        processor.process(id);
        assertThat(payout(id).status()).isEqualTo("confirming");

        rail.forget(hash);
        processor.process(id);
        assertThat(payout(id).status()).isEqualTo("broadcast");
        assertThat(payout(id).statusReason()).contains("reorganisation");

        rail.mine(hash, 1_004, true, GAS_FEE);
        rail.head = 1_030;
        processor.process(id);
        assertThat(payout(id).status()).isEqualTo("completed");
        assertThat(rail.signedNonces).hasSize(1);
        assertThat(events(id)).containsExactly("payout.processing", "payout.broadcast", "payout.confirming",
                "payout.broadcast", "payout.completed");
    }

    @Test
    void consecutivePayoutsUseConsecutiveNoncesAndRespectTheNodeView() throws Exception {
        fund(merchant.merchantId(), 50_000_000);
        rail.pendingNonce = 40;
        String a = create(newId(), "1").get("id").asString();
        String b = create(newId(), "1").get("id").asString();
        rail.nextBroadcast = new PayoutRail.Broadcast(PayoutRail.BroadcastResult.REJECTED, "busy");
        processor.process(a);
        processor.process(b);
        // Something else used the wallet: the node's pending nonce jumps ahead.
        rail.pendingNonce = 90;
        String c = create(newId(), "1").get("id").asString();
        processor.process(c);
        assertThat(List.of(outbound(a).nonce(), outbound(b).nonce(), outbound(c).nonce())).containsExactly(40L, 41L, 90L);
    }

    @Test
    void gasAboveTheCapLeavesThePayoutUnsigned() throws Exception {
        fund(merchant.merchantId(), 5_000_000);
        String id = create(newId(), "1").get("id").asString();
        rail.notReady = "network gas price above the cap";
        processor.process(id);
        assertThat(payout(id).status()).isEqualTo("processing");
        assertThat(payouts.outbound(id)).isEmpty();
        assertThat(payouts.attempts(id)).isEqualTo(1);
        rail.notReady = null;
        processor.process(id);
        assertThat(payout(id).status()).isEqualTo("broadcast");
    }

    @Test
    void concurrentPayoutsNeverReserveMoreThanTheLiquidity() throws Exception {
        fund(merchant.merchantId(), 10_000_000);
        jdbc.sql("UPDATE merchants SET payout_fee_bps = 0 WHERE id = :m").param("m", merchant.merchantId()).update();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Callable<Payout>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            calls.add(() -> payouts.create(merchant.merchantId(), new PayoutService.CreateRequest(newId(), "p",
                    "ETHEREUM", "USDC", "3", PLAYER_WALLET, Map.of()), AuditLog.Actor.system("test")).payout());
        }
        List<String> statuses = new ArrayList<>();
        for (Future<Payout> f : pool.invokeAll(calls)) {
            statuses.add(f.get().status());
        }
        pool.shutdown();
        assertThat(statuses).filteredOn("processing"::equals).hasSize(3);
        assertThat(statuses).filteredOn("awaiting_liquidity"::equals).hasSize(3);
        assertThat(balance(AccountKind.MERCHANT_LIQUIDITY, merchant.merchantId())).isEqualTo(1_000_000);
        assertThat(balance(AccountKind.MERCHANT_LIQUIDITY_ALLOCATED, merchant.merchantId())).isEqualTo(9_000_000);
    }

    @Test
    void limitsAreEnforced() throws Exception {
        fund(merchant.merchantId(), 100_000_000);
        mvc.perform(put("/internal/v1/merchants/" + merchant.merchantId() + "/payout_limits/" + USDC)
                        .header("X-Admin-Token", TestMerchants.ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"per_payout\":\"10\",\"per_day\":\"15\"}"))
                .andExpect(status().isOk());
        mvc.perform(merchant.post("/v1/payouts", body(newId(), "10.01", PLAYER_WALLET)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("payout_limit_exceeded"));
        create(newId(), "10");
        mvc.perform(merchant.post("/v1/payouts", body(newId(), "5.01", PLAYER_WALLET)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("payout_limit_exceeded"));
        create(newId(), "5");
    }

    @Test
    void invalidOrUnauthorisedRequestsAreRefused() throws Exception {
        fund(merchant.merchantId(), 100_000_000);
        mvc.perform(merchant.post("/v1/payouts", body(newId(), "1", "0x1234")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_destination_address"));
        mvc.perform(merchant.post("/v1/payouts", body(newId(), "1", "0x" + "00".repeat(20))))
                .andExpect(jsonPath("$.code").value("invalid_destination_address"));
        mvc.perform(merchant.post("/v1/payouts", body(newId(), "1", rail.fromAddress())))
                .andExpect(jsonPath("$.code").value("invalid_destination_address"));
        String ours = addresses.getOrCreate(merchant.merchantId(), DepositAddressService.OwnerType.merchant,
                merchant.merchantId(), "ETHEREUM", AuditLog.Actor.system("test")).address();
        mvc.perform(merchant.post("/v1/payouts", body(newId(), "1", ours.toLowerCase())))
                .andExpect(jsonPath("$.code").value("invalid_destination_address"));
        mvc.perform(merchant.post("/v1/payouts", body(newId(), "0", PLAYER_WALLET)))
                .andExpect(jsonPath("$.code").value("invalid_amount"));
        mvc.perform(merchant.post("/v1/payouts", body(newId(), "1.0000001", PLAYER_WALLET)))
                .andExpect(jsonPath("$.code").value("invalid_amount"));
        mvc.perform(merchant.post("/v1/payouts", """
                        {"merchant_payout_id":"%s","player_ref":"p","network":"TRON","asset":"USDT","amount":"1",
                         "destination_address":"TLsV52sRDL79HXGGm9yzwKibb6BeruhUzy"}""".formatted(newId())))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("payouts_unavailable"));

        // Keys need the payouts scope; merchants with platform-kept player balances use user cashouts instead.
        TestMerchants.Onboarded basic = TestMerchants.onboard(mvc, json);
        mvc.perform(basic.post("/v1/payouts", body(newId(), "1", PLAYER_WALLET))).andExpect(status().isForbidden());
        JsonNode key = TestMerchants.adminPost(mvc, json, "/internal/v1/merchants/" + basic.merchantId() + "/api-keys",
                "{\"name\":\"cashier\",\"scopes\":[\"payouts:write\"]}");
        TestMerchants.Onboarded platformManaged = new TestMerchants.Onboarded(basic.merchantId(),
                key.get("secret").asString(), key.get("signing_secret").asString(), key.get("id").asString());
        mvc.perform(platformManaged.post("/v1/payouts", body(newId(), "1", PLAYER_WALLET)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("payouts_require_merchant_managed_balances"));
    }

    @Test
    void dueWorkIsLeasedToOneWorkerAtATime() throws Exception {
        fund(merchant.merchantId(), 5_000_000);
        String id = create(newId(), "1").get("id").asString();
        assertThat(payouts.claimDue(10_000, java.time.Duration.ofMinutes(2))).contains(id);
        assertThat(payouts.claimDue(10_000, java.time.Duration.ofMinutes(2))).doesNotContain(id);
        payouts.reschedule(id, java.time.Duration.ZERO, false);
        assertThat(payouts.claimDue(10_000, java.time.Duration.ofMinutes(2))).contains(id);
    }

    @Test
    void devKeySignerIsRefusedInLiveMode() {
        PayoutProperties props = new PayoutProperties(Map.of("ETHEREUM", new PayoutProperties.Chain(true, "http://x",
                PayoutProperties.SignerType.dev_key, "0x" + "01".repeat(32), null, 125, 100_000)),
                java.time.Duration.ofMinutes(30), java.time.Duration.ofMinutes(2), java.time.Duration.ofSeconds(10));
        com.cpg.gateway.config.GatewayProperties live = new com.cpg.gateway.config.GatewayProperties(
                com.cpg.gateway.config.GatewayProperties.Mode.live,
                new com.cpg.gateway.config.GatewayProperties.Security("p", null, "k"),
                new com.cpg.gateway.config.GatewayProperties.Webhooks(16),
                new com.cpg.gateway.config.GatewayProperties.Jobs(false));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PayoutRails(jdbc, props, (c, f, id, cfg) -> null, live))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not allowed in live mode");
    }
}
