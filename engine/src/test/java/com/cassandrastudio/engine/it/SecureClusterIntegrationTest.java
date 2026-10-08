package com.cassandrastudio.engine.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.cql.QueryService.QueryRequest;
import com.cassandrastudio.engine.cql.QueryService.StatementResult;
import com.cassandrastudio.engine.cql.SessionManager.TestResult;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.Environment;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.security.RoleService;
import com.cassandrastudio.engine.store.Database;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * The estate's normal setup: client TLS (client_encryption_options) plus
 * PasswordAuthenticator and CassandraAuthorizer, on every version under test.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecureClusterIntegrationTest {
    private static final String STORE_PASSWORD = "changeit";
    private final Map<String, GenericContainer<?>> containers = new HashMap<>();
    private Path certDir;
    private Engine engine;

    static Stream<String> versions() {
        return CassandraClusters.versions().stream();
    }

    @BeforeAll
    void setUp() throws Exception {
        certDir = Files.createTempDirectory("studio-certs");
        // Node key pair (JKS so Cassandra 3.11 on Java 8 reads it) and its certificate as PEM for the client.
        run("keytool", "-genkeypair", "-alias", "node", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
                "-dname", "CN=cassandra-test", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-keystore", certDir.resolve("node.jks").toString(), "-storetype", "JKS",
                "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD);
        run("keytool", "-exportcert", "-rfc", "-alias", "node", "-keystore", certDir.resolve("node.jks").toString(),
                "-storepass", STORE_PASSWORD, "-file", certDir.resolve("node.pem").toString());
        engine = new Engine(Database.inMemory(), SecretStores.inMemory(), "it");
    }

    @AfterAll
    void tearDown() {
        engine.close();
        containers.values().forEach(GenericContainer::stop);
    }

    private static void run(String... cmd) throws Exception {
        String keytool = Path.of(System.getProperty("java.home"), "bin", cmd[0]).toString();
        cmd[0] = keytool;
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) throw new IllegalStateException("keytool failed: " + out);
    }

    @SuppressWarnings("resource")
    private GenericContainer<?> cluster(String version) {
        return containers.computeIfAbsent(version, v -> {
            // Enable client TLS and password auth by editing the stock cassandra.yaml, which works for
            // 3.11, 4.x and 5.0 alike (5.0 nests the authenticator class and ships keystore_password commented out).
            String script = String.join(" && ",
                    "CONF=/etc/cassandra/cassandra.yaml",
                    "sed -i 's/AllowAllAuthenticator/PasswordAuthenticator/; s/AllowAllAuthorizer/CassandraAuthorizer/' $CONF",
                    "sed -i '/^client_encryption_options:/,/^[a-z]/ { s/^\\(\\s*\\)enabled: false/\\1enabled: true/; "
                            + "s#^\\(\\s*\\)keystore: .*#\\1keystore: /certs/node.jks#; "
                            + "s/^\\(\\s*\\)#\\{0,1\\}keystore_password: .*/\\1keystore_password: " + STORE_PASSWORD + "/ }' $CONF",
                    "exec docker-entrypoint.sh cassandra -f");
            GenericContainer<?> c = new GenericContainer<>("cassandra:" + v)
                    .withEnv("MAX_HEAP_SIZE", "768M")
                    .withEnv("HEAP_NEWSIZE", "128M")
                    .withEnv("CASSANDRA_DC", "dc1")
                    .withEnv("CASSANDRA_ENDPOINT_SNITCH", "GossipingPropertyFileSnitch")
                    .withCopyFileToContainer(MountableFile.forHostPath(certDir.resolve("node.jks")), "/certs/node.jks")
                    .withExposedPorts(9042)
                    .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("bash", "-c", script))
                    // Port open is enough here: testUntilReady() retries until CQL and login work.
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(4)));
            c.start();
            return c;
        });
    }

    private ConnectionConfig config(String version, boolean tls) {
        GenericContainer<?> c = cluster(version);
        ConnectionConfig.Tls t = tls
                ? new ConnectionConfig.Tls(true, certDir.resolve("node.pem").toString(), "PEM", null, null, false)
                : null;
        return new ConnectionConfig(null, null, "secure-" + version, Environment.DEV, null, false,
                List.of(c.getHost() + ":" + c.getMappedPort(9042)), "dc1", "cassandra", t, null, "ONE", 30_000, null,
                null, null, null, null, null);
    }

    /** CQL comes up after the port opens, and the default superuser a few seconds after that. */
    private TestResult testUntilAuthReady(ConnectionConfig cfg, Map<String, String> secrets) throws InterruptedException {
        TestResult r = null;
        long deadline = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < deadline) {
            r = engine.sessions.test(cfg, secrets);
            if (r.ok()) return r;
            Thread.sleep(3_000);
        }
        return r;
    }

    @ParameterizedTest
    @MethodSource("versions")
    void tlsAndPasswordAuth(String version) throws Exception {
        ConnectionConfig cfg = config(version, true);
        TestResult ok = testUntilAuthReady(cfg, Map.of("password", "cassandra"));
        assertThat(ok.ok()).as(ok.error()).isTrue();
        assertThat(ok.version()).startsWith(version);

        TestResult wrongPassword = engine.sessions.test(cfg, Map.of("password", "wrong"));
        assertThat(wrongPassword.ok()).isFalse();
        assertThat(wrongPassword.error()).containsIgnoringCase("auth");

        assertThat(testUntilAuthReady(cfg, Map.of("password", "cassandra")).ok()).isTrue();
        TestResult plaintextToTlsPort = engine.sessions.test(config(version, false), Map.of("password", "cassandra"));
        assertThat(plaintextToTlsPort.ok()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("versions")
    void rolesAndPermissionsWithRealAuthorizer(String version) throws Exception {
        ConnectionConfig cfg = config(version, true);
        assertThat(testUntilAuthReady(cfg, Map.of("password", "cassandra")).ok()).isTrue();
        String id = engine.connections.save(cfg, Map.of("password", "cassandra")).id();
        String ks = "sec_" + version.replace('.', '_');
        String role = "reader_" + version.replace('.', '_');
        String script = String.join("\n",
                "CREATE KEYSPACE IF NOT EXISTS " + ks + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};",
                RoleService.createRole(new RoleService.RoleSpec(role, "s3cret", true, false, true)),
                RoleService.grantPermission("SELECT", "KEYSPACE", ks, role, false));
        List<StatementResult> results = engine.queries.execute(id, new QueryRequest(script, null, null, null, null, null,
                null, false, null, true, null, true, null)).results();
        assertThat(results).allSatisfy(r -> assertThat(r.error()).as(r.statement()).isNull());

        RoleService.RolesView view = engine.roles.list(id);
        assertThat(view.rolesError()).isNull();
        assertThat(view.permissionsError()).isNull();
        assertThat(view.roles()).anyMatch(r -> r.name().equals("cassandra") && r.superuser());
        assertThat(view.roles()).anyMatch(r -> r.name().equals(role) && r.login() && !r.superuser());
        assertThat(engine.roles.permissionsOf(id, role)).anyMatch(p -> p.permission().equals("SELECT") && p.resource().contains(ks));

        // The new role can log in over TLS; its password never reaches history or audit.
        ConnectionConfig asReader = new ConnectionConfig(null, null, "reader", Environment.DEV, null, false, cfg.contactPoints(),
                "dc1", role, cfg.tls(), null, "ONE", 30_000, null, null, null, null, null, null);
        assertThat(engine.sessions.test(asReader, Map.of("password", "s3cret")).ok()).isTrue();
        assertThat(engine.queries.history(id, "", 100)).noneMatch(h -> h.statement().contains("s3cret"));
        assertThat(engine.audit.search(id, null, null, 100)).noneMatch(a -> a.detail() != null && a.detail().contains("s3cret"));
    }
}
