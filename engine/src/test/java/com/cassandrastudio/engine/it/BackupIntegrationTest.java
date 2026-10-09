package com.cassandrastudio.engine.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cassandrastudio.engine.Engine;
import com.cassandrastudio.engine.api.BackupRoutes;
import com.cassandrastudio.engine.backup.BackupEntry;
import com.cassandrastudio.engine.backup.BackupService;
import com.cassandrastudio.engine.backup.BackupSettings;
import com.cassandrastudio.engine.backup.BackupSettings.Privilege;
import com.cassandrastudio.engine.backup.BackupSettings.Provider;
import com.cassandrastudio.engine.guard.ActionGuard;
import com.cassandrastudio.engine.jobs.Job;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.Environment;
import com.cassandrastudio.engine.model.ConnectionConfig.Jmx;
import com.cassandrastudio.engine.model.ConnectionConfig.JmxMethod;
import com.cassandrastudio.engine.model.ConnectionConfig.SecretKeys;
import com.cassandrastudio.engine.model.ConnectionConfig.Ssh;
import com.cassandrastudio.engine.model.ConnectionConfig.SshAuth;
import com.cassandrastudio.engine.model.ConnectionConfig.Tls;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.ssh.NodeShell;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Backups against the running test-env: snapshots over JMX on 3.11 (direct), 4.1 (SSH tunnel) and
 * 5.0 (through the bastion), and acceptance criterion 10 on acme-core: a full backup through the
 * estate scripts (the test-env stub, same CLI and output), then the backups in the catalogue.
 * Without the estate-stub mount the stub is copied into the SSH user's home and used from there.
 * Writes only keyspace t5_backup. Skipped when the test-env is not running.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackupIntegrationTest {
    private static final ActionGuard.Confirmation YES = new ActionGuard.Confirmation(true, null);
    private static final List<String> ACME = List.of("10.231.42.11", "10.231.42.12", "10.231.42.13");
    private final Path sshDir = Path.of(System.getenv().getOrDefault("STUDIO_IT_SSH_DIR", "../test-env/ssh"));
    private Engine engine;
    private BackupService svc;

    private static boolean listening(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @BeforeAll
    void setUp() {
        assumeTrue(listening("10.231.42.11", 2222) && listening("127.0.0.1", 19042) && Files.exists(sshDir.resolve("id_test")),
                "test-env with --profile jmx is not running");
        engine = new Engine(Database.inMemory(), SecretStores.inMemory(), "it");
        svc = new BackupService(engine.db, engine.connections, engine.guard, engine.jobs, engine.topology,
                BackupRoutes.nodes(engine));
        svc.pollEvery(Duration.ofMillis(500));
    }

    @AfterAll
    void tearDown() {
        if (svc != null) svc.close();
        if (engine != null) engine.close();
    }

    private Ssh ssh(String jumpHost, Integer jumpPort) {
        return new Ssh("studio", 2222, SshAuth.KEY, sshDir.resolve("id_test").toAbsolutePath().toString(), jumpHost, jumpPort,
                jumpHost == null ? null : "studio", false, null);
    }

    private String save(String name, String contact, String dc, Jmx jmx, Ssh ssh, Tls tls, String user, Map<String, String> secrets) {
        ConnectionConfig c = new ConnectionConfig(null, null, name, Environment.DEV, null, false, List.of(contact), dc, user,
                tls, null, null, null, null, jmx, ssh, List.of(), null, null);
        return engine.connections.save(c, secrets).id();
    }

    private String acme() {
        return save("acme-core", "127.0.0.1:19042", "dc_east", new Jmx(JmxMethod.SSH_TUNNEL, 7199, null, false, null, null),
                ssh(null, null), null, null, Map.of());
    }

    private void snapshotRoundTrip(String id, String node) throws Exception {
        svc.saveSettings(id, new BackupSettings(Provider.SNAPSHOT, null, null, null, null, null, null));
        String tag = "t5-it-" + System.currentTimeMillis();
        Job job = svc.run(id, new BackupService.RunRequest(BackupService.Scope.NODE, null, node, "snapshot", null, null,
                tag, List.of("t5_backup")), YES);
        Job done = engine.jobs.await(job.id(), 120_000);
        assertThat(done.error()).isNull();
        assertThat(done.state()).isEqualTo(Job.State.SUCCEEDED);
        BackupService.Catalogue cat = svc.catalogue(id);
        BackupEntry e = cat.backups().stream().filter(b -> b.id().equals(tag)).findFirst()
                .orElseThrow(() -> new AssertionError("not in catalogue: " + cat.nodes()));
        assertThat(e.node()).isEqualTo(node);
        assertThat(e.type()).isEqualTo("snapshot");
        assertThat(e.notes()).contains("t5_backup");
        assertThat(e.schemaVersion()).isNotNull();
        svc.clearSnapshot(id, node, tag, YES);
        // Also leftovers of earlier interrupted runs, so the test keyspace does not collect snapshots.
        for (BackupEntry old : svc.catalogue(id).backups()) {
            if (old.id().startsWith("t5-it-") && node.equals(old.node())) svc.clearSnapshot(id, node, old.id(), YES);
        }
        assertThat(svc.catalogue(id).backups()).noneMatch(b -> b.id().startsWith("t5-it-") && node.equals(b.node()));
    }

    private void createKeyspace(String id, String replication) {
        var s = engine.sessions.session(id);
        s.execute("CREATE KEYSPACE IF NOT EXISTS t5_backup WITH replication = " + replication);
        s.execute("CREATE TABLE IF NOT EXISTS t5_backup.events (id int PRIMARY KEY, body text)");
        for (int i = 0; i < 50; i++) s.execute("INSERT INTO t5_backup.events (id, body) VALUES (" + i + ", 'event " + i + "')");
    }

    @Test
    void snapshots41ThroughSshTunnel() throws Exception {
        String id = acme();
        createKeyspace(id, "{'class': 'NetworkTopologyStrategy', 'dc_east': 2, 'dc_west': 1}");
        snapshotRoundTrip(id, "10.231.42.11");
    }

    @Test
    void snapshots311DirectJmx() throws Exception {
        assumeTrue(listening("127.0.0.1", 29042) && listening("127.0.0.1", 27199), "legacy-311 not running");
        String id = save("legacy-311", "127.0.0.1:29042", "dc1", new Jmx(JmxMethod.DIRECT, 27199, null, false, null, null),
                null, null, null, Map.of());
        createKeyspace(id, "{'class': 'SimpleStrategy', 'replication_factor': 1}");
        String node = engine.topology.info(id).nodes().get(0).address();
        snapshotRoundTrip(id, node);
    }

    @Test
    void snapshots50ThroughBastion() throws Exception {
        assumeTrue(listening("127.0.0.1", 39042) && listening("127.0.0.1", 2200), "secure-50 not running");
        Path pem = Path.of(System.getenv().getOrDefault("STUDIO_IT_CERT", "../test-env/certs/node.pem"));
        String id = save("secure-50", "127.0.0.1:39042", "dc1", new Jmx(JmxMethod.SSH_TUNNEL, 7199, null, false, null, null),
                ssh("127.0.0.1", 2200), new Tls(true, pem.toAbsolutePath().toString(), "PEM", null, null, false), "cassandra",
                Map.of(SecretKeys.PASSWORD, "cassandra"));
        createKeyspace(id, "{'class': 'SimpleStrategy', 'replication_factor': 1}");
        snapshotRoundTrip(id, "10.231.42.31");
    }

    /** Acceptance criterion 10: run a backup through the estate scripts and see it in the catalogue. */
    @Test
    void estateFullBackupOnTheClusterThenCatalogue() throws Exception {
        String id = acme();
        createKeyspace(id, "{'class': 'NetworkTopologyStrategy', 'dc_east': 2, 'dc_west': 1}");
        ConnectionConfig cfg = engine.connections.get(id);
        // Prefer the mounted stub (/usr/local/bin + /etc/backup); else copy it to the SSH user's home.
        String probe = engine.shell.exec(cfg, Map.of(), ACME.get(0),
                "[ -x /usr/local/bin/full-backup-to-s3.sh ] && [ -f /etc/backup/config.json ] && echo MOUNTED || echo HOME",
                Duration.ofSeconds(20)).strip();
        BackupSettings settings;
        if (probe.equals("MOUNTED")) {
            settings = new BackupSettings(Provider.ESTATE, "/usr/local/bin", "/etc/backup/config.json", Privilege.NONE,
                    null, null, 30);
        } else {
            for (String node : ACME) installStubInHome(cfg, node);
            String home = engine.shell.exec(cfg, Map.of(), ACME.get(0), "cd && pwd", Duration.ofSeconds(10)).strip();
            settings = new BackupSettings(Provider.ESTATE, home + "/estate-stub/usr/local/bin",
                    home + "/estate-stub/etc/backup/config.json", Privilege.NONE, null, null, 30);
        }
        svc.saveSettings(id, settings);
        BackupService.Detection d = svc.detect(id);
        assertThat(d.recommended()).as(d.toString()).isEqualTo(Provider.ESTATE);

        Job job = svc.run(id, new BackupService.RunRequest(BackupService.Scope.CLUSTER, null, null, "full", 2, null, null,
                null), YES);
        Job done = engine.jobs.await(job.id(), 300_000);
        assertThat(done.error()).isNull();
        assertThat(done.state()).isEqualTo(Job.State.SUCCEEDED);
        BackupService.RunStatus st = svc.runStatus(id, job.id());
        assertThat(st.nodes()).hasSize(3).allMatch(n -> n.state().equals("SUCCEEDED") && n.backupId() != null);

        BackupService.Catalogue cat = svc.catalogue(id);
        assertThat(cat.nodes()).allMatch(BackupService.NodeListing::ok);
        for (BackupService.NodeResult r : st.nodes()) {
            BackupEntry e = cat.backups().stream().filter(b -> b.id().equals(r.backupId()) && r.node().equals(b.node()))
                    .findFirst().orElseThrow();
            assertThat(e.type()).isEqualTo("full");
            assertThat(e.status()).isEqualTo(BackupEntry.COMPLETE);
            assertThat(e.sizeBytes()).isPositive();
            assertThat(e.tables()).isPositive();
            assertThat(e.schemaVersion()).isNotNull();
            assertThat(e.datacenter()).isEqualTo(r.datacenter());
        }
    }

    private void installStubInHome(ConnectionConfig cfg, String node) throws IOException {
        Path stub = Path.of("../test-env/estate-stub");
        String home = "$HOME/estate-stub";
        StringBuilder cmd = new StringBuilder("mkdir -p " + home + "/usr/local/bin " + home + "/etc/backup");
        engine.shell.exec(cfg, Map.of(), node, cmd.toString(), Duration.ofSeconds(20));
        for (String f : List.of("full-backup-to-s3.sh", "incremental-backup-to-s3.sh", "backup-status.sh",
                "restore-from-s3.sh", "backup-storage-lib.sh")) {
            put(cfg, node, Files.readAllBytes(stub.resolve("usr/local/bin").resolve(f)), home + "/usr/local/bin/" + f, true);
        }
        ObjectNode conf = (ObjectNode) Json.MAPPER.readTree(stub.resolve("etc/backup/config.json").toFile());
        String userHome = engine.shell.exec(cfg, Map.of(), node, "cd && pwd", Duration.ofSeconds(10)).strip();
        conf.put("storage_local_path", userHome + "/estate-backups");
        put(cfg, node, Json.write(conf).getBytes(), home + "/etc/backup/config.json", false);
    }

    private void put(ConnectionConfig cfg, String node, byte[] content, String path, boolean exec) {
        String b64 = Base64.getEncoder().encodeToString(content);
        engine.shell.exec(cfg, Map.of(), node, "printf '%s' " + NodeShell.quote(b64) + " | base64 -d > \"" + path + "\""
                + (exec ? " && chmod 755 \"" + path + "\"" : ""), Duration.ofSeconds(30));
    }
}
