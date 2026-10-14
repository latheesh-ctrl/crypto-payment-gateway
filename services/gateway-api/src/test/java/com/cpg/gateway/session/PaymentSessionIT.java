package com.cpg.gateway.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cpg.gateway.IntegrationTest;
import com.cpg.gateway.TestMerchants;
import com.cpg.gateway.deposit.ControllableVerifier;
import com.cpg.gateway.deposit.DepositService;
import com.cpg.gateway.deposit.ObservedTransfer;
import com.cpg.gateway.deposit.TransferVerifier.Result;
import com.cpg.gateway.ledger.AccountKind;
import com.cpg.gateway.ledger.AccountRef;
import com.cpg.gateway.ledger.LedgerService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Payment sessions from creation to credit, driven through the real deposit lifecycle. */
@IntegrationTest
@Import(ControllableVerifier.Config.class)
class PaymentSessionIT {

    private static final String USDC = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";
    private static final int ETH_FINALITY = 12;

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired DepositService deposits;
    @Autowired PaymentSessionService sessions;
    @Autowired LedgerService ledger;
    @Autowired ControllableVerifier verifier;
    @Autowired CheckoutTokens tokens;

    private TestMerchants.Onboarded merchant;

    @BeforeEach
    void setUp() throws Exception {
        verifier.next.set(Result.CONFIRMED);
        merchant = TestMerchants.onboard(mvc, json);
        jdbc.sql("UPDATE merchants SET deposit_fee_bps = 100 WHERE id = :m").param("m", merchant.merchantId()).update();
    }

    private static String order() {
        return "GK-DEP-" + UUID.randomUUID();
    }

