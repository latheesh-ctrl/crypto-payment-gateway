package com.cpg.gateway.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cpg.gateway.IntegrationTest;
import com.cpg.gateway.TestMerchants;
import com.cpg.gateway.chain.FakeChain.Payment;
import com.cpg.gateway.deposit.Deposit;
import com.cpg.gateway.deposit.DepositService;
import com.cpg.gateway.deposit.ObservedTransfer;
import com.cpg.gateway.ledger.AccountKind;
import com.cpg.gateway.ledger.AccountRef;
import com.cpg.gateway.ledger.LedgerService;
import com.cpg.gateway.wallet.DepositAddressService;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** The Tron watcher end to end against an in-memory chain, with the real verifier and deposit lifecycle. */
@IntegrationTest
@Import(FakeChain.Config.class)
@TestPropertySource(properties = {
        "gateway.indexer.chains.TRON.enabled=true",
        "gateway.indexer.chains.TRON.rpc-url=fake://scan",
        "gateway.indexer.chains.TRON.verify-rpc-urls=fake://verify-a,fake://verify-b"})
class ChainScannerIT {

    private static final String USDT = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";
    private static final BigInteger TEN_USDT = BigInteger.valueOf(10_000_000);
    /** Each test gets its own height range so earlier tests' deposits never fall inside its blocks. */
    private static final AtomicLong HEIGHTS = new AtomicLong(1_000_000);

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ChainScanner scanner;
    @Autowired ChainClients clients;
    @Autowired DepositService deposits;
    @Autowired DepositAddressService addresses;
    @Autowired LedgerService ledger;
    @Autowired FakeChain.Registry chains;

    private FakeChain chain;
    private String userId;
    private String address;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("DELETE FROM chain_blocks WHERE chain_code = 'TRON'").update();
        jdbc.sql("DELETE FROM chain_scan_cursors WHERE chain_code = 'TRON'").update();
        chain = chains.get("TRON");
        chain.reset(HEIGHTS.getAndAdd(1_000_000));

