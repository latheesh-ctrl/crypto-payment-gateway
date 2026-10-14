package com.cpg.gateway.chain;

import com.cpg.gateway.deposit.ObservedTransfer;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** In-memory chain for scanner tests: mine blocks, reorganise, and give verification providers their own view. */
public final class FakeChain {

    public record Payment(String txHash, int outputIndex, String to, String tokenContract, BigInteger amount) {
    }

    private record Block(long number, String hash, String parentHash, List<Payment> payments) {
    }

    private final String chain;
    private final List<Block> blocks = new ArrayList<>();
    private long base;
    /** Verification providers: reachable, and an optional rewrite of what they report. */
    public volatile boolean verifiersDown;
    public volatile UnaryOperator<ObservedTransfer> verifierView = UnaryOperator.identity();

    FakeChain(String chain) {
        this.chain = chain;
    }

    /** Starts a fresh chain at {@code height} with a few empty blocks. */
    public synchronized void reset(long height) {
        blocks.clear();
        base = height;
        verifiersDown = false;
        verifierView = UnaryOperator.identity();
        mine(6);
    }

    public synchronized long head() {
        return base + blocks.size() - 1;
    }

    public synchronized long mine(Payment... payments) {
        String parent = blocks.isEmpty() ? "genesis" : blocks.getLast().hash();
        long number = base + blocks.size();
        blocks.add(new Block(number, UUID.randomUUID().toString(), parent, List.of(payments)));
        return number;
    }

    public synchronized void mine(int empty) {
        for (int i = 0; i < empty; i++) {
            mine(new Payment[0]);
        }
    }

    /** Drops every block from {@code number} on; the next mined blocks form the new branch. */
    public synchronized void reorgFrom(long number) {
        while (head() >= number) {
            blocks.removeLast();
        }
    }

    private synchronized Block at(long number) {
        if (number < base || number > head()) {
            throw new ChainRpcException(chain + " has no block " + number);
        }
        return blocks.get((int) (number - base));
    }

    private ObservedTransfer observed(Block b, Payment p) {
        return new ObservedTransfer(chain, p.txHash(), p.outputIndex(), p.to(), p.tokenContract(), p.amount(),
                b.number(), b.hash());
    }

    ChainClient client(boolean verifier) {
        return new ChainClient() {
            private void reachable() {
                if (verifier && verifiersDown) {
                    throw new ChainRpcException("verifier down");
                }
            }

            @Override
            public String chain() {
                return chain;
            }

            @Override
            public long head() {
                reachable();
                return FakeChain.this.head();
            }

            @Override
            public BlockHeader header(long number) {
                reachable();
                Block b = at(number);
                return new BlockHeader(b.number(), b.hash(), b.parentHash());
            }

            @Override
            public ChainBlock block(long number, Set<String> tokenContracts, AddressFilter owned) {
                Block b = at(number);
                Set<String> to = new HashSet<>();
                b.payments().forEach(p -> to.add(p.to()));
                Set<String> ours = to.isEmpty() ? Set.of() : owned.owned(to);
                return new ChainBlock(new BlockHeader(b.number(), b.hash(), b.parentHash()),
                        b.payments().stream().filter(p -> ours.contains(p.to())).map(p -> observed(b, p)).toList());
            }

            @Override
            public List<ObservedTransfer> transfers(String txHash, String blockHashHint, Set<String> tokenContracts) {
                reachable();
                synchronized (FakeChain.this) {
                    for (Block b : blocks) {
                        List<ObservedTransfer> found = b.payments().stream().filter(p -> p.txHash().equals(txHash))
                                .map(p -> observed(b, p)).map(verifier ? verifierView : UnaryOperator.identity()).toList();
                        if (!found.isEmpty()) {
                            return found;
                        }
                    }
                }
                return List.of();
            }

            @Override
            public String toString() {
                return chain + (verifier ? " verifier" : " scanner");
            }
        };
    }

    /** All fake chains of a test context, by chain code. */
    public static final class Registry {

        private final Map<String, FakeChain> chains = new ConcurrentHashMap<>();

        public FakeChain get(String chain) {
            return chains.computeIfAbsent(chain, FakeChain::new);
        }
    }

    /** Replaces RPC clients: {@code fake://scan} is the scanning view, anything else a verification view. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        Registry fakeChains() {
            return new Registry();
        }

        @Bean
        @Primary
        ChainClientFactory fakeChainClientFactory(Registry chains) {
            return (chain, family, url, apiKey) -> chains.get(chain).client(!url.equals("fake://scan"));
        }
    }
}
