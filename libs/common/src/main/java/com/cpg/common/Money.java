package com.cpg.common;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/**
 * An exact amount of one asset, held in the asset's smallest unit (satoshi, wei, sun, ...).
 *
 * <p>Amounts are never represented as floating point. {@link BigInteger} covers the full uint256 range
 * used by EVM tokens.
 */
public record Money(String assetId, BigInteger minorUnits) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(assetId, "assetId");
        Objects.requireNonNull(minorUnits, "minorUnits");
        if (assetId.isBlank()) {
            throw new IllegalArgumentException("assetId must not be blank");
        }
    }

    public static Money of(String assetId, long minorUnits) {
        return new Money(assetId, BigInteger.valueOf(minorUnits));
    }

    public static Money zero(String assetId) {
        return new Money(assetId, BigInteger.ZERO);
    }

    /** Parses a human decimal string such as {@code "12.5"} using the asset's decimals. */
    public static Money parse(String assetId, String decimal, int decimals) {
        BigDecimal value = new BigDecimal(decimal);
        BigDecimal scaled = value.movePointRight(decimals);
        try {
            return new Money(assetId, scaled.toBigIntegerExact());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "%s has more than %d decimal places".formatted(decimal, decimals), e);
        }
    }

    public String toDecimalString(int decimals) {
        return new BigDecimal(minorUnits, decimals).stripTrailingZeros().toPlainString();
    }

    public Money plus(Money other) {
        requireSameAsset(other);
        return new Money(assetId, minorUnits.add(other.minorUnits));
    }

    public Money minus(Money other) {
        requireSameAsset(other);
        return new Money(assetId, minorUnits.subtract(other.minorUnits));
    }

    public boolean isPositive() {
        return minorUnits.signum() > 0;
    }

    public boolean isNegative() {
        return minorUnits.signum() < 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameAsset(other);
        return minorUnits.compareTo(other.minorUnits);
    }

    private void requireSameAsset(Money other) {
        if (!assetId.equals(other.assetId)) {
            throw new IllegalArgumentException("asset mismatch: " + assetId + " vs " + other.assetId);
        }
    }
}
