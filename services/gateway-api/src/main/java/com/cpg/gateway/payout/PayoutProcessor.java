package com.cpg.gateway.payout;

import com.cpg.gateway.chain.ChainRpcException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Moves one leased payout one step: sign and store, broadcast, or check the chain. Network calls happen outside
 * database transactions; each state change is its own short transaction in {@link PayoutService}. Anything the
 * chain or node does not answer clearly is retried later, and an outcome that stays unclear goes to
 * {@code needs_review} without ever signing a second transaction.
 */
@Component
public class PayoutProcessor {

    private static final Logger log = LoggerFactory.getLogger(PayoutProcessor.class);
    private static final Duration REVIEW_POLL = Duration.ofMinutes(5);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final PayoutService payouts;
    private final PayoutRails rails;
    private final PayoutProperties properties;

    public PayoutProcessor(PayoutService payouts, PayoutRails rails, PayoutProperties properties) {
        this.payouts = payouts;
        this.rails = rails;
        this.properties = properties;
    }

    /** Leases due payouts and advances each; returns how many were looked at. */
    public int runOnce(int batch) {
        int n = 0;
        for (String id : payouts.claimDue(batch, Duration.ofMinutes(2))) {
            process(id);
            n++;
        }
        return n;
    }

    public void process(String id) {
        Payout p = payouts.require(id);
        Optional<PayoutRail> rail = rails.rail(p.chain());
        if (rail.isEmpty()) {
            log.warn("No payout rail for {}; payout {} waits", p.chain(), id);
            payouts.reschedule(id, MAX_BACKOFF, true);
            return;
        }
        try {
            Duration next = switch (p.status()) {
                case "processing" -> sign(p, rail.get());
                case "signing" -> broadcast(p, rail.get());
                case "broadcast", "confirming", "needs_review" -> track(p, rail.get());
                default -> null;
            };
            payouts.reschedule(id, next == null ? properties.pollInterval() : next, false);
        } catch (PayoutRail.NotReady e) {
            log.info("Payout {} not signed yet: {}", id, e.getMessage());
            payouts.reschedule(id, backoff(p), true);
        } catch (ChainRpcException e) {
            log.warn("Payout {} step failed, will retry: {}", id, e.getMessage());
            payouts.reschedule(id, backoff(p), true);
        } catch (RuntimeException e) {
            log.error("Payout {} step failed unexpectedly, will retry", id, e);
            payouts.reschedule(id, backoff(p), true);
        }
    }

    private Duration sign(Payout p, PayoutRail rail) {
        long pending = rail.pendingNonce();
        if (!payouts.sign(p.id(), rail, pending)) {
            return null;
        }
        return broadcast(payouts.require(p.id()), rail);
    }

    private Duration broadcast(Payout p, PayoutRail rail) {
        PayoutService.Outbound out = payouts.outbound(p.id()).orElseThrow();
        PayoutRail.Broadcast result = rail.broadcast(out.rawTx());
        switch (result.result()) {
            case ACCEPTED, ALREADY_KNOWN -> payouts.broadcasted(p.id());
            case NONCE_USED -> {
                if (rail.status(out.txHash()).state() == PayoutRail.State.NOT_FOUND) {
                    payouts.broadcastFailed(p.id(), result.error());
                    payouts.flagForReview(p.id(), "nonce " + out.nonce() + " is already used by a different transaction");
                    return REVIEW_POLL;
                }
                payouts.broadcasted(p.id());
            }
            case REJECTED -> {
                payouts.broadcastFailed(p.id(), result.error());
                if (out.createdAt().plus(properties.stuckAfter()).isBefore(Instant.now())) {
                    payouts.flagForReview(p.id(), "the transaction could not be broadcast: " + result.error());
                }
                return backoff(p);
            }
        }
        return null;
    }

    private Duration track(Payout p, PayoutRail rail) {
        PayoutService.Outbound out = payouts.outbound(p.id()).orElse(null);
        if (out == null) {
            return REVIEW_POLL;
        }
        PayoutRail.TxStatus status = rail.status(out.txHash());
        switch (status.state()) {
            case SUCCEEDED, REVERTED -> {
                int confirmations = (int) Math.max(0, rail.head() - status.blockNumber() + 1);
                if (confirmations < p.requiredConfirmations()) {
                    payouts.confirming(p.id(), status.blockNumber(), confirmations);
                } else if (status.state() == PayoutRail.State.SUCCEEDED) {
                    payouts.complete(p.id(), status.blockNumber(), confirmations, status.networkFee());
                } else {
                    payouts.failReverted(p.id(), status.blockNumber(), status.networkFee());
                }
                return null;
            }
            case PENDING -> {
                payouts.reorged(p.id());
                if (stuck(out)) {
                    payouts.flagForReview(p.id(), "transaction still pending after " + properties.stuckAfter()
                            + "; its gas price may be too low");
                }
            }
            case NOT_FOUND -> {
                payouts.reorged(p.id());
                if (out.nonce() != null && rail.minedNonce() > out.nonce()) {
                    payouts.flagForReview(p.id(), "nonce " + out.nonce()
                            + " was used by a different transaction; this payout's transaction can never be mined");
                    return REVIEW_POLL;
                }
                if (out.lastBroadcastAt() == null
                        || out.lastBroadcastAt().plus(properties.rebroadcastEvery()).isBefore(Instant.now())) {
                    PayoutRail.Broadcast result = rail.broadcast(out.rawTx());
                    switch (result.result()) {
                        case ACCEPTED, ALREADY_KNOWN -> payouts.broadcasted(p.id());
                        default -> payouts.broadcastFailed(p.id(), result.error());
                    }
                }
                if (stuck(out)) {
                    payouts.flagForReview(p.id(), "transaction not mined " + properties.stuckAfter()
                            + " after it was first broadcast");
                }
            }
        }
        return payouts.require(p.id()).status().equals("needs_review") ? REVIEW_POLL : null;
    }

    private boolean stuck(PayoutService.Outbound out) {
        Instant since = out.firstBroadcastAt() == null ? out.createdAt() : out.firstBroadcastAt();
        return since.plus(properties.stuckAfter()).isBefore(Instant.now());
    }

    private Duration backoff(Payout p) {
        Duration base = properties.pollInterval();
        long factor = 1L << Math.min(6, Math.max(0, attempts(p)));
        Duration d = base.multipliedBy(factor);
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    private int attempts(Payout p) {
        return payouts.attempts(p.id());
    }
}
