package com.cassandrastudio.engine.bulk;

import java.util.function.LongSupplier;

/** Rows per second, smooth: each permit is due 1/rate s after the previous one (no burst beyond 1 s). */
final class RateLimiter {
    private final double nanosPerPermit;
    private final LongSupplier clock;
    private long next;

    RateLimiter(int perSecond) {
        this(perSecond, System::nanoTime);
    }

    RateLimiter(int perSecond, LongSupplier clock) {
        this.nanosPerPermit = 1e9 / perSecond;
        this.clock = clock;
        this.next = clock.getAsLong();
    }

    /** How long the caller must wait before using {@code permits}; reserves them. */
    synchronized long reserve(int permits) {
        long now = clock.getAsLong();
        // Unused time is credited for at most one second.
        if (next < now - 1_000_000_000L) next = now - 1_000_000_000L;
        long wait = Math.max(0, next - now);
        next += (long) (permits * nanosPerPermit);
        return wait;
    }

    void acquire(int permits) throws InterruptedException {
        long wait = reserve(permits);
        if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
    }
}
