package com.cassandrastudio.engine.jmx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.server.RMIClientSocketFactory;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.rmi.RMIConnector;
import javax.management.remote.rmi.RMIServer;
import org.junit.jupiter.api.Test;

/**
 * Stub rewriting with public APIs only: a node whose stubs advertise an endpoint that must never
 * be dialled is still reached, through a relay, with every call on the session's own sockets.
 */
class RmiStubsTest {

    /** What the node advertises: any use of it is a test failure. */
    public static final class Unreachable implements RMIClientSocketFactory, Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            throw new IOException("advertised endpoint " + host + ":" + port + " was dialled");
        }
    }

    /** A TCP relay that counts the bytes it carries, standing in for an SSH forward. */
    static final class Relay implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        final AtomicLong bytes = new AtomicLong();

        Relay(int targetPort) throws IOException {
            Thread t = new Thread(() -> {
                while (!listener.isClosed()) {
                    try {
                        Socket in = listener.accept();
                        Socket out = new Socket(InetAddress.getLoopbackAddress(), targetPort);
                        pump(in, out);
                        pump(out, in);
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "relay");
            t.setDaemon(true);
            t.start();
        }

        private void pump(Socket from, Socket to) {
            Thread t = new Thread(() -> {
                byte[] buf = new byte[8192];
                try (InputStream i = from.getInputStream(); OutputStream o = to.getOutputStream()) {
                    for (int n; (n = i.read(buf)) > 0; ) {
                        o.write(buf, 0, n);
                        bytes.addAndGet(n);
                    }
                } catch (IOException ignored) {
                    // peer closed
                }
            }, "relay-pump");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            listener.close();
        }
    }

    @Test
    void rewrittenStubsUseTheRelocatedEndpointAndTheSessionSockets() throws Exception {
        try (TestJmxServer node = new TestJmxServer("N", true, new Unreachable());
             Relay relay = new Relay(node.objectPort);
             RmiSockets sockets = new RmiSockets(null, Duration.ofSeconds(5), Duration.ofSeconds(5))) {
            RMIServer original = node.stub();
            RMIConnector plain = new RMIConnector(original, Map.of());
            assertThatThrownBy(() -> plain.connect()).hasStackTraceContaining("was dialled");

            RmiStubs.Relocation toRelay = (h, p) -> new RmiStubs.Endpoint("127.0.0.1", relay.port());
            RMIServer moved = RmiStubs.relocate(original, toRelay, sockets.factory());
            assertThat(moved.toString()).contains("127.0.0.1:" + relay.port()).doesNotContain("Unreachable");

            JMXConnector c = new RMIConnector(RmiStubs.server(original, toRelay, sockets.factory()), Map.of());
            c.connect();
            MBeanServerConnection m = c.getMBeanServerConnection();
            long before = relay.bytes.get();
            assertThat(m.getAttribute(TestJmxServer.NODE, "Id")).isEqualTo("N");
            assertThat(relay.bytes.get()).isGreaterThan(before);
            assertThat(sockets.openSockets()).isPositive();
            c.close();
        }
    }

    @Test
    void closedSessionSocketsRefuseToDial() throws Exception {
        RmiSockets sockets = new RmiSockets(null, Duration.ofSeconds(1), Duration.ofSeconds(1));
        RmiSockets.Factory f = sockets.factory();
        try (RmiSockets other = new RmiSockets(null, Duration.ZERO, Duration.ZERO)) {
            assertThat(f).isEqualTo(sockets.factory()).isNotEqualTo(other.factory());
        }
        sockets.close();
        assertThatThrownBy(() -> f.createSocket("127.0.0.1", 1)).hasMessage("JMX session is closed");
    }
}
