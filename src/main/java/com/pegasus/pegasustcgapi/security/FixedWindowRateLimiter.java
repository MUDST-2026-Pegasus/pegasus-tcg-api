package com.pegasus.pegasustcgapi.security;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A count of tries per key per minute, kept in this instance's memory. With several
 * replicas the cap is per replica, which still bounds a sweep.
 */
final class FixedWindowRateLimiter {

    static final long WINDOW_MILLIS = 60_000;
    /** Old windows are swept once the map grows past this, so idle callers do not pile up. */
    private static final int SWEEP_AT = 10_000;

    private final int limitPerMinute;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    FixedWindowRateLimiter(int limitPerMinute, Clock clock) {
        this.limitPerMinute = limitPerMinute;
        this.clock = clock;
    }

    private record Window(long start, AtomicInteger count) {
    }

    /** Counts one try under each key; false as soon as any of them is over the cap. */
    boolean allow(Iterable<String> keys) {
        long now = clock.millis();
        if (windows.size() > SWEEP_AT) windows.values().removeIf(w -> now - w.start() >= WINDOW_MILLIS);
        for (String key : keys) {
            Window w = windows.compute(key, (k, current) ->
                    current == null || now - current.start() >= WINDOW_MILLIS
                            ? new Window(now, new AtomicInteger()) : current);
            if (w.count().incrementAndGet() > limitPerMinute) {
                return false;
            }
        }
        return true;
    }
}
