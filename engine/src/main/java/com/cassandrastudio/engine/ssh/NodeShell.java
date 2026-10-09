package com.cassandrastudio.engine.ssh;

import com.cassandrastudio.engine.model.ConnectionConfig;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Phase 3 contract: shell commands on a node over SSH (GC logs, config files, backup scripts,
 * df ...), using the connection's SSH settings (user, key/agent/password, jump host). One SSH
 * session per (connection, node) is cached and reopened when it drops or the settings change.
 *
 * <p>Commands run as the SSH user. Callers quote every argument they build from user input
 * ({@link #quote}) and never pass secrets on the command line.
 */
public final class NodeShell implements AutoCloseable {
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final SshConnector connector = new SshConnector();
    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();

    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        SshConnection conn;
        String fingerprint;
    }

    /** Standard output of {@code command} on {@code host}; throws {@link SshAccessException} on failure. */
    public String exec(ConnectionConfig cfg, Map<String, String> secrets, String host, String command, Duration timeout) {
        return connection(cfg, secrets, host).exec(command, timeout);
    }

    /** Like {@link #exec(ConnectionConfig, Map, String, String, Duration)} keeping up to {@code maxBytes}. */
    public String exec(ConnectionConfig cfg, Map<String, String> secrets, String host, String command, Duration timeout,
                       int maxBytes) {
        return connection(cfg, secrets, host).exec(command, timeout, maxBytes);
    }

    /** The cached SSH session to {@code host}, opened on demand (also for port forwards). */
    public SshConnection connection(ConnectionConfig cfg, Map<String, String> secrets, String host) {
        if (cfg.ssh() == null) throw new SshAccessException("SSH is not configured for this connection");
        Entry e = sessions.computeIfAbsent(cfg.id() + "|" + host, k -> new Entry());
        String fp = cfg.ssh() + "|" + secrets.getOrDefault(ConnectionConfig.SecretKeys.SSH_PASSWORD, "").hashCode()
                + "|" + secrets.getOrDefault(ConnectionConfig.SecretKeys.SSH_PASSPHRASE, "").hashCode();
        e.lock.lock();
        try {
            if (e.conn != null && (!e.conn.isOpen() || !fp.equals(e.fingerprint))) {
                e.conn.close();
                e.conn = null;
            }
            if (e.conn == null) {
                e.conn = connector.connect(cfg.ssh(), secrets, host, CONNECT_TIMEOUT);
                e.fingerprint = fp;
            }
            return e.conn;
        } finally {
            e.lock.unlock();
        }
    }

    /** Close the sessions of one connection (disconnect or settings change). */
    public void closeConnection(String connectionId) {
        sessions.entrySet().removeIf(en -> {
            if (!en.getKey().startsWith(connectionId + "|")) return false;
            Entry e = en.getValue();
            e.lock.lock();
            try {
                if (e.conn != null) e.conn.close();
                e.conn = null;
            } finally {
                e.lock.unlock();
            }
            return true;
        });
    }

    /** Single-quotes {@code s} for a POSIX shell. */
    public static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    @Override
    public void close() {
        sessions.values().forEach(e -> {
            if (e.conn != null) e.conn.close();
        });
        sessions.clear();
        connector.close();
    }
}
