package com.cpg.gateway.payout;

import com.cpg.gateway.chain.ChainRpcException;
import com.cpg.gateway.chain.Hex;
import com.cpg.gateway.chain.RpcTransport;
import com.cpg.gateway.payout.evm.EvmTransactions;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Payouts on Ethereum, BNB Smart Chain and Arbitrum through standard JSON-RPC, as legacy EIP-155 transactions
 * signed by a {@link TransactionSigner}.
 */
final class EvmPayoutRail implements PayoutRail {

    private static final long NATIVE_GAS_LIMIT = 21_000;
    /** {@code bytes4(keccak256("balanceOf(address)"))}. */
    private static final String BALANCE_OF = "0x70a08231";

    private final String chain;
    private final long chainId;
    private final RpcTransport rpc;
    private final TransactionSigner signer;
    private final PayoutProperties.Chain config;

    EvmPayoutRail(String chain, Long chainId, RpcTransport rpc, TransactionSigner signer, PayoutProperties.Chain config) {
        if (chainId == null) {
            throw new IllegalStateException(chain + " has no EVM chain ID");
        }
        this.chain = chain;
        this.chainId = chainId;
        this.rpc = rpc;
        this.signer = signer;
        this.config = config;
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
        return Hex.quantity(rpc.call("eth_getTransactionCount", signer.address(), "pending").asString());
    }

    @Override
    public long minedNonce() {
        return Hex.quantity(rpc.call("eth_getTransactionCount", signer.address(), "latest").asString());
    }

    @Override
    public long head() {
        return Hex.quantity(rpc.call("eth_blockNumber").asString());
    }

    @Override
    public Signed sign(Transfer transfer, long nonce) {
        BigInteger suggested = Hex.uint(rpc.call("eth_gasPrice").asString());
        BigInteger gasPrice = suggested.multiply(BigInteger.valueOf(config.gasPricePercent())).divide(BigInteger.valueOf(100));
        BigInteger cap = config.maxGasPriceWei();
        if (cap != null && gasPrice.compareTo(cap) > 0) {
            if (suggested.compareTo(cap) > 0) {
                throw new NotReady("network gas price " + suggested + " wei is above the cap of " + cap);
            }
            gasPrice = cap;
        }
        long gasLimit = transfer.tokenContract() == null ? NATIVE_GAS_LIMIT : config.tokenGasLimit();
        BigInteger gasCost = gasPrice.multiply(BigInteger.valueOf(gasLimit));
        BigInteger nativeBalance = Hex.uint(rpc.call("eth_getBalance", signer.address(), "latest").asString());

        EvmTransactions.Unsigned tx;
        if (transfer.tokenContract() == null) {
            if (nativeBalance.compareTo(transfer.amount().add(gasCost)) < 0) {
                throw new NotReady("hot wallet " + signer.address() + " cannot cover the transfer and gas");
            }
            tx = EvmTransactions.nativeTransfer(chainId, nonce, gasPrice, gasLimit, transfer.to(), transfer.amount());
        } else {
            if (nativeBalance.compareTo(gasCost) < 0) {
                throw new NotReady("hot wallet " + signer.address() + " cannot cover gas");
            }
            if (tokenBalance(transfer.tokenContract()).compareTo(transfer.amount()) < 0) {
                throw new NotReady("hot wallet " + signer.address() + " token balance is too low");
            }
            tx = EvmTransactions.tokenTransfer(chainId, nonce, gasPrice, gasLimit, transfer.tokenContract(),
                    transfer.to(), transfer.amount());
        }
        EvmTransactions.Signed signed = EvmTransactions.sign(tx, signer);
        return new Signed(signed.txHash(), signed.rawTx());
    }

    private BigInteger tokenBalance(String contract) {
        String owner = signer.address().substring(2).toLowerCase(Locale.ROOT);
        JsonNode result = rpc.call("eth_call", Map.of("to", contract, "data", BALANCE_OF + "0".repeat(24) + owner),
                "latest");
        String hex = result.asString();
        return "0x".equals(hex) ? BigInteger.ZERO : Hex.uint(hex);
    }

    @Override
    public Broadcast broadcast(String rawTx) {
        try {
            rpc.call("eth_sendRawTransaction", rawTx);
            return new Broadcast(BroadcastResult.ACCEPTED, null);
        } catch (ChainRpcException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("already known") || lower.contains("alreadyknown") || lower.contains("known transaction")) {
                return new Broadcast(BroadcastResult.ALREADY_KNOWN, null);
            }
            if (lower.contains("nonce too low") || lower.contains("oldnonce")
                    || lower.contains("replacement transaction underpriced")) {
                return new Broadcast(BroadcastResult.NONCE_USED, message);
            }
            return new Broadcast(BroadcastResult.REJECTED, message);
        }
    }

    @Override
    public TxStatus status(String txHash) {
        JsonNode receipt = rpc.call("eth_getTransactionReceipt", txHash);
        if (receipt == null || receipt.isNull()) {
            JsonNode tx = rpc.call("eth_getTransactionByHash", txHash);
            return tx == null || tx.isNull() ? TxStatus.notFound() : TxStatus.pending();
        }
        long block = Hex.quantity(receipt.path("blockNumber").asString());
        BigInteger gasUsed = Hex.uint(receipt.path("gasUsed").asString());
        String price = receipt.path("effectiveGasPrice").asString(null);
        if (price == null) {
            price = rpc.call("eth_getTransactionByHash", txHash).path("gasPrice").asString();
        }
        BigInteger fee = gasUsed.multiply(Hex.uint(price));
        boolean ok = "0x1".equals(receipt.path("status").asString());
        return new TxStatus(ok ? State.SUCCEEDED : State.REVERTED, block, fee);
    }

    @Override
    public String toString() {
        return chain + " payouts from " + signer.address();
    }
}
