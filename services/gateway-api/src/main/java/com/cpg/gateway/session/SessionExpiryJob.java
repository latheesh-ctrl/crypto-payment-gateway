package com.cpg.gateway.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Moves open sessions past their expiry to {@code expired} and tells the merchant. */
@Component
@ConditionalOnProperty(name = "gateway.jobs.enabled", havingValue = "true", matchIfMissing = true)
class SessionExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(SessionExpiryJob.class);

    private final PaymentSessionService sessions;

    SessionExpiryJob(PaymentSessionService sessions) {
        this.sessions = sessions;
    }

    @Scheduled(fixedDelayString = "PT15S", initialDelayString = "PT15S")
    void expire() {
        for (String id : sessions.dueForExpiry(500)) {
            try {
                sessions.refresh(id);
            } catch (RuntimeException e) {
                log.warn("Could not expire payment session {}", id, e);
            }
        }
    }
}
