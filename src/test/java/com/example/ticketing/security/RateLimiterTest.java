package com.example.ticketing.security;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2030-01-01T00:00:00Z"));

        void advanceSeconds(long seconds) {
            now.updateAndGet(i -> i.plusSeconds(seconds));
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @Test
    void allowsUpToLimitThenBlocks() {
        RateLimiter limiter = new RateLimiter(new MutableClock());
        assertTrue(limiter.tryAcquire("a", 3).allowed());
        assertTrue(limiter.tryAcquire("a", 3).allowed());
        RateLimiter.Decision third = limiter.tryAcquire("a", 3);
        assertTrue(third.allowed());
        assertEquals(0, third.remaining());
        RateLimiter.Decision fourth = limiter.tryAcquire("a", 3);
        assertFalse(fourth.allowed());
        assertTrue(fourth.retryAfterSeconds() >= 1);
    }

    @Test
    void keysAreIndependent() {
        RateLimiter limiter = new RateLimiter(new MutableClock());
        assertTrue(limiter.tryAcquire("a", 1).allowed());
        assertFalse(limiter.tryAcquire("a", 1).allowed());
        assertTrue(limiter.tryAcquire("b", 1).allowed());
    }

    @Test
    void windowResetsAfterAMinute() {
        MutableClock clock = new MutableClock();
        RateLimiter limiter = new RateLimiter(clock);
        assertTrue(limiter.tryAcquire("a", 1).allowed());
        assertFalse(limiter.tryAcquire("a", 1).allowed());
        clock.advanceSeconds(61);
        assertTrue(limiter.tryAcquire("a", 1).allowed());
    }
}
