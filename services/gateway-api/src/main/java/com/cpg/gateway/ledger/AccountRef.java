package com.cpg.gateway.ledger;

import java.util.Objects;

/** Identifies a ledger account by owner, asset and kind; the account row is created on first use. */
public record AccountRef(AccountKind kind, String ownerId, String assetId) {

    public static final String PLATFORM_OWNER = "platform";

    public AccountRef {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(assetId, "assetId");
        if (kind.owner() == AccountKind.Owner.PLATFORM && !PLATFORM_OWNER.equals(ownerId)) {
            throw new IllegalArgumentException(kind + " must be owned by the platform");
        }
    }

    public static AccountRef merchant(AccountKind kind, String merchantId, String assetId) {
        return new AccountRef(kind, merchantId, assetId);
    }

    public static AccountRef platform(AccountKind kind, String assetId) {
        return new AccountRef(kind, PLATFORM_OWNER, assetId);
    }
}
