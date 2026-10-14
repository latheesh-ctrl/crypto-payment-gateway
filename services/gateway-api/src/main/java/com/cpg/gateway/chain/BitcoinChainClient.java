package com.cpg.gateway.chain;

import com.cpg.gateway.deposit.ObservedTransfer;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Bitcoin through Bitcoin Core JSON-RPC. Each output paying one of our addresses is a transfer (output index =
 * vout). Coinbase outputs are skipped: they cannot be spent for 100 blocks and are never customer deposits.
 */
final class BitcoinChainClient implements ChainClient {

    private final String chain;
    private final RpcTransport rpc;

    BitcoinChainClient(String chain, RpcTransport rpc) {
        this.chain = chain;
        this.rpc = rpc;
    }

    @Override
    public String chain() {
        return chain;
    }

    @Override
    public long head() {
        return rpc.call("getblockcount").asLong();
    }

    @Override
    public BlockHeader header(long number) {
        String hash = rpc.call("getblockhash", number).asString();
        JsonNode header = rpc.call("getblockheader", hash);
        return header(header, number);
    }

    @Override
    public ChainBlock block(long number, Set<String> tokenContracts, AddressFilter owned) {
        String hash = rpc.call("getblockhash", number).asString();
        JsonNode block = rpc.call("getblock", hash, 2);
        BlockHeader header = header(block, number);
        List<ObservedTransfer> candidates = new ArrayList<>();
        for (JsonNode tx : block.path("tx")) {
            candidates.addAll(outputs(tx, number, header.hash()));
        }
        Set<String> recipients = new HashSet<>();
        candidates.forEach(t -> recipients.add(t.toAddress()));
        Set<String> ours = recipients.isEmpty() ? Set.of() : owned.owned(recipients);
        return new ChainBlock(header, candidates.stream().filter(t -> ours.contains(t.toAddress())).toList());
    }

    @Override
    public List<ObservedTransfer> transfers(String txHash, String blockHashHint, Set<String> tokenContracts) {
        if (blockHashHint == null) {
            return List.of();
        }
        JsonNode header = rpc.call("getblockheader", blockHashHint);
        // confirmations is -1 when the block is no longer on the best chain.
        if (header.isNull() || header.path("confirmations").asLong(-1) < 1) {
            return List.of();
        }
        JsonNode tx;
        try {
            tx = rpc.call("getrawtransaction", txHash, true, blockHashHint);
        } catch (ChainRpcException e) {
            if (e.getMessage().contains("No such transaction")) {
                return List.of();
            }
            throw e;
        }
        return outputs(tx, header.path("height").asLong(), blockHashHint);
    }

    private List<ObservedTransfer> outputs(JsonNode tx, long number, String blockHash) {
        if (tx.path("vin").path(0).has("coinbase")) {
            return List.of();
        }
        List<ObservedTransfer> out = new ArrayList<>();
        for (JsonNode vout : tx.path("vout")) {
            String address = vout.path("scriptPubKey").path("address").asString(null);
            if (address == null) {
                continue; // OP_RETURN, bare multisig and other outputs without an address
            }
            BigInteger sats = vout.path("value").decimalValue().movePointRight(8).toBigIntegerExact();
            if (sats.signum() > 0) {
                out.add(new ObservedTransfer(chain, tx.path("txid").asString(), vout.path("n").asInt(), address, null,
                        sats, number, blockHash));
            }
        }
        return out;
    }

    private static BlockHeader header(JsonNode block, long expected) {
        long height = block.path("height").asLong(-1);
        if (height != expected) {
            throw new ChainRpcException("asked for block " + expected + ", got " + height);
        }
        // The genesis block has no previous block.
        return new BlockHeader(height, block.path("hash").asString(), block.path("previousblockhash").asString(""));
    }
}
