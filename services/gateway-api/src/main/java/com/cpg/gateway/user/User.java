package com.cpg.gateway.user;

import java.time.Instant;
import java.util.Map;

/** An end user (player, customer) of one merchant, identified to that merchant by {@code externalRef}. */
public record User(String id, String merchantId, String externalRef, String status, String kycStatus,
                   Map<String, Object> metadata, Instant createdAt) {
}
