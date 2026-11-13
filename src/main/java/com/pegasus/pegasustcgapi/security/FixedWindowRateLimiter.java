package com.pegasus.pegasustcgapi.security;

import java.time.Clock;
import java.util.Map;
import java.util.HashMap;

/**
 * A count of tries per key per minute, kept in this instance's memory. With several
 * replicas the cap is per replica, which still bounds a sweep.
 */
final class FixedWindowRateLimiter {

    static final long WINDOW_MILLIS = 60_000;
    /** Refuse new keys when full rather than letting address churn exhaust memory. */
    private static final int MAX_KEYS = 10_000;

    private final int limitPerMinute;
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();
    private long nextSweep;

    FixedWindowRateLimiter(int limitPerMinute, Clock clock) {
        if (limitPerMinute <= 0) throw new IllegalArgumentException("Rate limit must be positive");
        this.limitPerMinute = limitPerMinute;
        this.clock = clock;
    }

    private static final class Window {
        private final long start;
        private int count;

        private Window(long start) {
            this.start = start;
        }
    }

    /** Counts one try under each key; false as soon as any of them is over the cap. */
    synchronized boolean allow(Iterable<String> keys) {
        long now = clock.millis();
        if (windows.size() >= MAX_KEYS && now >= nextSweep) {
            windows.values().removeIf(w -> now - w.start >= WINDOW_MILLIS);
            nextSweep = now + 1000;
        }
        for (String key : keys) {
            Window w = windows.get(key);
            if (w == null || now - w.start >= WINDOW_MILLIS) {
                if (w == null && windows.size() >= MAX_KEYS) return false;
                w = new Window(now);
                windows.put(key, w);
            }
            if (w.count >= limitPerMinute) return false;
            w.count++;
        }
        return true;
    }
}
