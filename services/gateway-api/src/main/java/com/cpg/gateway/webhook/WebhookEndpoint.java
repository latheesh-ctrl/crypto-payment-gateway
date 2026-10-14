package com.cpg.gateway.webhook;

import java.time.Instant;
import java.util.List;

public record WebhookEndpoint(String id, String url, List<String> eventTypes, String status, Instant createdAt) {
}
