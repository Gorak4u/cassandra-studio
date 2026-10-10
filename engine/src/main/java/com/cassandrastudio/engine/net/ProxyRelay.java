package com.cassandrastudio.engine.net;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;

/**
 * A one-shot local relay for clients that can only dial a socket address (the SSH client): listens on
 * 127.0.0.1 on a free port, accepts exactly ONE connection, opens the proxy tunnel for it and copies bytes
 * both ways until either side closes. The listener closes after the first accept (or on {@link #close}),
 * so no other local process can use it afterwards.
 */
public final class ProxyRelay implements Closeable {
    private final ServerSocket server;
    private final ProxyEndpoint proxy;
    private final String host;
    private final int port;
    private final Duration timeout;
    private volatile Socket client;
    private volatile Socket upstream;
    private volatile IOException failure;

    public ProxyRelay(ProxyEndpoint proxy, String host, int port, Duration timeout) throws IOException {
        this.proxy = proxy;
        this.host = host;
        this.port = port;
        this.timeout = timeout;
        this.server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("proxy-relay-" + host).start(this::acceptOne);
    }

    /** Where the client should connect. */
    public InetSocketAddress address() {
        return new InetSocketAddress(server.getInetAddress(), server.getLocalPort());
    }

    /** Why the tunnel failed, when it did (for a clearer message than "connection closed"). */
    public IOException failure() {
        return failure;
    }

    private void acceptOne() {
        try {
            server.setSoTimeout((int) Math.max(1000, Math.min(Integer.MAX_VALUE, timeout.toMillis())));
            Socket c = server.accept();
            client = c;
            server.close();
            Socket up;
            try {
                up = ProxyTunnel.open(proxy, host, port, timeout);
            } catch (IOException e) {
                failure = e;
                c.close();
                return;
            }
            upstream = up;
            Thread.ofVirtual().name("proxy-relay-up").start(() -> pump(c, up));
            pump(up, c);
        } catch (IOException e) {
            if (failure == null && !server.isClosed()) failure = e;
        } finally {
            quietClose(server);
        }
    }

    private static void pump(Socket from, Socket to) {
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            in.transferTo(out);
        } catch (IOException e) {
            // either side closed
        } finally {
            quietClose(from);
            quietClose(to);
        }
    }

    private static void quietClose(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // closing
        }
    }

    @Override
    public void close() {
        quietClose(server);
        quietClose(client);
        quietClose(upstream);
    }
}
