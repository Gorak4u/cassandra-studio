package com.cassandrastudio.engine.conn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HostPortTest {
    @Test
    void parsesForms() {
        assertThat(HostPort.parse("cass1", 9042)).isEqualTo(new HostPort("cass1", 9042));
        assertThat(HostPort.parse(" 10.0.0.1:9142 ", 9042)).isEqualTo(new HostPort("10.0.0.1", 9142));
        assertThat(HostPort.parse("[::1]:9043", 9042)).isEqualTo(new HostPort("::1", 9043));
        assertThat(HostPort.parse("fe80::1", 9042)).isEqualTo(new HostPort("fe80::1", 9042));
    }

    @Test
    void rejectsBadInput() {
        assertThatThrownBy(() -> HostPort.parse("host:abc", 9042)).hasMessageContaining("Bad port");
        assertThatThrownBy(() -> HostPort.parse("host:70000", 9042)).hasMessageContaining("range");
        assertThatThrownBy(() -> HostPort.parse("", 9042)).hasMessageContaining("Empty");
        assertThatThrownBy(() -> HostPort.parse("a b:1", 9042)).hasMessageContaining("Bad host");
    }
}