    private JsonNode create(String order, String amount) throws Exception {
        return json.readTree(mvc.perform(merchant.post("/v1/payment_sessions", """
                        {"player_ref":"gk_player_8812","merchant_order_id":"%s","network":"ethereum","asset":"USDC",
                         "amount":"%s","return_url":"https://gameking.example/wallet","metadata":{"table":"vip-3"}}
                        """.formatted(order, amount)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private static String tx() {
        return "0x" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    /** A USDC transfer to the session address, seen and then confirmed to finality. */
    private void pay(String address, long units) {
        String tx = tx();
        deposits.onTransferObserved(new ObservedTransfer("ETHEREUM", tx, 4, address, USDC, BigInteger.valueOf(units), 100L, "h"));
        deposits.onConfirmations("ETHEREUM", tx, 4, 100L, "h", 1);
        deposits.onConfirmations("ETHEREUM", tx, 4, 100L, "h", ETH_FINALITY);
    }

    private JsonNode session(String id) throws Exception {
        return json.readTree(mvc.perform(merchant.authorize(get("/v1/payment_sessions/" + id)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private List<String> events(String sessionId) {
        return jdbc.sql("SELECT event_type FROM outbox_events WHERE aggregate_id = :id ORDER BY created_at, id")
                .param("id", sessionId).query(String.class).list();
    }

    private static BigDecimal dec(JsonNode node) {
        return new BigDecimal(node.asString());
    }

    @Test
    void createReturnsABoundSessionWithItsOwnAddressAndPaymentUrl() throws Exception {
        String order = order();
        JsonNode s = create(order, "25.50");
        assertThat(s.get("id").asString()).startsWith("ps_");
        assertThat(s.get("status").asString()).isEqualTo("open");
        assertThat(s.get("network").asString()).isEqualTo("ETHEREUM");
        assertThat(dec(s.get("amount"))).isEqualByComparingTo("25.50");
        assertThat(s.get("address").asString()).matches("0x[0-9a-fA-F]{40}");
        assertThat(s.get("required_confirmations").asInt()).isEqualTo(ETH_FINALITY);
        assertThat(s.get("payment_url").asString()).startsWith("http://localhost:3000/s/" + s.get("id").asString() + "#t=");
        assertThat(s.get("metadata").get("table").asString()).isEqualTo("vip-3");

        // Same order again (new idempotency key): the same session. Different details: a conflict.
        mvc.perform(merchant.post("/v1/payment_sessions", """
                        {"player_ref":"gk_player_8812","merchant_order_id":"%s","network":"ETHEREUM","asset":"usdc","amount":"25.5"}
                        """.formatted(order)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(s.get("id").asString()));
        mvc.perform(merchant.post("/v1/payment_sessions", """
                        {"player_ref":"gk_player_8812","merchant_order_id":"%s","network":"ETHEREUM","asset":"USDC","amount":"26"}
                        """.formatted(order)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("order_conflict"));

        // Each session has its own address.
        assertThat(create(order(), "1").get("address").asString()).isNotEqualTo(s.get("address").asString());

        mvc.perform(merchant.authorize(get("/v1/payment_sessions").param("merchant_order_id", order)))
                .andExpect(jsonPath("$.data[0].id").value(s.get("id").asString()));
        TestMerchants.Onboarded other = TestMerchants.onboard(mvc, json);
        mvc.perform(other.authorize(get("/v1/payment_sessions/" + s.get("id").asString()))).andExpect(status().isNotFound());
    }

    @Test
    void invalidRequestsAreRefused() throws Exception {
        mvc.perform(merchant.post("/v1/payment_sessions", """
                        {"player_ref":"p","merchant_order_id":"o-1","network":"ETHEREUM","asset":"DOGE","amount":"1"}"""))
                .andExpect(jsonPath("$.code").value("unsupported_asset"));
        mvc.perform(merchant.post("/v1/payment_sessions", """
                        {"player_ref":"p","merchant_order_id":"o-2","network":"ETHEREUM","asset":"USDC","amount":"1.0000001"}"""))
                .andExpect(jsonPath("$.code").value("invalid_amount"));
        mvc.perform(merchant.post("/v1/payment_sessions", """
                        {"player_ref":"p","merchant_order_id":"o-3","network":"ETHEREUM","asset":"USDC","amount":"0"}"""))
                .andExpect(jsonPath("$.code").value("invalid_amount"));
        mvc.perform(merchant.post("/v1/payment_sessions", """
                        {"player_ref":"p","merchant_order_id":"o-4","network":"ETHEREUM","asset":"USDC","amount":"1",
                         "expires_in_seconds":60}"""))
                .andExpect(jsonPath("$.code").value("invalid_expiry"));
        mvc.perform(merchant.post("/v1/payment_sessions", """
                        {"player_ref":"p","merchant_order_id":"o-5","network":"ETHEREUM","asset":"USDC","amount":"-1"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exactPaymentCompletesTheSessionAndFundsMerchantLiquidityOnly() throws Exception {
        JsonNode s = create(order(), "25");
        String id = s.get("id").asString();
        String tx = tx();
        deposits.onTransferObserved(new ObservedTransfer("ETHEREUM", tx, 4, s.get("address").asString().toLowerCase(), USDC,
                BigInteger.valueOf(25_000_000), 100L, "h"));
        assertThat(session(id).get("status").asString()).isEqualTo("pending");
        deposits.onConfirmations("ETHEREUM", tx, 4, 100L, "h", 3);
        assertThat(session(id).get("status").asString()).isEqualTo("confirming");
        deposits.onConfirmations("ETHEREUM", tx, 4, 100L, "h", ETH_FINALITY);

        JsonNode done = session(id);
        assertThat(done.get("status").asString()).isEqualTo("completed");
        assertThat(dec(done.get("amount_received"))).isEqualByComparingTo("25");
        assertThat(done.get("completed_at").isNull()).isFalse();
        assertThat(done.get("payments").get(0).get("tx_hash").asString()).isEqualTo(tx);
        assertThat(events(id)).containsExactly("payment_session.pending", "payment_session.confirming",
                "payment_session.completed");

        // Liquidity gets 25 minus the 1% fee; the platform keeps no player balance for sessions.
        assertThat(ledger.balance(AccountRef.merchant(AccountKind.MERCHANT_LIQUIDITY, merchant.merchantId(), "USDC_ETHEREUM")))
                .isEqualTo(BigInteger.valueOf(24_750_000));
        assertThat(ledger.balance(AccountRef.merchant(AccountKind.MERCHANT_USER_OBLIGATIONS, merchant.merchantId(),
                "USDC_ETHEREUM"))).isZero();

        // The same chain event again changes nothing.
        deposits.onConfirmations("ETHEREUM", tx, 4, 100L, "h", 40);
        assertThat(events(id)).hasSize(3);
    }

    @Test
    void underpaymentThenTopUpThenOverpayment() throws Exception {
        JsonNode s = create(order(), "10");
        String id = s.get("id").asString();
        String address = s.get("address").asString();

        pay(address, 4_000_000);
        assertThat(session(id).get("status").asString()).isEqualTo("underpaid");
        pay(address, 6_000_000);
        assertThat(session(id).get("status").asString()).isEqualTo("completed");
        pay(address, 1);
        JsonNode over = session(id);
        assertThat(over.get("status").asString()).isEqualTo("overpaid");
        assertThat(dec(over.get("amount_received"))).isEqualByComparingTo("10.000001");
        assertThat(over.get("payments").size()).isEqualTo(3);
        assertThat(events(id)).contains("payment_session.underpaid", "payment_session.completed",
                "payment_session.overpaid");
    }

    @Test
    void expiryAndLatePayment() throws Exception {
        JsonNode s = create(order(), "5");
        String id = s.get("id").asString();
        jdbc.sql("UPDATE payment_sessions SET expires_at = now() - interval '1 second' WHERE id = :id").param("id", id).update();

        assertThat(sessions.dueForExpiry(1000)).contains(id);
        sessions.refresh(id);
        assertThat(session(id).get("status").asString()).isEqualTo("expired");

        // Money that arrives after expiry is still credited and reported, never lost.
        pay(s.get("address").asString(), 5_000_000);
        assertThat(session(id).get("status").asString()).isEqualTo("late_payment");
        assertThat(events(id)).containsSubsequence("payment_session.expired", "payment_session.late_payment");
    }

    @Test
    void reorgBeforeFinalitySendsTheSessionBackToOpen() throws Exception {
        JsonNode s = create(order(), "5");
        String id = s.get("id").asString();
        String tx = tx();
        deposits.onTransferObserved(new ObservedTransfer("ETHEREUM", tx, 4, s.get("address").asString(), USDC,
                BigInteger.valueOf(5_000_000), 100L, "h"));
        deposits.onConfirmations("ETHEREUM", tx, 4, 100L, "h", 2);
        deposits.onReorged("ETHEREUM", tx, 4, "block replaced");

        assertThat(session(id).get("status").asString()).isEqualTo("open");
        assertThat(events(id)).containsExactly("payment_session.pending", "payment_session.confirming",
                "payment_session.reversed");
    }

    @Test
    void wrongAssetOrVerificationMismatchGoesToReview() throws Exception {
        JsonNode s = create(order(), "5");
        String tx = tx();
        deposits.onTransferObserved(new ObservedTransfer("ETHEREUM", tx, 0, s.get("address").asString(), null,
                new BigInteger("1000000000000000000"), 100L, "h"));
        assertThat(session(s.get("id").asString()).get("status").asString()).isEqualTo("needs_review");

        JsonNode t = create(order(), "5");
        verifier.next.set(Result.MISMATCH);
        pay(t.get("address").asString(), 5_000_000);
        assertThat(session(t.get("id").asString()).get("status").asString()).isEqualTo("needs_review");
    }

    @Test
    void cancelOnlyWhileNothingHasArrived() throws Exception {
        JsonNode s = create(order(), "5");
        mvc.perform(merchant.post("/v1/payment_sessions/" + s.get("id").asString() + "/cancel", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
        assertThat(events(s.get("id").asString())).containsExactly("payment_session.cancelled");

        JsonNode paid = create(order(), "5");
        pay(paid.get("address").asString(), 5_000_000);
        mvc.perform(merchant.post("/v1/payment_sessions/" + paid.get("id").asString() + "/cancel", ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("session_not_cancellable"));
    }

    @Test
    void hostedPageApiNeedsTheSessionTokenAndShowsNoMerchantData() throws Exception {
        JsonNode s = create(order(), "7.25");
        String id = s.get("id").asString();
        String token = s.get("payment_url").asString().split("#t=")[1];
        assertThat(token).isEqualTo(tokens.tokenFor(id));

        mvc.perform(get("/checkout/v1/sessions/" + id).header("X-Checkout-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value(s.get("address").asString()))
                .andExpect(jsonPath("$.asset").value("USDC"))
                .andExpect(jsonPath("$.token_contract").value(USDC))
                .andExpect(jsonPath("$.network_name").value("Ethereum"))
                .andExpect(jsonPath("$.player_ref").doesNotExist())
                .andExpect(jsonPath("$.merchant_order_id").doesNotExist())
                .andExpect(jsonPath("$.metadata").doesNotExist());
        mvc.perform(get("/checkout/v1/sessions/" + id).header("X-Checkout-Token", token + "x"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/checkout/v1/sessions/" + id)).andExpect(status().isNotFound());

        // Only the hosted page's origin may call it from a browser.
        mvc.perform(options("/checkout/v1/sessions/" + id).header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "X-Checkout-Token"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        mvc.perform(options("/checkout/v1/sessions/" + id).header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void sandboxCanPayASessionWithoutAChain() throws Exception {
        JsonNode s = create(order(), "3");
        mvc.perform(merchant.post("/v1/sandbox/simulate_deposit",
                        "{\"address\":\"%s\",\"asset\":\"USDC_ETHEREUM\",\"amount\":\"3\"}".formatted(s.get("address").asString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("session_payment"))
                .andExpect(jsonPath("$.payment_session_id").value(s.get("id").asString()));
        assertThat(session(s.get("id").asString()).get("status").asString()).isEqualTo("completed");
    }
}
