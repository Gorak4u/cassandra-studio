package com.cassandrastudio.engine.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.gclog.GcLogFiles;
import com.cassandrastudio.engine.gclog.GcLogService;
import com.cassandrastudio.engine.gclog.GcReport;
import com.cassandrastudio.engine.jobs.Job;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * GC logs from the running test-env over SSH (GCL-1, acceptance criterion 8): JVM options over JMX,
 * file discovery, compressed transfer, and the report for Cassandra 3.11 (Java 8 CMS), 4.1 (Java 11
 * CMS) and 5.0 (Java 17 G1, through the bastion). The node log directories must be mounted into the
 * sshd sidecars (test-env/docker-compose.yml log volumes); without them the files list is empty and
 * only discovery and the transfer path are checked. Skipped when the test-env is not running.
 * Overrides: STUDIO_IT_SSH_DIR (../test-env/ssh), STUDIO_IT_CERTS (../test-env/certs).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GcLogIntegrationTest {
    private final Path sshKey = Path.of(env("STUDIO_IT_SSH_DIR", "../test-env/ssh")).resolve("id_test").toAbsolutePath();
    private final Path pem = Path.of(env("STUDIO_IT_CERTS", "../test-env/certs")).resolve("node.pem").toAbsolutePath();
    private Engine engine;
    private GcLogService svc;

    private static String env(String k, String dflt) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? dflt : v;
    }

    @BeforeAll
    void start() {
        assumeTrue(sshKey.toFile().exists(), "test-env SSH key not found: " + sshKey);
        engine = new Engine(Database.inMemory(), SecretStores.inMemory(), "it");
        svc = new GcLogService(engine);
    }

    @AfterAll
    void stop() {
        if (svc != null) svc.close();
        if (engine != null) engine.close();
    }

    private static boolean open(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String connection(String json, Map<String, String> secrets) {
        return engine.connections.save(Json.read(json, ConnectionConfig.class), secrets).id();
    }

    @Test
    void cassandra41Java11Cms() throws Exception {
        assumeTrue(open("10.231.42.11", 2222) && open("127.0.0.1", 19042), "acme-core not running");
        String id = connection("""
                {"name": "it-acme", "contactPoints": ["127.0.0.1:19042"], "localDatacenter": "dc_east",
                 "jmx": {"method": "SSH_TUNNEL", "port": 7199}, "ssh": %s}""".formatted(String.format(
                "{\"username\": \"studio\", \"port\": 2222, \"auth\": \"KEY\", \"keyPath\": \"%s\", "
                        + "\"strictHostKeyChecking\": false}", sshKey)), Map.of());
        check(id, "10.231.42.11", "11", "CMS");
    }

    @Test
    void cassandra50Java17G1ThroughBastion() throws Exception {
        assumeTrue(open("127.0.0.1", 2200) && open("127.0.0.1", 39042), "secure-50 not running");
        String id = connection("""
                {"name": "it-secure", "contactPoints": ["127.0.0.1:39042"], "localDatacenter": "dc1", "username": "cassandra",
                 "tls": {"enabled": true, "truststorePath": "%s", "truststoreType": "PEM", "hostnameVerification": false},
                 "jmx": {"method": "SSH_TUNNEL", "port": 7199}, "ssh": %s}""".formatted(pem, String.format(
                "{\"username\": \"studio\", \"port\": 2222, \"auth\": \"KEY\", \"keyPath\": \"%s\", "
                        + "\"strictHostKeyChecking\": false, \"jumpHost\": \"127.0.0.1\", \"jumpPort\": 2200, "
                        + "\"jumpUser\": \"studio\"}", sshKey)), Map.of(ConnectionConfig.SecretKeys.PASSWORD, "cassandra"));
        check(id, "10.231.42.31", "17", "G1");
    }

    @Test
    void cassandra311Java8Cms() throws Exception {
        // the 3.11 node advertises 0.0.0.0; its sshd sidecar is published on 127.0.0.1:2204
        assumeTrue(open("127.0.0.1", 2204) && open("127.0.0.1", 29042), "legacy-311 or its sshd sidecar not running");
        String id = connection("""
                {"name": "it-legacy", "contactPoints": ["127.0.0.1:29042"], "localDatacenter": "dc1",
                 "jmx": {"method": "DIRECT", "port": 27199}, "ssh": %s}""".formatted(String.format(
                "{\"username\": \"studio\", \"port\": 2204, \"auth\": \"KEY\", \"keyPath\": \"%s\", "
                        + "\"strictHostKeyChecking\": false}", sshKey)), Map.of());
        String node = engine.topology.info(id).nodes().get(0).address();
        check(id, node, "1.8", "CMS");
    }

    private void check(String id, String node, String java, String collector) throws Exception {
        GcLogFiles.Discovery d = svc.discover(id, node);
        assertThat(d.javaVersion()).isEqualTo(java);
        assertThat(d.configuredPath()).as("log path from the JVM options").isEqualTo("/opt/cassandra/logs/gc.log");
        assertThat(d.compressed()).isTrue();
        if (d.files().isEmpty()) {
            // log directory not mounted into the sidecar: check the compressed transfer on another file
            ConnectionConfig cfg = engine.connections.get(id);
            Map<String, String> secrets = engine.secretsFor(id);
            String plain = engine.shell.exec(cfg, secrets, node, "cat /etc/os-release", Duration.ofSeconds(20));
            String b64 = engine.shell.exec(cfg, secrets, node, "cat /etc/os-release | gzip -c | base64", Duration.ofSeconds(20));
            byte[] raw = new GZIPInputStream(new ByteArrayInputStream(Base64.getMimeDecoder().decode(b64))).readAllBytes();
            assertThat(new String(raw, StandardCharsets.UTF_8)).isEqualTo(plain);
            assumeTrue(false, "no GC log visible over SSH on " + node + ": " + d.note());
        }
        List<String> paths = d.files().stream().limit(2).map(GcLogFiles.RemoteFile::path).toList();
        Job job = svc.fetch(id, node, paths, null, d.javaVersion());
        Job done = engine.jobs.await(job.id(), 120_000);
        assertThat(done.state()).as(String.valueOf(done.error())).isEqualTo(Job.State.SUCCEEDED);
        String aid = String.valueOf(((Map<?, ?>) done.result()).get("analysisId"));
        GcReport r = svc.report(id, aid, null, null);
        assertThat(r.log().collector()).isEqualTo(collector);
        assertThat(r.summary().pauses().count()).isPositive();
        assertThat(r.events()).anyMatch(e -> e.heapAfterK != null);
        assertThat(r.findings()).isNotEmpty();
        assertThat(r.source().files()).hasSize(paths.size());
        try {
            svc.fetch(id, node, List.of("/etc/passwd"), null, null);
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(400);
        }
    }
}
