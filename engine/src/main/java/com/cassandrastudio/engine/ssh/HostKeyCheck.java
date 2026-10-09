package com.cassandrastudio.engine.ssh;

import java.nio.file.Path;
import org.apache.sshd.common.AttributeRepository.AttributeKey;

/**
 * Host-key policy for one SSH connection attempt, handed to {@link KnownHostsVerifier} through the
 * session's connection context. {@code host}/{@code port} are what the user configured (not the
 * local end of a jump tunnel). The verifier records why it rejected a key, for a readable error.
 */
final class HostKeyCheck {
    static final AttributeKey<HostKeyCheck> KEY = new AttributeKey<>();

    final String host;
    final int port;
    final boolean strict;
    final Path knownHosts;
    private volatile String rejection;

    HostKeyCheck(String host, int port, boolean strict, Path knownHosts) {
        this.host = host;
        this.port = port;
        this.strict = strict;
        this.knownHosts = knownHosts;
    }

    /** known_hosts style name: {@code host} on port 22, {@code [host]:port} otherwise. */
    String displayName() {
        return port == 22 ? host : "[" + host + "]:" + port;
    }

    void reject(String reason) {
        rejection = reason;
    }

    /** Why the host key was refused, or null when it was not. */
    String rejection() {
        return rejection;
    }
}
