package com.cpg.gateway.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Token buckets per API key and route class: the sustained rate is the merchant's limit and short bursts up to
 * twice that are absorbed. Buckets live in this instance's memory, so the effective limit scales with the number
 * of instances; a shared store (Redis) replaces this when the API runs in several regions.
 */
@Component
public class RateLimiter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** @return 0 when the request may proceed, otherwise milliseconds until a token is available */
    public long tryAcquire(String bucketKey, int perSecond) {
        return buckets.computeIfAbsent(bucketKey, k -> new Bucket()).take(Math.max(1, perSecond), System.nanoTime());
    }

    private static final class Bucket {

        private double tokens = -1;
        private long last;

        synchronized long take(int perSecond, long now) {
            double capacity = 2.0 * perSecond;
            if (tokens < 0) {
                tokens = capacity;
            } else {
                tokens = Math.min(capacity, tokens + (now - last) / 1e9 * perSecond);
            }
            last = now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return (long) Math.ceil((1 - tokens) / perSecond * 1000);
        }
    }
}
