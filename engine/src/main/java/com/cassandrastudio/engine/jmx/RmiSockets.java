package com.cassandrastudio.engine.jmx;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.rmi.server.RMIClientSocketFactory;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * The TCP (or TLS) sockets of ONE JMX session. Every RMI stub of the session is rewritten to use
 * {@link #factory()}, so RMI's connection cache (keyed by host, port and factory) never shares a
 * connection between two sessions, even when both nodes advertise the same 127.0.0.1:7199.
 * Sockets get a connect timeout and a read timeout (SO_TIMEOUT), so a hung node cannot block a
 * caller forever; closing the session closes its sockets.
 */
final class RmiSockets implements AutoCloseable {
    private static final Map<String, RmiSockets> LIVE = new ConcurrentHashMap<>();

    private final String id = UUID.randomUUID().toString();
    private final SSLSocketFactory ssl;
    private final int connectTimeoutMs;
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private volatile int readTimeoutMs;

    RmiSockets(SSLSocketFactory ssl, Duration connectTimeout, Duration readTimeout) {
        this.ssl = ssl;
        this.connectTimeoutMs = millis(connectTimeout);
        this.readTimeoutMs = millis(readTimeout);
        LIVE.put(id, this);
    }

    private static int millis(Duration d) {
        return d.isZero() || d.isNegative() ? 0 : (int) Math.max(1, Math.min(Integer.MAX_VALUE, d.toMillis()));
    }

    Factory factory() {
        return new Factory(id);
    }

    /** Changes the read timeout of future and already open sockets (connect phase vs. normal use). */
    void readTimeout(Duration d) {
        readTimeoutMs = millis(d);
        open.removeIf(Socket::isClosed);
        for (Socket s : open) {
            try {
                s.setSoTimeout(readTimeoutMs);
            } catch (SocketException ignored) {
                // closed meanwhile
            }
        }
    }

    int openSockets() {
        open.removeIf(Socket::isClosed);
        return open.size();
    }

    private Socket create(String host, int port) throws IOException {
        open.removeIf(Socket::isClosed);
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            raw.setSoTimeout(readTimeoutMs);
            raw.setTcpNoDelay(true);
            raw.setKeepAlive(true);
            Socket s = raw;
            if (ssl != null) {
                SSLSocket tls = (SSLSocket) ssl.createSocket(raw, host, port, true);
                tls.setSoTimeout(readTimeoutMs);
                tls.startHandshake();
                s = tls;
            }
            open.add(s);
            return s;
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
    }

    @Override
    public void close() {
        LIVE.remove(id);
        for (Socket s : open) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        open.clear();
    }

    /** Serialisable handle carried inside the rewritten stubs; finds the live session by id. */
    static final class Factory implements RMIClientSocketFactory, Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private final String id;

        Factory(String id) {
            this.id = id;
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            RmiSockets s = LIVE.get(id);
            if (s == null) throw new IOException("JMX session is closed");
            return s.create(host, port);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Factory f && f.id.equals(id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return "StudioJmxSockets[" + id + "]";
        }
    }
}
