package com.cpg.gateway.chain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cpg.gateway.chain.FakeChain.Payment;
import com.cpg.gateway.deposit.Deposit;
import com.cpg.gateway.deposit.TransferVerifier.Result;
import java.math.BigInteger;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChainTransferVerifierTest {

    FakeChain chain = new FakeChain("TRON");
    ChainClient verifier = chain.client(true);

    @BeforeEach
    void setUp() {
        chain.reset(100);
    }

    static Deposit deposit(String tx, int index, String to, BigInteger amount, long block, String hash) {
        return new Deposit("dep_1", "mer_1", "user", "usr_1", "TRON", to, "USDT_TRON", "TUSDT", amount,
                BigInteger.ZERO, tx, index, block, hash, 0, 19, "confirming", null, false, Instant.now(), null);
    }

    private String hashOf(long block) {
        return chain.client(false).header(block).hash();
    }

    @Test
    void confirmsOnlyAMatchingTransferPastFinality() {
        long block = chain.mine(new Payment("tx1", 2, "TOurs", "TUSDT", BigInteger.TEN));
        Deposit d = deposit("tx1", 2, "TOurs", BigInteger.TEN, block, hashOf(block));
        assertThat(ChainTransferVerifier.check(verifier, d)).isEqualTo(Result.UNAVAILABLE);
        chain.mine(18);
        assertThat(ChainTransferVerifier.check(verifier, d)).isEqualTo(Result.CONFIRMED);

        assertThat(ChainTransferVerifier.check(verifier, deposit("tx1", 2, "TOther", BigInteger.TEN, block, hashOf(block))))
                .isEqualTo(Result.MISMATCH);
        assertThat(ChainTransferVerifier.check(verifier, deposit("tx1", 3, "TOurs", BigInteger.TEN, block, hashOf(block))))
                .isEqualTo(Result.MISMATCH);
        assertThat(ChainTransferVerifier.check(verifier, deposit("tx1", 2, "TOurs", BigInteger.TWO, block, hashOf(block))))
                .isEqualTo(Result.MISMATCH);
        // Reported in a block the provider doesn't consider canonical: wait for the scanner to settle it.
        assertThat(ChainTransferVerifier.check(verifier, deposit("tx1", 2, "TOurs", BigInteger.TEN, block, "other")))
                .isEqualTo(Result.UNAVAILABLE);
    }

    @Test
    void unknownTransactionIsAMismatchOnlyOncePastFinality() {
        Deposit forged = deposit("forged", 0, "TOurs", BigInteger.TEN, chain.head(), "h");
        assertThat(ChainTransferVerifier.check(verifier, forged)).isEqualTo(Result.UNAVAILABLE);
        chain.mine(25);
        assertThat(ChainTransferVerifier.check(verifier, forged)).isEqualTo(Result.MISMATCH);
    }
}
