package com.cpg.gateway.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cpg.gateway.IntegrationTest;
import com.cpg.gateway.TestMerchants;
import com.cpg.gateway.deposit.TransferVerifier.Result;
import com.cpg.gateway.ledger.AccountKind;
import com.cpg.gateway.ledger.AccountRef;
import com.cpg.gateway.ledger.LedgerService;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@Import(ControllableVerifier.Config.class)
class DepositFlowIT {

    private static final String USDT_TRON = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";
    private static final String USDC_ETH = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired DepositService deposits;
    @Autowired LedgerService ledger;
    @Autowired ControllableVerifier verifier;

    private TestMerchants.Onboarded merchant;
    private String userId;

    @BeforeEach
    void setUp() throws Exception {
        verifier.next.set(Result.CONFIRMED);
        merchant = TestMerchants.onboard(mvc, json);
        jdbc.sql("UPDATE merchants SET deposit_fee_bps = 100 WHERE id = :m").param("m", merchant.merchantId()).update();
        String body = mvc.perform(merchant.post("/v1/users", "{\"external_ref\":\"player-%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        userId = json.readTree(body).get("id").asString();
    }

    private String userAddress(String chain) throws Exception {
        String body = mvc.perform(merchant.post("/v1/users/" + userId + "/deposit_addresses", "{\"chain\":\"%s\"}".formatted(chain)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("address").asString();
    }

    private static String tx() {
        return "0x" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private BigInteger balance(AccountRef ref) {
        return ledger.balance(ref);
    }

    @Test
    void addressesArePermanentPerUserAndChainAndCorrectlyFormatted() throws Exception {
        String tron = userAddress("TRON");
        assertThat(tron).startsWith("T").hasSize(34);
        assertThat(userAddress("TRON")).isEqualTo(tron);
        assertThat(userAddress("ETHEREUM")).matches("0x[0-9a-fA-F]{40}");
        assertThat(userAddress("BITCOIN")).startsWith("bc1q");
        mvc.perform(merchant.authorize(get("/v1/users/" + userId + "/deposit_addresses")))
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    void unconfiguredOrUnknownChainsAreRefused() throws Exception {
        mvc.perform(merchant.post("/v1/users/" + userId + "/deposit_addresses", "{\"chain\":\"ARBITRUM\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("chain_unavailable"));
        mvc.perform(merchant.post("/v1/users/" + userId + "/deposit_addresses", "{\"chain\":\"DOGE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unsupported_chain"));
    }

    @Test
    void userDepositIsCreditedOnlyAfterFinalityAndFundsMerchantLiquidity() throws Exception {
        String address = userAddress("TRON");
        String tx = tx();
        BigInteger amount = BigInteger.valueOf(100_000_000); // 100 USDT

        Deposit d = deposits.onTransferObserved(new ObservedTransfer("TRON", tx, 3, address, USDT_TRON, amount, 1000L, "h1"))
                .orElseThrow();
        assertThat(d.status()).isEqualTo("detected");
        assertThat(d.assetId()).isEqualTo("USDT_TRON");
        assertThat(balance(AccountRef.user(AccountKind.USER_AVAILABLE, userId, "USDT_TRON"))).isZero();

        deposits.onConfirmations("TRON", tx, 3, 1000L, "h1", 5);
        assertThat(deposits.require(d.id()).status()).isEqualTo("confirming");
        assertThat(balance(AccountRef.user(AccountKind.USER_AVAILABLE, userId, "USDT_TRON"))).isZero();

        Deposit done = deposits.onConfirmations("TRON", tx, 3, 1000L, "h1", 19).orElseThrow();
        assertThat(done.status()).isEqualTo("completed");
        assertThat(done.fee()).isEqualTo(BigInteger.valueOf(1_000_000));

        // User credited in full; merchant liquidity gets amount minus the 1% fee; obligations track what users are owed.
        assertThat(balance(AccountRef.user(AccountKind.USER_AVAILABLE, userId, "USDT_TRON"))).isEqualTo(amount);
        String m = merchant.merchantId();
        assertThat(balance(AccountRef.merchant(AccountKind.MERCHANT_LIQUIDITY, m, "USDT_TRON"))).isEqualTo(BigInteger.valueOf(99_000_000));
        assertThat(balance(AccountRef.merchant(AccountKind.MERCHANT_USER_OBLIGATIONS, m, "USDT_TRON"))).isEqualTo(amount);

        mvc.perform(merchant.authorize(get("/v1/liquidity")))
                .andExpect(jsonPath("$.data[0].asset").value("USDT_TRON"))
                .andExpect(jsonPath("$.data[0].available").value("99"))
                .andExpect(jsonPath("$.data[0].owed_to_users").value("100"));
        mvc.perform(merchant.authorize(get("/v1/deposits/" + d.id())))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.amount").value("100"))
                .andExpect(jsonPath("$.user_id").value(userId));

        List<String> history = jdbc.sql("SELECT to_status FROM deposit_status_history WHERE deposit_id = :d ORDER BY id")
                .param("d", d.id()).query(String.class).list();
        assertThat(history).containsExactly("detected", "confirming", "completed");
        assertThat(jdbc.sql("SELECT event_type FROM outbox_events WHERE aggregate_id = :d ORDER BY created_at")
                .param("d", d.id()).query(String.class).list()).containsExactly("deposit.detected", "deposit.completed");
    }

    @Test
    void repeatedEventsNeverCreditTwice() throws Exception {
        String address = userAddress("TRON");
        String tx = tx();
        ObservedTransfer t = new ObservedTransfer("TRON", tx, 0, address, USDT_TRON, BigInteger.valueOf(5_000_000), 10L, "h");
        deposits.onTransferObserved(t);
        deposits.onTransferObserved(t);
        deposits.onConfirmations("TRON", tx, 0, 10L, "h", 25);
        deposits.onConfirmations("TRON", tx, 0, 10L, "h", 30);
        deposits.onTransferObserved(t);

        assertThat(jdbc.sql("SELECT count(*) FROM deposits WHERE tx_hash = :tx").param("tx", tx).query(Long.class).single())
                .isEqualTo(1);
        assertThat(balance(AccountRef.user(AccountKind.USER_AVAILABLE, userId, "USDT_TRON"))).isEqualTo(BigInteger.valueOf(5_000_000));
    }

    @Test
    void concurrentConfirmationEventsCreditOnce() throws Exception {
        String address = userAddress("TRON");
        String tx = tx();
        deposits.onTransferObserved(new ObservedTransfer("TRON", tx, 0, address, USDT_TRON, BigInteger.valueOf(7), 10L, "h"));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit((Callable<Object>) () -> deposits.onConfirmations("TRON", tx, 0, 10L, "h", 20)));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        assertThat(balance(AccountRef.user(AccountKind.USER_AVAILABLE, userId, "USDT_TRON"))).isEqualTo(BigInteger.valueOf(7));
    }

    @Test
    void unsupportedTokenIsRecordedButNeverCredited() throws Exception {
        String address = userAddress("TRON");
        String tx = tx();
        Deposit d = deposits.onTransferObserved(new ObservedTransfer("TRON", tx, 1, address, "TFakeTokenContract1111111111111111",
                BigInteger.TEN, 5L, "h")).orElseThrow();
        assertThat(d.status()).isEqualTo("unsupported");
        deposits.onConfirmations("TRON", tx, 1, 5L, "h", 50);
        assertThat(deposits.require(d.id()).status()).isEqualTo("unsupported");
        assertThat(jdbc.sql("SELECT count(*) FROM ledger_transactions WHERE reference LIKE :r").param("r", "deposit:TRON:" + tx + "%")
                .query(Long.class).single()).isZero();
    }

    @Test
    void reorgBeforeFinalityReversesWithoutCredit() throws Exception {
        String address = userAddress("TRON");
        String tx = tx();
        deposits.onTransferObserved(new ObservedTransfer("TRON", tx, 0, address, USDT_TRON, BigInteger.TEN, 5L, "h"));
        deposits.onConfirmations("TRON", tx, 0, 5L, "h", 3);
        Deposit d = deposits.onReorged("TRON", tx, 0, "block 5 replaced").orElseThrow();
        assertThat(d.status()).isEqualTo("reversed");
        deposits.onConfirmations("TRON", tx, 0, 5L, "h", 40);
        assertThat(deposits.require(d.id()).status()).isEqualTo("reversed");
        assertThat(balance(AccountRef.user(AccountKind.USER_AVAILABLE, userId, "USDT_TRON"))).isZero();
    }

    @Test
    void verificationMismatchGoesToReviewAndUnavailableWaits() throws Exception {
        String address = userAddress("TRON");
        String tx1 = tx();
        verifier.next.set(Result.UNAVAILABLE);
        deposits.onTransferObserved(new ObservedTransfer("TRON", tx1, 0, address, USDT_TRON, BigInteger.TEN, 5L, "h"));
        Deposit waiting = deposits.onConfirmations("TRON", tx1, 0, 5L, "h", 19).orElseThrow();
        assertThat(waiting.status()).isEqualTo("confirming");
        assertThat(deposits.awaitingVerification(100)).contains(waiting.id());

        verifier.next.set(Result.CONFIRMED);
        deposits.retryVerification(waiting.id());
        assertThat(deposits.require(waiting.id()).status()).isEqualTo("completed");

        String tx2 = tx();
        verifier.next.set(Result.MISMATCH);
        deposits.onTransferObserved(new ObservedTransfer("TRON", tx2, 0, address, USDT_TRON, BigInteger.ONE, 6L, "h"));
        Deposit flagged = deposits.onConfirmations("TRON", tx2, 0, 6L, "h", 19).orElseThrow();
        assertThat(flagged.status()).isEqualTo("needs_review");
        assertThat(balance(AccountRef.user(AccountKind.USER_AVAILABLE, userId, "USDT_TRON"))).isEqualTo(BigInteger.TEN);
    }

    @Test
    void evmAddressesMatchRegardlessOfCase() throws Exception {
        String address = userAddress("ETHEREUM");
        String tx = tx();
        Deposit d = deposits.onTransferObserved(new ObservedTransfer("ETHEREUM", tx, 7, address.toLowerCase(),
                USDC_ETH.toLowerCase(), BigInteger.valueOf(2_500_000), 1L, "h")).orElseThrow();
        assertThat(d.assetId()).isEqualTo("USDC_ETHEREUM");
    }

    @Test
    void nativeCoinDepositsResolveToTheNativeAsset() throws Exception {
        String address = userAddress("BITCOIN");
        String tx = tx();
        Deposit d = deposits.onTransferObserved(new ObservedTransfer("BITCOIN", tx, 1, address, null,
                BigInteger.valueOf(150_000), 800_000L, "h")).orElseThrow();
        assertThat(d.assetId()).isEqualTo("BTC_BITCOIN");
        deposits.onConfirmations("BITCOIN", tx, 1, 800_000L, "h", 2);
        assertThat(balance(AccountRef.user(AccountKind.USER_AVAILABLE, userId, "BTC_BITCOIN"))).isEqualTo(BigInteger.valueOf(150_000));
    }

    @Test
    void transfersToUnknownAddressesAreIgnored() {
        assertThat(deposits.onTransferObserved(new ObservedTransfer("TRON", tx(), 0, "TNotOurs111111111111111111111111111",
                USDT_TRON, BigInteger.TEN, 1L, "h"))).isEmpty();
    }

    @Test
    void merchantPayinFundsLiquidityWithoutFee() throws Exception {
        String body = mvc.perform(merchant.post("/v1/payin_addresses", "{\"chain\":\"TRON\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String address = json.readTree(body).get("address").asString();
        String tx = tx();
        deposits.onTransferObserved(new ObservedTransfer("TRON", tx, 0, address, USDT_TRON, BigInteger.valueOf(1_000_000_000), 1L, "h"));
        deposits.onConfirmations("TRON", tx, 0, 1L, "h", 19);
        String m = merchant.merchantId();
        assertThat(balance(AccountRef.merchant(AccountKind.MERCHANT_LIQUIDITY, m, "USDT_TRON"))).isEqualTo(BigInteger.valueOf(1_000_000_000));
        assertThat(balance(AccountRef.merchant(AccountKind.MERCHANT_USER_OBLIGATIONS, m, "USDT_TRON"))).isZero();
        mvc.perform(merchant.authorize(get("/v1/deposits").param("type", "merchant_payin")))
                .andExpect(jsonPath("$.data[0].type").value("merchant_payin"))
                .andExpect(jsonPath("$.data[0].fee").value("0"));
    }

    @Test
    void merchantsCannotSeeOrSimulateOnOthersAddresses() throws Exception {
        String address = userAddress("TRON");
        String tx = tx();
        Deposit d = deposits.onTransferObserved(new ObservedTransfer("TRON", tx, 0, address, USDT_TRON, BigInteger.TEN, 1L, "h"))
                .orElseThrow();
        TestMerchants.Onboarded other = TestMerchants.onboard(mvc, json);
        mvc.perform(other.authorize(get("/v1/deposits/" + d.id()))).andExpect(status().isNotFound());
        mvc.perform(other.post("/v1/sandbox/simulate_deposit",
                        "{\"address\":\"%s\",\"asset\":\"USDT_TRON\",\"amount\":\"5\"}".formatted(address)))
                .andExpect(status().isNotFound());
    }

    @Test
    void sandboxSimulationCompletesADeposit() throws Exception {
        String address = userAddress("TRON");
        mvc.perform(merchant.post("/v1/sandbox/simulate_deposit",
                        "{\"address\":\"%s\",\"asset\":\"USDT_TRON\",\"amount\":\"12.5\"}".formatted(address)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.amount").value("12.5"));
        mvc.perform(merchant.authorize(get("/v1/users/" + userId + "/balances")))
                .andExpect(jsonPath("$.data[0].available").value("12.5"));
    }

    @Test
    void depositHistoryIsAppendOnly() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("DELETE FROM deposit_status_history").update())
                .hasMessageContaining("append-only");
    }
}
