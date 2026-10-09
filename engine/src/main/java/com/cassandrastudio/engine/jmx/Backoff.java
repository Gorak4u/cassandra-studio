package com.cassandrastudio.engine.jmx;

import java.time.Duration;

/**
 * Reconnect back-off for one node (NFR-RELI): after a failure, further attempts fail fast with
 * the remembered reason until the wait is over; the wait doubles per consecutive failure up to a
 * cap, and a success resets it. Not thread-safe: callers hold the node's lock.
 */
final class Backoff {
    private final long initialNanos;
    private final long maxNanos;
    private int failures;
    private long retryAtNanos;
    private RuntimeException last;

    Backoff(Duration initial, Duration max) {
        this.initialNanos = initial.toNanos();
        this.maxNanos = max.toNanos();
    }

    /** Null when an attempt may be made at {@code now}, else how long to wait. */
    Duration waitLeft(long now) {
        if (failures == 0 || now - retryAtNanos >= 0) return null;
        return Duration.ofNanos(retryAtNanos - now);
    }

    void failure(long now, RuntimeException reason) {
        failures++;
        long wait = initialNanos << Math.min(failures - 1, 20);
        retryAtNanos = now + Math.min(maxNanos, wait <= 0 ? maxNanos : wait);
        last = reason;
    }

    void success() {
        failures = 0;
        last = null;
    }

    int failures() {
        return failures;
    }

    /** The failure that started the current wait. */
    RuntimeException lastFailure() {
        return last;
    }
}
