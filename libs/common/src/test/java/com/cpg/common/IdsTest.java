package com.cpg.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void producesPrefixedTwentySixCharUlids() {
        String id = Ids.next("mch");
        assertThat(id).matches("mch_[0-9A-HJKMNP-TV-Z]{26}");
    }

    @Test
    void idsSortByCreationTime() {
        String earlier = Ids.next("pi", Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        String later = Ids.next("pi", Clock.fixed(Instant.parse("2026-01-01T00:00:01Z"), ZoneOffset.UTC));
        assertThat(earlier.substring(0, 13)).isLessThan(later.substring(0, 13));
    }

    @Test
    void idsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(seen.add(Ids.next("tx"))).isTrue();
        }
    }

    @Test
    void rejectsBadPrefix() {
        assertThatThrownBy(() -> Ids.next("Bad")).isInstanceOf(IllegalArgumentException.class);
    }
}
