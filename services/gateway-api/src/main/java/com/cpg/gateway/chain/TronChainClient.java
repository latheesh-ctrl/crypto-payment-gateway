package com.cpg.gateway.chain;

import com.cpg.gateway.deposit.ObservedTransfer;
import com.cpg.gateway.wallet.AddressCodec;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Tron through the full-node HTTP API (java-tron / TronGrid).
 *
 * <p>Native TRX: successful {@code TransferContract} transactions (output index 0). TRC-20: Transfer logs of our
 * token contracts in successful transactions (output index = position of the log in its transaction). TRC-10
 * assets and TRX moved by contract-internal calls are not deposits in v1.
 */
final class TronChainClient implements ChainClient {

    private final String chain;
    private final RpcTransport http;

    TronChainClient(String chain, RpcTransport http) {
        this.chain = chain;
        this.http = http;
    }

    @Override
    public String chain() {
        return chain;
    }

    @Override
    public long head() {
        return header(http.post("/wallet/getblock", Map.of("detail", false))).number();
    }

    @Override
    public BlockHeader header(long number) {
        BlockHeader header = header(http.post("/wallet/getblock", Map.of("id_or_num", Long.toString(number),
                "detail", false)));
        expect(number, header.number());
        return header;
    }

    @Override
    public ChainBlock block(long number, Set<String> tokenContracts, AddressFilter owned) {
        JsonNode block = http.post("/wallet/getblockbynum", Map.of("num", number));
        BlockHeader header = header(block);
        expect(number, header.number());

        List<ObservedTransfer> candidates = new ArrayList<>();
        Set<String> txIds = new HashSet<>();
        for (JsonNode tx : block.path("transactions")) {
            txIds.add(tx.path("txID").asString());
            ObservedTransfer native_ = nativeTransfer(tx, number, header.hash());
            if (native_ != null) {
                candidates.add(native_);
            }
        }
        if (!tokenContracts.isEmpty() && !txIds.isEmpty()) {
            JsonNode infos = http.post("/wallet/gettransactioninfobyblocknum", Map.of("num", number));
            for (JsonNode info : infos) {
                String id = info.path("id").asString();
                // Both calls must describe the same block; a reorg between them means retry later.
                if (!txIds.contains(id) || info.path("blockNumber").asLong(-1) != number) {
                    throw new ChainRpcException("Tron block " + number + " changed while it was being read");
                }
                candidates.addAll(tokenTransfers(info, tokenContracts, number, header.hash()));
            }
        }

        Set<String> recipients = new HashSet<>();
        candidates.forEach(t -> recipients.add(t.toAddress()));
        Set<String> ours = recipients.isEmpty() ? Set.of() : owned.owned(recipients);
        return new ChainBlock(header, candidates.stream().filter(t -> ours.contains(t.toAddress())).toList());
    }

    @Override
    public List<ObservedTransfer> transfers(String txHash, String blockHashHint, Set<String> tokenContracts) {
        JsonNode info = http.post("/wallet/gettransactioninfobyid", Map.of("value", txHash));
        if (!info.has("blockNumber")) {
            return List.of();
        }
        long number = info.path("blockNumber").asLong();
        String blockHash = header(number).hash();
        List<ObservedTransfer> out = new ArrayList<>();
        JsonNode tx = http.post("/wallet/gettransactionbyid", Map.of("value", txHash));
        ObservedTransfer native_ = nativeTransfer(tx, number, blockHash);
        if (native_ != null) {
            out.add(native_);
        }
        out.addAll(tokenTransfers(info, tokenContracts, number, blockHash));
        return out;
    }

    private ObservedTransfer nativeTransfer(JsonNode tx, long number, String blockHash) {
        JsonNode contract = tx.path("raw_data").path("contract").path(0);
        if (!"TransferContract".equals(contract.path("type").asString())
                || !"SUCCESS".equals(tx.path("ret").path(0).path("contractRet").asString())) {
            return null;
        }
        JsonNode value = contract.path("parameter").path("value");
        BigInteger amount = BigInteger.valueOf(value.path("amount").asLong(0));
        if (amount.signum() <= 0) {
            return null;
        }
        return new ObservedTransfer(chain, tx.path("txID").asString(), 0,
                AddressCodec.tronFromHex(value.path("to_address").asString()), null, amount, number, blockHash);
    }

    private List<ObservedTransfer> tokenTransfers(JsonNode info, Set<String> tokenContracts, long number,
                                                  String blockHash) {
        if (!"SUCCESS".equals(info.path("receipt").path("result").asString())) {
            return List.of();
        }
        Map<String, String> byHex = new HashMap<>();
        tokenContracts.forEach(c -> byHex.put(hexOf(c), c));
        List<ObservedTransfer> out = new ArrayList<>();
        JsonNode logs = info.path("log");
        for (int i = 0; i < logs.size(); i++) {
            JsonNode log = logs.get(i);
            String contract = byHex.get(log.path("address").asString("").toLowerCase());
            JsonNode topics = log.path("topics");
            String data = log.path("data").asString("");
            if (contract == null || topics.size() != 3 || data.length() != 64
                    || !Hex.TRANSFER_TOPIC.equalsIgnoreCase(topics.get(0).asString())) {
                continue;
            }
            out.add(new ObservedTransfer(chain, info.path("id").asString(), i,
                    AddressCodec.tronFromHex(Hex.topicAddress(topics.get(2).asString())), contract, Hex.uint(data),
                    number, blockHash));
        }
        return out;
    }

    /** 20-byte account hex (no 41 prefix) of a Base58 contract address, as Tron logs report it. */
    private static String hexOf(String base58) {
        byte[] decoded = org.bitcoinj.base.Base58.decodeChecked(base58);
        return java.util.HexFormat.of().formatHex(decoded, 1, decoded.length);
    }

    private static BlockHeader header(JsonNode block) {
        JsonNode raw = block.path("block_header").path("raw_data");
        if (!block.has("blockID") || !raw.has("number")) {
            throw new ChainRpcException("Tron block not available");
        }
        return new BlockHeader(raw.path("number").asLong(), block.path("blockID").asString(),
                raw.path("parentHash").asString());
    }

    private static void expect(long asked, long got) {
        if (asked != got) {
            throw new ChainRpcException("asked for block " + asked + ", got " + got);
        }
    }
}
