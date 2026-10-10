package com.cassandrastudio.engine.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.cql.SessionManager.TestResult;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.Environment;
import com.cassandrastudio.engine.model.ConnectionConfig.ProxyType;
import com.cassandrastudio.engine.model.ConnectionConfig.SecretKeys;
import com.cassandrastudio.engine.model.ConnectionConfig.Ssh;
import com.cassandrastudio.engine.model.ConnectionConfig.SshAuth;
import com.cassandrastudio.engine.model.ConnectionConfig.SshProxy;
import com.cassandrastudio.engine.model.ConnectionConfig.Tls;
import com.cassandrastudio.engine.net.Net;
import com.cassandrastudio.engine.net.NetworkSettings;
import com.cassandrastudio.engine.net.TestCerts;
import com.cassandrastudio.engine.net.TestProxy;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.ssh.SshConnection;
import com.cassandrastudio.engine.ssh.SshConnector;
import com.cassandrastudio.engine.store.Database;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * NFR-NET against the live test-env: SSH to real node sidecars through an in-process HTTP CONNECT / SOCKS5
 * proxy (3.11 direct, 5.0 via the bastion), and CQL TLS to secure-50 trusted only through the global CA bundle.
 * Overrides: STUDIO_IT_SSH_DIR (../test-env/ssh), STUDIO_IT_CERT_DIR (../test-env/certs).
 */
@Tag("integration")
class NetworkIntegrationTest {
    private static final Duration T = Duration.ofSeconds(20);
    private final Path sshDir = Path.of(env("STUDIO_IT_SSH_DIR", "../test-env/ssh"));
    private final Path certDir = Path.of(env("STUDIO_IT_CERT_DIR", "../test-env/certs"));

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }

    private static boolean listening(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @AfterEach
    void reset() {
        Net.reset();
    }

    @Test
    void sshToTheLegacyNodeThroughHttpAndSocksProxies() throws Exception {
        assumeTrue(listening("127.0.0.1", 2204) && Files.exists(sshDir.resolve("id_test")), "legacy-311 SSH sidecar is not running");
        try (TestProxy proxy = new TestProxy("corp", "pw", h -> h.equals("legacy311.behind.proxy") ? "127.0.0.1" : h);
             SshConnector c = new SshConnector()) {
            for (ProxyType type : ProxyType.values()) {
                Ssh ssh = new Ssh("studio", 2204, SshAuth.KEY, sshDir.resolve("id_test").toAbsolutePath().toString(), null, null,
                        null, false, null, new SshProxy(type, "127.0.0.1", proxy.port(), "corp"));
                try (SshConnection s = c.connect(ssh, Map.of(SecretKeys.SSH_PROXY_PASSWORD, "pw"), "legacy311.behind.proxy", T)) {
                    assertThat(s.exec("ls /opt/cassandra/logs", T)).contains("system.log");
                }
            }
            assertThat(proxy.tunnels).containsExactly("legacy311.behind.proxy:2204", "legacy311.behind.proxy:2204");
        }
    }

    @Test
    void sshToSecure50ThroughTheProxyToTheBastion() throws Exception {
        assumeTrue(listening("127.0.0.1", 2200) && Files.exists(sshDir.resolve("id_test")), "bastion is not running");
        try (TestProxy proxy = new TestProxy(null, null, h -> h.equals("bastion.behind.proxy") ? "127.0.0.1" : h);
             SshConnector c = new SshConnector()) {
            Ssh ssh = new Ssh("studio", 2222, SshAuth.KEY, sshDir.resolve("id_test").toAbsolutePath().toString(),
                    "bastion.behind.proxy", 2200, "studio", false, null, new SshProxy(ProxyType.SOCKS5, "127.0.0.1", proxy.port(), null));
            try (SshConnection s = c.connect(ssh, Map.of(), "10.231.42.31", T)) {
                assertThat(s.exec("ls /var/lib/cassandra", T)).contains("data");
            }
            assertThat(proxy.tunnels).containsExactly("bastion.behind.proxy:2200");
        }
    }

    @Test
    void cqlTlsTrustsTheGlobalCaBundle(@TempDir Path tmp) throws Exception {
        assumeTrue(listening("127.0.0.1", 39042) && Files.exists(certDir.resolve("node.pem")), "secure-50 is not running");
        TestCerts unrelated = new TestCerts(tmp);
        try (Engine engine = new Engine(Database.inMemory(), SecretStores.inMemory(), "it")) {
            // The connection's own truststore does not trust the node: only the global bundle does.
            ConnectionConfig cfg = new ConnectionConfig(null, null, "secure-50", Environment.DEV, null, true,
                    List.of("127.0.0.1:39042"), null, "cassandra",
                    new Tls(true, unrelated.otherCaPem.toString(), "PEM", null, null, false),
                    null, "ONE", 20_000, null, null, null, null, null, null);
            Map<String, String> secrets = Map.of(SecretKeys.PASSWORD, "cassandra");
            TestResult without = engine.sessions.test(cfg, secrets);
            assertThat(without.ok()).isFalse();
            Net.apply(new NetworkSettings(false, true, NetworkSettings.ProxyMode.NONE, null, null, null, List.of(), false,
                    certDir.resolve("node.pem").toAbsolutePath().toString(), false), null);
            TestResult with = engine.sessions.test(cfg, secrets);
            assertThat(with.ok()).as(String.valueOf(with.error())).isTrue();
            assertThat(with.version()).startsWith("5.");
        }
    }
}
