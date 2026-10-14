package com.cpg.gateway.webhook;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the dispatch and delivery loops. Disabled in tests, which drive them directly. */
@Component
@ConditionalOnProperty(name = "gateway.webhooks.scheduler-enabled", havingValue = "true", matchIfMissing = true)
class WebhookScheduler {

    private final WebhookDispatcher dispatcher;
    private final WebhookSender sender;

    WebhookScheduler(WebhookDispatcher dispatcher, WebhookSender sender) {
        this.dispatcher = dispatcher;
        this.sender = sender;
    }

    @Scheduled(fixedDelayString = "PT1S")
    void dispatch() {
        while (dispatcher.dispatchBatch(100) == 100) {
            // drain the backlog
        }
    }

    @Scheduled(fixedDelayString = "PT1S")
    void send() {
        sender.sendDue(50);
    }
}
