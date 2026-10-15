package com.cpg.gateway.payout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background payout work: reserve liquidity for waiting payouts, and advance payouts in flight. */
@Component
@ConditionalOnProperty(name = "gateway.jobs.enabled", havingValue = "true", matchIfMissing = true)
class PayoutJobs {

    private static final Logger log = LoggerFactory.getLogger(PayoutJobs.class);

    private final PayoutService payouts;
    private final PayoutProcessor processor;

    PayoutJobs(PayoutService payouts, PayoutProcessor processor) {
        this.payouts = payouts;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "PT15S", initialDelayString = "PT15S")
    void reserveWaiting() {
        for (String id : payouts.awaitingLiquidity(500)) {
            try {
                payouts.tryReserve(id);
            } catch (RuntimeException e) {
                log.warn("Could not reserve liquidity for payout {}", id, e);
            }
        }
    }

    @Scheduled(fixedDelayString = "PT2S", initialDelayString = "PT10S")
    void advance() {
        while (processor.runOnce(20) == 20) {
            // keep draining while there is a full batch of work
        }
    }
}
