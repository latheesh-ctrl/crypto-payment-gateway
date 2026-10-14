package com.cpg.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void parsesDecimalIntoMinorUnits() {
        assertThat(Money.parse("USDT_TRON", "12.5", 6).minorUnits()).isEqualTo(BigInteger.valueOf(12_500_000));
        assertThat(Money.parse("ETH_ETHEREUM", "1", 18).minorUnits()).isEqualTo(new BigInteger("1000000000000000000"));
    }

    @Test
    void rejectsMorePrecisionThanAssetSupports() {
        assertThatThrownBy(() -> Money.parse("BTC_BITCOIN", "0.000000001", 8))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formatsBackToDecimal() {
        assertThat(Money.of("USDT_TRON", 12_500_000).toDecimalString(6)).isEqualTo("12.5");
    }

    @Test
    void refusesToMixAssets() {
        assertThatThrownBy(() -> Money.of("USDT_TRON", 1).plus(Money.of("USDT_ETHEREUM", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handlesUint256Range() {
        BigInteger max = BigInteger.TWO.pow(256).subtract(BigInteger.ONE);
        assertThat(new Money("X", max).plus(Money.zero("X")).minorUnits()).isEqualTo(max);
    }
}
