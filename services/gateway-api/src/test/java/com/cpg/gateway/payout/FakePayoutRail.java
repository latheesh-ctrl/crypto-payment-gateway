package com.cpg.gateway.payout;

import com.cpg.gateway.chain.ChainRpcException;
import com.cpg.gateway.payout.evm.EvmTransactions;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * In-memory chain for payout tests. Transactions are really built and signed (EIP-155, a throwaway key); the
 * "chain" is a mempool and a map of receipts the test controls.
 */
public final class FakePayoutRail implements PayoutRail {

    private static final String TEST_KEY = "0x" + "4c".repeat(32);

    private final String chain;
    private final DevKeySigner signer = new DevKeySigner(TEST_KEY);
    private final Map<String, TxStatus> chainState = new HashMap<>();
    public final List<String> broadcasts = new ArrayList<>();
    public final List<Long> signedNonces = new ArrayList<>();
    public volatile long pendingNonce;
    public volatile long minedNonce;
    public volatile long head = 1_000;
    public volatile String notReady;
    public volatile boolean down;
    /** Result for the next broadcast only; ACCEPTED when null. */
    public volatile Broadcast nextBroadcast;

    FakePayoutRail(String chain) {
        this.chain = chain;
    }

    public synchronized void reset() {
        chainState.clear();
        broadcasts.clear();
        signedNonces.clear();
        pendingNonce = 0;
        minedNonce = 0;
        head = 1_000;
        notReady = null;
        down = false;
        nextBroadcast = null;
    }

    private void reachable() {
        if (down) {
            throw new ChainRpcException("node unreachable");
        }
    }

    /** Puts the transaction in a block with the given outcome. */
    public synchronized void mine(String txHash, long block, boolean success, BigInteger fee) {
        chainState.put(txHash, new TxStatus(success ? State.SUCCEEDED : State.REVERTED, block, fee));
    }

    /** The chain and mempool forget the transaction (dropped, or reorganised out). */
    public synchronized void forget(String txHash) {
        chainState.remove(txHash);
    }

    @Override
    public String chain() {
        return chain;
    }

    @Override
    public String fromAddress() {
        return signer.address();
    }

    @Override
    public long pendingNonce() {
        reachable();
        return pendingNonce;
    }

    @Override
    public long minedNonce() {
        reachable();
        return minedNonce;
    }

    @Override
    public long head() {
        reachable();
        return head;
    }

    @Override
    public synchronized Signed sign(Transfer transfer, long nonce) {
        reachable();
        if (notReady != null) {
            throw new NotReady(notReady);
        }
        EvmTransactions.Unsigned tx = transfer.tokenContract() == null
                ? EvmTransactions.nativeTransfer(1, nonce, BigInteger.TEN.pow(9), 21_000, transfer.to(), transfer.amount())
                : EvmTransactions.tokenTransfer(1, nonce, BigInteger.TEN.pow(9), 100_000, transfer.tokenContract(),
                        transfer.to(), transfer.amount());
        EvmTransactions.Signed signed = EvmTransactions.sign(tx, signer);
        signedNonces.add(nonce);
        return new Signed(signed.txHash(), signed.rawTx());
    }

    @Override
    public synchronized Broadcast broadcast(String rawTx) {
        reachable();
        broadcasts.add(rawTx);
        Broadcast result = nextBroadcast == null ? new Broadcast(BroadcastResult.ACCEPTED, null) : nextBroadcast;
        nextBroadcast = null;
        if (result.result() == BroadcastResult.ACCEPTED) {
            String hash = "0x" + java.util.HexFormat.of().formatHex(
                    EvmTransactions.keccak256(java.util.HexFormat.of().parseHex(rawTx.substring(2))));
            chainState.putIfAbsent(hash, TxStatus.pending());
        }
        return result;
    }

    @Override
    public synchronized TxStatus status(String txHash) {
        reachable();
        return chainState.getOrDefault(txHash, TxStatus.notFound());
    }

    /** Rails per chain for one test context. */
    public static final class Registry {

        private final Map<String, FakePayoutRail> rails = new ConcurrentHashMap<>();

        public FakePayoutRail get(String chain) {
            return rails.computeIfAbsent(chain, FakePayoutRail::new);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        Registry fakePayoutRails() {
            return new Registry();
        }

        @Bean
        @Primary
        PayoutRailFactory fakePayoutRailFactory(Registry rails) {
            return (chain, family, chainId, config) -> "evm".equals(family) ? rails.get(chain) : null;
        }
    }
}
