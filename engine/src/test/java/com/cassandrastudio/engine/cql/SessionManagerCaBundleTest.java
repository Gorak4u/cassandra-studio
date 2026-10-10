package com.cassandrastudio.engine.cql;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.net.Net;
import com.cassandrastudio.engine.net.NetworkSettings;
import com.cassandrastudio.engine.net.TestCerts;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CQL TLS trusts the global CA bundle in addition to the connection's own truststore (NFR-NET). */
class SessionManagerCaBundleTest {
    @AfterEach
    void reset() {
        Net.reset();
    }

    @Test
    void connectionTruststorePlusGlobalBundle(@TempDir Path dir) throws Exception {
        TestCerts certs = new TestCerts(dir);
        try (SSLServerSocket server = (SSLServerSocket) certs.serverContext().getServerSocketFactory()
                .createServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try (SSLSocket s = (SSLSocket) server.accept()) {
                        s.startHandshake();
                    } catch (Exception ignored) {
                        // client rejected us, or closed
                    }
                }
            });
            // The connection trusts an unrelated CA only (PEM truststore).
            ConnectionConfig.Tls tls = new ConnectionConfig.Tls(true, certs.otherCaPem.toString(), "PEM", null, null, false);
            assertThatThrownBy(() -> handshake(SessionManager.sslContext(tls, Map.of()).getSocketFactory(), server.getLocalPort()))
                    .isInstanceOf(javax.net.ssl.SSLHandshakeException.class);
            Net.apply(new NetworkSettings(false, true, NetworkSettings.ProxyMode.NONE, null, null, null, List.of(), false,
                    certs.caPem.toString(), false), null);
            handshake(SessionManager.sslContext(tls, Map.of()).getSocketFactory(), server.getLocalPort());
        }
    }

    private static void handshake(SSLSocketFactory f, int port) throws Exception {
        try (SSLSocket s = (SSLSocket) f.createSocket("127.0.0.1", port)) {
            s.startHandshake();
        }
    }
}
