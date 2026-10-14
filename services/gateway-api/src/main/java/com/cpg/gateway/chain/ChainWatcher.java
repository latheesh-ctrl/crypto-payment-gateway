package com.cpg.gateway.chain;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Runs {@link ChainScanner} for every configured chain on its own schedule, so a slow or failing provider
 * delays only its own chain. Publishes {@code gateway.chain.head} and {@code gateway.chain.scanned} per chain;
 * the difference is the scan lag to alert on.
 */
@Component
@ConditionalOnProperty(name = "gateway.jobs.enabled", havingValue = "true", matchIfMissing = true)
class ChainWatcher {

    private static final Logger log = LoggerFactory.getLogger(ChainWatcher.class);

    private final ChainScanner scanner;
    private final ChainClients clients;
    private final MeterRegistry meters;
    private final Map<String, AtomicLong> heads = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> scanned = new ConcurrentHashMap<>();
    private ScheduledExecutorService executor;

    ChainWatcher(ChainScanner scanner, ChainClients clients, MeterRegistry meters) {
        this.scanner = scanner;
        this.clients = clients;
        this.meters = meters;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        var chains = clients.watchedChains();
        if (chains.isEmpty()) {
            log.info("No chains configured for scanning");
            return;
        }
        executor = Executors.newScheduledThreadPool(chains.size(), Thread.ofPlatform().name("chain-watcher-", 0).factory());
        for (String chain : chains) {
            AtomicLong head = heads.computeIfAbsent(chain, c -> new AtomicLong());
            AtomicLong last = scanned.computeIfAbsent(chain, c -> new AtomicLong());
            Gauge.builder("gateway.chain.head", head, AtomicLong::get).tag("chain", chain).register(meters);
            Gauge.builder("gateway.chain.scanned", last, AtomicLong::get).tag("chain", chain).register(meters);
            long interval = clients.config(chain).pollInterval().toMillis();
            executor.scheduleWithFixedDelay(() -> poll(chain, head, last), 0, interval, TimeUnit.MILLISECONDS);
            log.info("Watching {} every {} ms", chain, interval);
        }
    }

    private void poll(String chain, AtomicLong head, AtomicLong last) {
        try {
            scanner.poll(chain).ifPresent(r -> {
                head.set(r.head());
                last.set(r.lastBlock());
            });
        } catch (ChainRpcException e) {
            log.warn("{} scan skipped: {}", chain, e.getMessage());
        } catch (RuntimeException e) {
            log.error("{} scan failed", chain, e);
        }
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
