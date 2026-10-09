package com.cassandrastudio.engine.jmx;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffTest {
    private static final long S = 1_000_000_000L;

    @Test
    void waitsDoubleUpToTheCapAndResetOnSuccess() {
        Backoff b = new Backoff(Duration.ofSeconds(2), Duration.ofSeconds(10));
        assertThat(b.waitLeft(0)).isNull();
        RuntimeException down = new RuntimeException("down");
        b.failure(0, down);
        assertThat(b.waitLeft(S)).isEqualTo(Duration.ofSeconds(1));
        assertThat(b.lastFailure()).isSameAs(down);
        assertThat(b.waitLeft(2 * S)).isNull();
        b.failure(2 * S, down);
        assertThat(b.waitLeft(2 * S)).isEqualTo(Duration.ofSeconds(4));
        b.failure(6 * S, down);
        assertThat(b.waitLeft(6 * S)).isEqualTo(Duration.ofSeconds(8));
        b.failure(14 * S, down);
        assertThat(b.waitLeft(14 * S)).isEqualTo(Duration.ofSeconds(10));
        for (int i = 0; i < 100; i++) b.failure(100 * S, down);
        assertThat(b.waitLeft(100 * S)).isEqualTo(Duration.ofSeconds(10));
        b.success();
        assertThat(b.waitLeft(100 * S)).isNull();
        assertThat(b.failures()).isZero();
    }
}
