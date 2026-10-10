package com.cassandrastudio.engine.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.net.NetworkSettings.ProxyMode;
import com.cassandrastudio.engine.net.UpdateChecker.UpdateStatus;
import com.cassandrastudio.engine.util.ApiException;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Global CA bundle trust (generated CA + server cert) and the update check over HTTPS, through a proxy. */
class CaTrustAndUpdateTest {
    @TempDir
    static Path dir;
    static TestCerts certs;
    static HttpsServer https;
    static final AtomicInteger hits = new AtomicInteger();

    static final String RELEASE = """
            {"tag_name":"v1.1.0","name":"Cassandra Studio 1.1.0","html_url":"https://github.com/Gorak4u/cassandra-studio/releases/tag/v1.1.0",
             "body":"## Fixes\\n- proxy support","published_at":"2026-10-01T10:00:00Z","prerelease":false,"draft":false}
            """;

    @BeforeAll
    static void setUp() throws Exception {
        certs = new TestCerts(dir);
        https = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(certs.serverContext()));
        https.createContext("/repos/Gorak4u/cassandra-studio/releases/latest", ex -> {
            hits.incrementAndGet();
            byte[] b = RELEASE.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        https.start();
    }

    @AfterAll
    static void tearDown() {
        https.stop(0);
    }

    @AfterEach
    void reset() {
        Net.reset();
    }

    private static NetworkSettings settings(ProxyMode mode, Integer proxyPort, String user, String ca) {
        return new NetworkSettings(false, true, mode, mode == ProxyMode.MANUAL ? "127.0.0.1" : null, proxyPort, user,
                List.of(), false, ca, false);
    }

    /** A TLS handshake with the test server using these trust managers. */
    private static void handshake(TrustManager[] trust) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trust, null);
        try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket("127.0.0.1", https.getAddress().getPort())) {
            s.startHandshake();
        }
    }

    @Test
    void caBundleIsTrustedInAdditionToTheJdkAndTheConnectionTruststore() throws Exception {
        assertThat(Net.trustManagers(null)).as("nothing configured: JDK default").isNull();
        assertThatThrownBy(() -> handshake(null)).isInstanceOf(SSLHandshakeException.class);

        KeyStore unrelated = CaTrust.keyStore(CaTrust.readPem(certs.otherCaPem));
        assertThatThrownBy(() -> handshake(Net.trustManagers(unrelated))).isInstanceOf(SSLHandshakeException.class);

        Net.apply(settings(ProxyMode.NONE, null, null, certs.caPem.toString()), null);
        handshake(Net.trustManagers(null));      // JDK CAs + bundle
        handshake(Net.trustManagers(unrelated)); // connection truststore + bundle
        assertThat(Net.trust().bundle()).hasSize(1);
        assertThat(Net.trust().bundle().get(0).getSubjectX500Principal().getName()).isEqualTo("CN=Studio Test CA");
    }

    @Test
    void badBundleIsRejectedAndLeavesTheOldTrust() throws Exception {
        Net.apply(settings(ProxyMode.NONE, null, null, certs.caPem.toString()), null);
        Path notPem = dir.resolve("nothing.pem");
        java.nio.file.Files.writeString(notPem, "not a certificate");
        assertThatThrownBy(() -> Net.apply(settings(ProxyMode.NONE, null, null, notPem.toString()), null))
                .hasMessageContaining("nothing.pem");
        assertThatThrownBy(() -> Net.apply(settings(ProxyMode.NONE, null, null, dir.resolve("missing.pem").toString()), null))
                .hasMessageContaining("Cannot read CA bundle");
        handshake(Net.trustManagers(null)); // still the good bundle
    }

    @Test
    void osTrustStoreLoadsWhenAvailable() {
        CaTrust t = CaTrust.load(null, true);
        assertThat(t.trustsOs()).isTrue();
        if (NetworkService.osTrustAvailable()) assertThat(t.isEmpty()).isFalse();
    }

    private UpdateChecker checker(int port) {
        URI endpoint = URI.create("https://updates.example.test:" + port + "/repos/Gorak4u/cassandra-studio/releases/latest");
        return new UpdateChecker(endpoint, "1.0.0", Net::internetClient, Clock.systemUTC());
    }

    @Test
    void updateCheckOverHttpsThroughAnAuthenticatingProxyWithTheCaBundle() throws Exception {
        int port = https.getAddress().getPort();
        try (TestProxy proxy = new TestProxy("alice", "pa:ss", h -> h.equals("updates.example.test") ? "127.0.0.1" : h)) {
            NetworkSettings s = settings(ProxyMode.MANUAL, proxy.port(), "alice", certs.caPem.toString());
            Net.apply(s, "pa:ss");
            UpdateStatus st = checker(port).check(s, true);
            assertThat(st.message()).isEqualTo("Version 1.1.0 is available.");
            assertThat(st.state()).isEqualTo("ok");
            assertThat(st.latest()).isEqualTo("1.1.0");
            assertThat(st.updateAvailable()).isTrue();
            assertThat(st.url()).isEqualTo("https://github.com/Gorak4u/cassandra-studio/releases/tag/v1.1.0");
            assertThat(st.notes()).contains("proxy support");
            assertThat(proxy.tunnels).contains("updates.example.test:" + port);

            // Without the CA bundle the TLS check fails with a hint.
            NetworkSettings noCa = settings(ProxyMode.MANUAL, proxy.port(), "alice", null);
            Net.apply(noCa, "pa:ss");
            UpdateStatus err = checker(port).check(noCa, true);
            assertThat(err.state()).isEqualTo("error");
            assertThat(err.message()).contains("TLS check").contains("CA bundle");
        }
    }

    @Test
    void offlineModeAndDisabledCheckMakeNoCall() {
        AtomicInteger calls = new AtomicInteger();
        UpdateChecker c = new UpdateChecker(URI.create("https://never.invalid/"), "1.0.0", t -> {
            calls.incrementAndGet();
            throw new AssertionError("no HTTP client may be created");
        }, Clock.systemUTC());
        NetworkSettings offline = new NetworkSettings(true, true, ProxyMode.NONE, null, null, null, List.of(), false, null, false);
        assertThat(c.check(offline, true).state()).isEqualTo("offline");
        NetworkSettings off = new NetworkSettings(false, false, ProxyMode.NONE, null, null, null, List.of(), false, null, false);
        assertThat(c.check(off, true).state()).isEqualTo("disabled");
        assertThat(calls).hasValue(0);

        Net.apply(offline, null);
        assertThatThrownBy(() -> Net.internetClient(Duration.ofSeconds(1))).isInstanceOf(ApiException.class)
                .hasMessageContaining("offline mode");
    }

    @Test
    void resultIsCachedUntilForced() throws Exception {
        int port = https.getAddress().getPort();
        try (TestProxy proxy = new TestProxy(null, null, h -> "127.0.0.1")) {
            NetworkSettings s = settings(ProxyMode.MANUAL, proxy.port(), null, certs.caPem.toString());
            Net.apply(s, null);
            UpdateChecker c = checker(port);
            int before = hits.get();
            c.check(s, false);
            c.check(s, false);
            assertThat(hits.get() - before).isEqualTo(1);
            c.check(s, true);
            assertThat(hits.get() - before).isEqualTo(2);
            c.invalidate();
            c.check(s, false);
            assertThat(hits.get() - before).isEqualTo(3);
        }
    }

    @Test
    void comparesVersionsAndOnlyLinksToTheProjectsReleases() {
        UpdateChecker c = new UpdateChecker(URI.create("https://x.invalid/"), "1.1.0-rc.2", t -> null, Clock.systemUTC());
        assertThat(c.parse(RELEASE, "now").updateAvailable()).as("1.1.0 > 1.1.0-rc.2").isTrue();
        UpdateChecker same = new UpdateChecker(URI.create("https://x.invalid/"), "1.1.0", t -> null, Clock.systemUTC());
        assertThat(same.parse(RELEASE, "now").updateAvailable()).isFalse();
        assertThat(same.parse(RELEASE, "now").message()).isEqualTo("You have the latest version.");
        UpdateChecker newer = new UpdateChecker(URI.create("https://x.invalid/"), "1.2.0-SNAPSHOT", t -> null, Clock.systemUTC());
        assertThat(newer.parse(RELEASE, "now").updateAvailable()).isFalse();
        UpdateChecker dev = new UpdateChecker(URI.create("https://x.invalid/"), "dev", t -> null, Clock.systemUTC());
        assertThat(dev.parse(RELEASE, "now").updateAvailable()).isFalse();
        assertThat(dev.parse(RELEASE, "now").message()).contains("development build");

        UpdateStatus evil = same.parse("{\"tag_name\":\"v9.0.0\",\"html_url\":\"https://evil.example/download\"}", "now");
        assertThat(evil.url()).isEqualTo(UpdateChecker.RELEASES_PAGE);
        assertThat(same.parse("{\"tag_name\":\"nightly\"}", "now").state()).isEqualTo("error");
        assertThat(same.parse("<html>", "now").state()).isEqualTo("error");
    }
}
