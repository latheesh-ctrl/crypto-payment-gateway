package com.cpg.gateway.chain;

import com.cpg.gateway.deposit.Deposit;
import com.cpg.gateway.deposit.ObservedTransfer;
import com.cpg.gateway.deposit.TransferVerifier;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Re-checks a deposit on every configured verification provider before it is credited. All of them must see the
 * transfer, in the same block, with the same recipient, token and amount, and past the finality threshold by
 * their own head. Any disagreement sends the deposit to review; anything a provider cannot answer yet (an error,
 * lag, a block it doesn't have) means "try again later". With no verification providers nothing is credited.
 */
@Component
public class ChainTransferVerifier implements TransferVerifier {

    private static final Logger log = LoggerFactory.getLogger(ChainTransferVerifier.class);

    private final ChainClients clients;

    public ChainTransferVerifier(ChainClients clients) {
        this.clients = clients;
    }

    @Override
    public Result verify(Deposit deposit) {
        List<ChainClient> sources;
        try {
            sources = clients.verifiers(deposit.chain());
        } catch (RuntimeException e) {
            log.error("Verification providers for {} are misconfigured", deposit.chain(), e);
            return Result.UNAVAILABLE;
        }
        if (sources.isEmpty() || deposit.blockNumber() == null) {
            return Result.UNAVAILABLE;
        }
        Result overall = Result.CONFIRMED;
        for (ChainClient source : sources) {
            Result r;
            try {
                r = check(source, deposit);
            } catch (RuntimeException e) {
                log.warn("Verification of deposit {} on {} unavailable: {}", deposit.id(), source, e.getMessage());
                r = Result.UNAVAILABLE;
            }
            if (r == Result.MISMATCH) {
                log.error("Deposit {} contradicted by verification provider {}", deposit.id(), source);
                return Result.MISMATCH;
            }
            if (r == Result.UNAVAILABLE) {
                overall = Result.UNAVAILABLE;
            }
        }
        return overall;
    }

    static Result check(ChainClient source, Deposit d) {
        Set<String> contracts = d.tokenContract() == null ? Set.of() : Set.of(d.tokenContract());
        List<ObservedTransfer> seen = source.transfers(d.txHash(), d.blockHash(), contracts);
        long head = source.head();
        boolean pastFinality = head - d.blockNumber() + 1 >= d.requiredConfirmations();
        if (seen.isEmpty()) {
            // A provider past finality that has no trace of the transfer contradicts it; otherwise it may lag.
            return pastFinality ? Result.MISMATCH : Result.UNAVAILABLE;
        }
        if (seen.stream().anyMatch(t -> t.blockHash() == null || !t.blockHash().equalsIgnoreCase(d.blockHash()))) {
            return Result.UNAVAILABLE; // a reorganisation in progress; the scanner settles which block is canonical
        }
        Optional<ObservedTransfer> match = seen.stream().filter(t -> t.outputIndex() == d.outputIndex()).findFirst();
        if (match.isEmpty()) {
            return Result.MISMATCH;
        }
        ObservedTransfer t = match.get();
        if (!sameAddress(t.toAddress(), d.address()) || !sameAddress(t.tokenContract(), d.tokenContract())
                || !t.amount().equals(d.amount())) {
            return Result.MISMATCH;
        }
        if (!Objects.equals(t.blockNumber(), d.blockNumber())) {
            return Result.UNAVAILABLE;
        }
        return pastFinality ? Result.CONFIRMED : Result.UNAVAILABLE;
    }

    /** EVM hex addresses compare case-insensitively; Base58 and bech32 addresses compare exactly. */
    private static boolean sameAddress(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.startsWith("0x") && b.startsWith("0x") ? a.equalsIgnoreCase(b) : a.equals(b);
    }
}
