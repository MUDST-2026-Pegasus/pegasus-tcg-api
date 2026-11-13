package com.pegasus.pegasustcgapi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FixedWindowRateLimiterTest {
    private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    @Test
    void rejectsNonPositiveConfiguration() {
        assertThatIllegalArgumentException().isThrownBy(() -> new FixedWindowRateLimiter(0, clock));
        assertThatIllegalArgumentException().isThrownBy(() -> new FixedWindowRateLimiter(-1, clock));
    }

    @Test
    void concurrentCallsCannotExceedBudget() throws Exception {
        var limiter = new FixedWindowRateLimiter(30, clock);
        try (var executor = Executors.newFixedThreadPool(12)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 300; i++) results.add(executor.submit(() -> limiter.allow(List.of("ip:a"))));
            int allowed = 0;
            for (var result : results) if (result.get()) allowed++;
            assertThat(allowed).isEqualTo(30);
        }
    }

    @Test
    void resetsAtTheExactWindowBoundaryAndDeniedTriesDoNotExtendIt() {
        Clock mutable = mock(Clock.class);
        when(mutable.millis()).thenReturn(0L);
        var limiter = new FixedWindowRateLimiter(1, mutable);
        assertThat(limiter.allow(List.of("ip:a"))).isTrue();
        when(mutable.millis()).thenReturn(59_999L);
        assertThat(limiter.allow(List.of("ip:a"))).isFalse();
        when(mutable.millis()).thenReturn(60_000L);
        assertThat(limiter.allow(List.of("ip:a"))).isTrue();
    }

    @Test
    void addressChurnIsBoundedAndExpiredEntriesFreeCapacity() {
        Clock mutable = mock(Clock.class);
        when(mutable.millis()).thenReturn(0L);
        var limiter = new FixedWindowRateLimiter(2, mutable);
        for (int i = 0; i < 10_000; i++) assertThat(limiter.allow(List.of("ip:" + i))).isTrue();
        assertThat(limiter.allow(List.of("new"))).isFalse();
        assertThat(limiter.allow(List.of("ip:0"))).isTrue();
        when(mutable.millis()).thenReturn(60_000L);
        assertThat(limiter.allow(List.of("new"))).isTrue();
    }
}