        TestMerchants.Onboarded merchant = TestMerchants.onboard(mvc, json);
        String body = mvc.perform(merchant.post("/v1/users", "{\"external_ref\":\"p-%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        userId = json.readTree(body).get("id").asString();
        body = mvc.perform(merchant.post("/v1/users/" + userId + "/deposit_addresses", "{\"chain\":\"TRON\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        address = json.readTree(body).get("address").asString();

        // The first poll anchors on the current head.
        assertThat(scanner.poll("TRON")).isPresent();
    }

    private static String txHash() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private Deposit deposit(String txHash) {
        String id = jdbc.sql("SELECT id FROM deposits WHERE tx_hash = :tx").param("tx", txHash)
                .query(String.class).single();
        return deposits.require(id);
    }

    private BigInteger userBalance() {
        return ledger.balance(AccountRef.user(AccountKind.USER_AVAILABLE, userId, "USDT_TRON"));
    }

    @Test
    void depositIsCreditedOnceAfterNineteenScannedConfirmationsAndVerification() {
        String hash = txHash();
        long block = chain.mine(new Payment(hash, 1, address, USDT, TEN_USDT));
        ChainScanner.PollResult r = scanner.poll("TRON").orElseThrow();
        assertThat(r.lastBlock()).isEqualTo(block);

        Deposit d = deposit(hash);
        assertThat(d.status()).isEqualTo("confirming");
        assertThat(d.confirmations()).isEqualTo(1);
        assertThat(d.blockNumber()).isEqualTo(block);

        chain.mine(17);
        scanner.poll("TRON");
        assertThat(deposit(hash).confirmations()).isEqualTo(18);
        assertThat(userBalance()).isZero();

        chain.mine(1);
        scanner.poll("TRON");
        assertThat(deposit(hash).status()).isEqualTo("completed");
        assertThat(userBalance()).isEqualTo(TEN_USDT);

        chain.mine(5);
        scanner.poll("TRON");
        assertThat(userBalance()).isEqualTo(TEN_USDT);
        assertThat(jdbc.sql("SELECT count(*) FROM ledger_transactions WHERE reference LIKE :r")
                .param("r", "deposit:TRON:" + hash + ":%").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void reorgReversesTheOpenDepositAndReinclusionStartsItAgain() {
        String hash = txHash();
        Payment payment = new Payment(hash, 1, address, USDT, TEN_USDT);
        long block = chain.mine(payment);
        chain.mine(2);
        scanner.poll("TRON");
        assertThat(deposit(hash).confirmations()).isEqualTo(3);

        chain.reorgFrom(block);
        chain.mine(5);
        ChainScanner.PollResult r = scanner.poll("TRON").orElseThrow();
        assertThat(r.reorgFrom()).isEqualTo(block - 1);
        assertThat(deposit(hash).status()).isEqualTo("reversed");

        scanner.poll("TRON"); // rescans the new branch
        long again = chain.mine(payment);
        scanner.poll("TRON");
        Deposit revived = deposit(hash);
        assertThat(revived.status()).isEqualTo("confirming");
        assertThat(revived.blockNumber()).isEqualTo(again);

        chain.mine(18);
        scanner.poll("TRON");
        assertThat(deposit(hash).status()).isEqualTo("completed");
        assertThat(userBalance()).isEqualTo(TEN_USDT);
        List<String> history = jdbc.sql("""
                        SELECT to_status FROM deposit_status_history WHERE deposit_id = :id ORDER BY id
                        """).param("id", revived.id()).query(String.class).list();
        assertThat(history).containsExactly("detected", "confirming", "reversed", "detected", "confirming", "completed");
    }

    @Test
    void transfersToAddressesWeDontOwnAreIgnored() {
        String hash = txHash();
        chain.mine(new Payment(hash, 0, "T" + "x".repeat(33), null, BigInteger.TEN));
        scanner.poll("TRON");
        assertThat(jdbc.sql("SELECT count(*) FROM deposits WHERE tx_hash = :tx").param("tx", hash)
                .query(Long.class).single()).isZero();
    }

    @Test
    void verificationDisagreementSendsTheDepositToReview() {
        chain.verifierView = t -> new ObservedTransfer(t.chain(), t.txHash(), t.outputIndex(), t.toAddress(),
                t.tokenContract(), t.amount().add(BigInteger.ONE), t.blockNumber(), t.blockHash());
        String hash = txHash();
        chain.mine(new Payment(hash, 1, address, USDT, TEN_USDT));
        chain.mine(18);
        scanner.poll("TRON");
        assertThat(deposit(hash).status()).isEqualTo("needs_review");
        assertThat(userBalance()).isZero();
    }

    @Test
    void verifierOutageHoldsTheCreditUntilVerificationSucceeds() {
        chain.verifiersDown = true;
        String hash = txHash();
        chain.mine(new Payment(hash, 1, address, USDT, TEN_USDT));
        chain.mine(18);
        scanner.poll("TRON");
        Deposit waiting = deposit(hash);
        assertThat(waiting.status()).isEqualTo("confirming");
        assertThat(waiting.confirmations()).isEqualTo(19);
        assertThat(deposits.awaitingVerification(1000)).contains(waiting.id());
        assertThat(userBalance()).isZero();

        chain.verifiersDown = false;
        deposits.retryVerification(waiting.id());
        assertThat(deposit(hash).status()).isEqualTo("completed");
        assertThat(userBalance()).isEqualTo(TEN_USDT);
    }

    @Test
    void catchesUpInBatchesAndOnlyOneWatcherScansAChain() {
        chain.mine(120);
        assertThat(scanner.poll("TRON").orElseThrow().scanned()).isEqualTo(50);

        ChainScanner other = new ChainScanner(jdbc, tx, clients, deposits, addresses);
        assertThat(other.poll("TRON")).isEmpty();

        assertThat(scanner.poll("TRON").orElseThrow().scanned()).isEqualTo(50);
        ChainScanner.PollResult last = scanner.poll("TRON").orElseThrow();
        assertThat(last.lastBlock()).isEqualTo(chain.head());
    }
}
