package com.cassandrastudio.engine.jmx;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.server.RMISocketFactory;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JVM-wide RMI socket factory, used only for stubs that carry no factory of their own, i.e. the
 * node's ORIGINAL stubs before {@link RmiStubs} rewrites them. When RMI unmarshals such a stub it
 * synchronously sends a DGC "dirty" call to the endpoint the node advertised (127.0.0.1:7199, or a
 * firewalled address); on a Studio connect thread that call is refused at once instead of hanging
 * until the OS connect timeout. Elsewhere it behaves like the default factory plus a connect timeout.
 */
final class RmiDefaultSockets extends RMISocketFactory {
    private static final Logger LOG = LoggerFactory.getLogger(RmiDefaultSockets.class);
    private static final ThreadLocal<Boolean> CONNECTING = ThreadLocal.withInitial(() -> false);
    private static boolean installed;

    private final int connectTimeoutMs;

    private RmiDefaultSockets(Duration connectTimeout) {
        this.connectTimeoutMs = (int) Math.min(Integer.MAX_VALUE, Math.max(1, connectTimeout.toMillis()));
    }

    static synchronized void install(Duration connectTimeout) {
        if (installed) return;
        installed = true;
        try {
            RMISocketFactory.setSocketFactory(new RmiDefaultSockets(connectTimeout));
        } catch (IOException | IllegalStateException e) {
            LOG.debug("RMI socket factory already set by someone else; stray DGC calls may wait for the OS timeout");
        }
    }

    /** Runs {@code work} with advertised (un-rewritten) RMI endpoints refused on this thread. */
    static <T> T whileConnecting(Supplier<T> work) {
        boolean outer = CONNECTING.get();
        CONNECTING.set(true);
        try {
            return work.get();
        } finally {
            CONNECTING.set(outer);
        }
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        if (CONNECTING.get()) throw new ConnectException("advertised RMI endpoint " + host + ":" + port + " is not used");
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            return s;
        } catch (IOException e) {
            s.close();
            throw e;
        }
    }

    @Override
    public ServerSocket createServerSocket(int port) throws IOException {
        return new ServerSocket(port);
    }
}
