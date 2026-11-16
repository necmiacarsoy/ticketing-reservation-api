package com.example.ticketing.security;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

public final class RateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;
    private static final int EVICTION_THRESHOLD = 10_000;

    public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
    }

    private record Window(long start, long count) {
    }

    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    public Decision tryAcquire(String key, int limit) {
        long now = clock.millis();
        Window window = windows.compute(key, (k, old) ->
                old == null || now - old.start() >= WINDOW_MILLIS
                        ? new Window(now, 1)
                        : new Window(old.start(), old.count() + 1));
        if (windows.size() > EVICTION_THRESHOLD) {
            windows.entrySet().removeIf(e -> now - e.getValue().start() >= WINDOW_MILLIS);
        }
        long retryAfter = Math.max(1, (window.start() + WINDOW_MILLIS - now + 999) / 1000);
        return new Decision(window.count() <= limit, Math.max(0, limit - window.count()), retryAfter);
    }
}
