package com.cpg.gateway.chain;

import com.cpg.gateway.deposit.ObservedTransfer;
import com.cpg.gateway.wallet.AddressCodec;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * Ethereum, BNB Smart Chain and Arbitrum through standard JSON-RPC.
 *
 * <p>Native coin: top-level transactions with value to our address whose receipt succeeded (output index 0).
 * Tokens: Transfer logs of our token contracts, fetched with the EIP-234 {@code blockHash} filter so the logs are
 * bound to exactly the block whose header we checked (output index = the log's block-level index). Value moved by
 * contract-internal calls is not seen; that needs a trace API and is out of scope for v1.
 */
final class EvmChainClient implements ChainClient {

    private final String chain;
    private final RpcTransport rpc;

    EvmChainClient(String chain, RpcTransport rpc) {
        this.chain = chain;
        this.rpc = rpc;
    }

    @Override
    public String chain() {
        return chain;
    }

    @Override
    public long head() {
        return Hex.quantity(rpc.call("eth_blockNumber").asString());
    }

    @Override
    public BlockHeader header(long number) {
        return header(blockByNumber(number, false), number);
    }

    @Override
    public ChainBlock block(long number, Set<String> tokenContracts, AddressFilter owned) {
        JsonNode block = blockByNumber(number, true);
        BlockHeader header = header(block, number);

        record Native(String tx, String to, BigInteger value) {
        }
        List<Native> natives = new ArrayList<>();
        for (JsonNode tx : block.path("transactions")) {
            JsonNode to = tx.get("to");
            BigInteger value = Hex.uint(tx.path("value").asString("0x0"));
            if (to != null && !to.isNull() && value.signum() > 0) {
                natives.add(new Native(tx.path("hash").asString(), AddressCodec.normalizeEvm(to.asString()), value));
            }
        }

        List<Log> logs = new ArrayList<>();
        if (!tokenContracts.isEmpty()) {
            JsonNode result = rpc.call("eth_getLogs", Map.of(
                    "blockHash", header.hash(),
                    "address", List.copyOf(tokenContracts),
                    "topics", List.of("0x" + Hex.TRANSFER_TOPIC)));
            Set<String> contracts = lower(tokenContracts);
            for (JsonNode log : result) {
                Log parsed = parseLog(log, contracts, header.hash());
                if (parsed != null) {
                    logs.add(parsed);
                }
            }
        }

        Set<String> candidates = new HashSet<>();
        natives.forEach(n -> candidates.add(n.to()));
        logs.forEach(l -> candidates.add(l.to()));
        Set<String> ours = candidates.isEmpty() ? Set.of() : owned.owned(candidates);

        List<ObservedTransfer> transfers = new ArrayList<>();
        for (Native n : natives) {
            if (ours.contains(n.to()) && succeeded(n.tx())) {
                transfers.add(new ObservedTransfer(chain, n.tx(), 0, n.to(), null, n.value(), number, header.hash()));
            }
        }
        for (Log l : logs) {
            if (ours.contains(l.to())) {
                transfers.add(new ObservedTransfer(chain, l.tx(), l.index(), l.to(), l.contract(), l.amount(), number,
                        header.hash()));
            }
        }
        return new ChainBlock(header, transfers);
    }

    @Override
    public List<ObservedTransfer> transfers(String txHash, String blockHashHint, Set<String> tokenContracts) {
        JsonNode receipt = rpc.call("eth_getTransactionReceipt", txHash);
        if (receipt.isNull() || receipt.path("blockHash").isNull() || !"0x1".equals(receipt.path("status").asString())) {
            return List.of();
        }
        JsonNode tx = rpc.call("eth_getTransactionByHash", txHash);
        if (tx.isNull()) {
            return List.of();
        }
        long number = Hex.quantity(receipt.path("blockNumber").asString());
        String blockHash = receipt.path("blockHash").asString();
        List<ObservedTransfer> out = new ArrayList<>();
        JsonNode to = tx.get("to");
        BigInteger value = Hex.uint(tx.path("value").asString("0x0"));
        if (to != null && !to.isNull() && value.signum() > 0) {
            out.add(new ObservedTransfer(chain, txHash, 0, AddressCodec.normalizeEvm(to.asString()), null, value, number,
                    blockHash));
        }
        Set<String> contracts = lower(tokenContracts);
        for (JsonNode log : receipt.path("logs")) {
            Log parsed = parseLog(log, contracts, blockHash);
            if (parsed != null) {
                out.add(new ObservedTransfer(chain, txHash, parsed.index(), parsed.to(), parsed.contract(),
                        parsed.amount(), number, blockHash));
            }
        }
        return out;
    }

    private record Log(String tx, int index, String contract, String to, BigInteger amount) {
    }

    /** A well-formed ERC-20 Transfer from one of our contracts, or null. */
    private static Log parseLog(JsonNode log, Set<String> contracts, String expectedBlockHash) {
        if (log.path("removed").asBoolean(false)) {
            return null;
        }
        String contract = log.path("address").asString("");
        if (!contracts.contains(contract.toLowerCase(Locale.ROOT))) {
            return null;
        }
        JsonNode topics = log.path("topics");
        // ERC-721 Transfer shares the signature but indexes the token id (4 topics); only ERC-20 has 3.
        if (topics.size() != 3 || !Hex.strip(topics.get(0).asString()).equalsIgnoreCase(Hex.TRANSFER_TOPIC)) {
            return null;
        }
        String data = Hex.strip(log.path("data").asString(""));
        if (data.length() != 64) {
            return null;
        }
        if (!expectedBlockHash.equalsIgnoreCase(log.path("blockHash").asString(expectedBlockHash))) {
            throw new ChainRpcException("log from block " + log.path("blockHash").asString() + ", expected "
                    + expectedBlockHash);
        }
        return new Log(log.path("transactionHash").asString(),
                (int) Hex.quantity(log.path("logIndex").asString()),
                AddressCodec.normalizeEvm(contract),
                AddressCodec.normalizeEvm(Hex.topicAddress(topics.get(2).asString())),
                Hex.uint(data));
    }

    private boolean succeeded(String txHash) {
        JsonNode receipt = rpc.call("eth_getTransactionReceipt", txHash);
        return !receipt.isNull() && "0x1".equals(receipt.path("status").asString());
    }

    private JsonNode blockByNumber(long number, boolean fullTransactions) {
        JsonNode block = rpc.call("eth_getBlockByNumber", Hex.quantity(number), fullTransactions);
        if (block.isNull()) {
            throw new ChainRpcException(chain + " block " + number + " not available on " + rpc);
        }
        return block;
    }

    private BlockHeader header(JsonNode block, long expected) {
        long number = Hex.quantity(block.path("number").asString());
        if (number != expected) {
            throw new ChainRpcException("asked for block " + expected + ", got " + number);
        }
        return new BlockHeader(number, block.path("hash").asString(), block.path("parentHash").asString());
    }

    private static Set<String> lower(Set<String> values) {
        return values.stream().map(v -> v.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }
}
