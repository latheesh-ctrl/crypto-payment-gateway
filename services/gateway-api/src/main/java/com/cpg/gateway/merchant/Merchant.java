package com.cpg.gateway.merchant;

import java.time.Instant;

public record Merchant(String id, String name, String email, String status, String kycStatus, String country,
                       Instant createdAt) {
}
