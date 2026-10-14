package com.cpg.gateway.deposit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retries independent verification for deposits that reached finality while the verifier was unavailable. */
@Component
@ConditionalOnProperty(name = "gateway.jobs.enabled", havingValue = "true", matchIfMissing = true)
class DepositVerificationRetrier {

    private static final Logger log = LoggerFactory.getLogger(DepositVerificationRetrier.class);

    private final DepositService deposits;

    DepositVerificationRetrier(DepositService deposits) {
        this.deposits = deposits;
    }

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT30S")
    void retry() {
        for (String id : deposits.awaitingVerification(100)) {
            try {
                deposits.retryVerification(id);
            } catch (RuntimeException e) {
                log.warn("Verification retry failed for deposit {}", id, e);
            }
        }
    }
}
