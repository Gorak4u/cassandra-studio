package com.cassandrastudio.engine.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cassandrastudio.engine.audit.AuditLog;
import com.cassandrastudio.engine.backup.BackupSettings.Privilege;
import com.cassandrastudio.engine.backup.BackupSettings.Provider;
import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.cql.ClusterService.ClusterInfo;
import com.cassandrastudio.engine.cql.ClusterService.NodeInfo;
import com.cassandrastudio.engine.guard.ActionGuard;
import com.cassandrastudio.engine.jobs.Job;
import com.cassandrastudio.engine.jobs.JobService;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.Environment;
import com.cassandrastudio.engine.model.ConnectionConfig.SecretKeys;
import com.cassandrastudio.engine.model.ConnectionConfig.Ssh;
import com.cassandrastudio.engine.model.ConnectionConfig.SshAuth;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.ssh.NodeShell;
import com.cassandrastudio.engine.ssh.TestSshServer;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The estate provider end to end without Docker: the test-env stub scripts (same CLI, output and
 * object layout as the estate's) run as real local processes behind an in-process SSH server,
 * driven through NodeShell, the detached runner, the job and the catalogue listing.
 */
class BackupServiceTest {
    @TempDir
    Path tmp;
    private TestSshServer sshd;
    private Database db;
    private ConnectionRepository repo;
    private JobService jobs;
    private NodeShell shell;
    private BackupService svc;
    private String id;
    private Path stubBin;
    private Path bucket;
    private NodeInfo node;

    static boolean tools() {
        for (String t : List.of("bash", "jq", "tar", "gzip", "setsid", "find")) {
            boolean found = Stream.of(System.getenv("PATH").split(":")).anyMatch(d -> Files.isExecutable(Path.of(d, t)));
            if (!found) return false;
        }
        return Files.isDirectory(Path.of("../test-env/estate-stub"));
    }

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(tools(), "bash, jq, tar, gzip, setsid and the test-env stub are needed");
        sshd = new TestSshServer();
        db = Database.inMemory();
        repo = new ConnectionRepository(db, SecretStores.inMemory());
        AuditLog audit = new AuditLog(db, "tester");
        jobs = new JobService(repo, audit);
        shell = new NodeShell();
        Ssh ssh = new Ssh(TestSshServer.USER, sshd.port(), SshAuth.PASSWORD, null, null, null, null, false, null);
        ConnectionConfig cfg = new ConnectionConfig(null, null, "c1", Environment.DEV, null, false, List.of("127.0.0.1"),
                "dc1", null, null, null, null, null, null, null, ssh, List.of(), null, null);
        id = repo.save(cfg, Map.of(SecretKeys.SSH_PASSWORD, TestSshServer.PASSWORD)).id();

        // The stub, with its config pointing at a temp data dir and a temp "bucket" (local backend).
        Path stub = tmp.resolve("stub");
        copy(Path.of("../test-env/estate-stub"), stub);
        stubBin = stub.resolve("usr/local/bin");
        Path data = tmp.resolve("data");
        Files.createDirectories(data.resolve("t5_backup/events-0001"));
        Files.writeString(data.resolve("t5_backup/events-0001/nb-1-big-Data.db"), "x".repeat(1000));
        Files.createDirectories(data.resolve("system_schema/tables-0002"));
        Files.writeString(data.resolve("system_schema/tables-0002/nb-1-big-Data.db"), "y");
        Files.createDirectories(data.resolve("system/local-0003"));
        bucket = tmp.resolve("bucket");
        ObjectNode conf = (ObjectNode) Json.MAPPER.readTree(stub.resolve("etc/backup/config.json").toFile());
        conf.put("cassandra_data_dir", data.toString());
        conf.put("storage_local_path", bucket.toString());
        conf.put("full_backup_log_file", tmp.resolve("log/full.log").toString());
        conf.put("incremental_backup_log_file", tmp.resolve("log/inc.log").toString());
        conf.put("stub_delay_per_table_sec", 0);
        Files.writeString(stub.resolve("etc/backup/config.json"), Json.write(conf));

        node = new NodeInfo("h1", "127.0.0.1", 9042, "dc1", "r1", "4.1.5", "UP", 16, "schema-v1", 1);
        NodeInfo down = new NodeInfo("h2", "127.0.0.2", 9042, "dc2", "r1", "4.1.5", "DOWN", 16, null, 0);
        ClusterInfo info = new ClusterInfo("acme-core", "Murmur3Partitioner", List.of("dc1", "dc2"), List.of(node, down),
                true, List.of("4.1.5"), "V5");
        BackupService.Nodes nodes = new BackupService.Nodes() {
            @Override
            public String exec(ConnectionConfig c, String host, String command, Duration timeout) {
                if (!host.equals("127.0.0.1")) throw new IllegalStateException("cannot reach " + host);
                return shell.exec(c, Map.of(SecretKeys.SSH_PASSWORD, TestSshServer.PASSWORD), host, command, timeout,
                        256 * 1024);
            }

            @Override
            public <T> T jmx(ConnectionConfig c, NodeInfo n, BackupService.JmxCall<T> call) {
                throw new IllegalStateException("no JMX in this test");
            }

            @Override
            public int expectedTables(String connectionId) {
                return 2;
            }
        };
        svc = new BackupService(db, repo, new ActionGuard(audit), jobs, connectionId -> info, nodes);
        svc.pollEvery = Duration.ofMillis(200);
        svc.saveSettings(id, new BackupSettings(Provider.ESTATE, stubBin.toString(),
                stub.resolve("etc/backup/config.json").toString(), Privilege.NONE, null, null, 5));
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t);
            }
        }
        try (Stream<Path> s = Files.list(to.resolve("usr/local/bin"))) {
            for (Path p : s.toList()) Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (svc == null) return;
        svc.close();
        jobs.close();
        shell.close();
        db.close();
        sshd.close();
    }

    private static final ActionGuard.Confirmation YES = new ActionGuard.Confirmation(true, null);

    @Test
    void runFullBackupThenSeeItInTheCatalogue() throws Exception {
        BackupService.RunRequest req = new BackupService.RunRequest(BackupService.Scope.CLUSTER, null, null, "full", 1,
                null, null, null);
        // Unconfirmed: 428 with the exact command per node as the preview, and the down node named.
        assertThatThrownBy(() -> svc.run(id, req, null)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(428);
            assertThat(e.details().get("preview").toString()).contains("127.0.0.1: '" + stubBin + "/full-backup-to-s3.sh'");
            assertThat(e.details().get("warnings").toString()).contains("127.0.0.2 is DOWN and will be skipped");
        });

        Job job = svc.run(id, req, YES);
        Job done = jobs.await(job.id(), 60_000);
        assertThat(done.error()).isNull();
        assertThat(done.state()).isEqualTo(Job.State.SUCCEEDED);
        assertThat(done.log()).anyMatch(l -> l.startsWith("[127.0.0.1] Summary: all 2 table(s)"));

        BackupService.RunStatus st = svc.runStatus(id, job.id());
        BackupService.NodeResult r = st.nodes().get(0);
        assertThat(r.state()).isEqualTo("SUCCEEDED");
        assertThat(r.exitCode()).isZero();
        assertThat(r.backupId()).matches(EstateFormat.TAG.pattern());
        assertThat(st.nodes().get(1).state()).isEqualTo("SKIPPED");

        BackupService.Catalogue cat = svc.catalogue(id);
        BackupEntry e = cat.backups().stream().filter(b -> b.id().equals(r.backupId())).findFirst().orElseThrow();
        assertThat(e.type()).isEqualTo("full");
        assertThat(e.status()).isEqualTo(BackupEntry.COMPLETE);
        assertThat(e.tables()).isEqualTo(2);
        assertThat(e.sizeBytes()).isPositive();
        assertThat(e.schemaVersion()).isEqualTo("schema-v1");
        assertThat(e.location()).isEqualTo(bucket + "/" + e.host() + "/" + r.backupId() + "/");
        assertThat(e.retention()).contains("not enforced by the local backend");
        assertThat(cat.nodes()).anyMatch(n -> n.node().equals("127.0.0.2") && !n.ok());
        assertThat(cat.nodes()).anyMatch(n -> n.node().equals("127.0.0.1") && n.ok()
                && n.method().equals("backup-storage-lib.sh listing"));
    }

    @Test
    void cancelStopsTheRemoteScript() throws Exception {
        Path cfg = Path.of(svc.settings(id).configFile());
        ObjectNode conf = (ObjectNode) Json.MAPPER.readTree(cfg.toFile());
        conf.put("stub_delay_per_table_sec", 30);
        Files.writeString(cfg, Json.write(conf));
        Job job = svc.run(id, new BackupService.RunRequest(BackupService.Scope.NODE, null, "127.0.0.1", "full", null,
                null, null, null), YES);
        assertThat(job.cancellable()).isTrue();
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline
                && jobs.get(job.id()).log().stream().noneMatch(l -> l.contains("Backing up table"))) {
            Thread.sleep(100);
        }
        jobs.cancel(job.id());
        Job done = jobs.await(job.id(), 30_000);
        assertThat(done.state()).isEqualTo(Job.State.CANCELLED);
        assertThat(svc.runStatus(id, job.id()).nodes().get(0).state()).isEqualTo("CANCELLED");
        // The script's process group is gone: its lock file was removed by its EXIT trap or it is stale.
        String ps = shell.exec(repo.get(id), Map.of(SecretKeys.SSH_PASSWORD, TestSshServer.PASSWORD), "127.0.0.1",
                // [f] keeps the pattern from matching this very shell's command line.
                "sleep 1; pgrep -f " + NodeShell.quote(stubBin + "/[f]ull-backup-to-s3.sh") + " || echo none",
                Duration.ofSeconds(10));
        assertThat(ps.strip()).isEqualTo("none");
    }

    @Test
    void failingScriptReportsItsErrorPerNode() throws Exception {
        Path cfg = Path.of(svc.settings(id).configFile());
        ObjectNode conf = (ObjectNode) Json.MAPPER.readTree(cfg.toFile());
        conf.put("cassandra_data_dir", tmp.resolve("missing").toString());
        Files.writeString(cfg, Json.write(conf));
        Job job = svc.run(id, new BackupService.RunRequest(BackupService.Scope.DC, "dc1", null, "full", null, null,
                null, null), YES);
        Job done = jobs.await(job.id(), 30_000);
        assertThat(done.state()).isEqualTo(Job.State.FAILED);
        assertThat(done.error()).contains("1 of 1 nodes failed").contains("does not exist");
        BackupService.NodeResult r = svc.runStatus(id, job.id()).nodes().get(0);
        assertThat(r.exitCode()).isEqualTo(1);
        assertThat(r.message()).startsWith("exited with 1: cassandra_data_dir");
    }

    @Test
    void detectFindsTheScripts() {
        BackupService.Detection d = svc.detect(id);
        BackupService.NodeDetection n = d.nodes().stream().filter(x -> x.node().equals("127.0.0.1")).findFirst().orElseThrow();
        assertThat(n.reachable()).isTrue();
        assertThat(n.scripts()).contains("full-backup-to-s3.sh", "backup-storage-lib.sh", "backup-status.sh");
        assertThat(n.configPresent()).isTrue();
        assertThat(d.nodes().stream().filter(x -> x.node().equals("127.0.0.2")).findFirst().orElseThrow().reachable()).isFalse();
        assertThat(d.recommended()).isEqualTo(Provider.ESTATE);
        assertThat(d.jmxSnapshots()).isFalse();
    }

    @Test
    void validation() {
        assertThatThrownBy(() -> svc.run(id, new BackupService.RunRequest(BackupService.Scope.CLUSTER, null, null,
                "differential", null, null, null, null), YES)).hasMessageContaining("full or incremental");
        assertThatThrownBy(() -> svc.run(id, new BackupService.RunRequest(BackupService.Scope.CLUSTER, null, null,
                "full", 0, null, null, null), YES)).hasMessageContaining("concurrency");
        assertThatThrownBy(() -> svc.run(id, new BackupService.RunRequest(BackupService.Scope.CLUSTER, null, null,
                "full", 1, "1M/s && reboot", null, null), YES)).hasMessageContaining("throttle");
        assertThatThrownBy(() -> svc.run(id, new BackupService.RunRequest(BackupService.Scope.DC, "dc2", null,
                "full", 1, null, null, null), YES)).hasMessageContaining("None of the selected nodes is up");
        assertThatThrownBy(() -> svc.run(id, new BackupService.RunRequest(BackupService.Scope.NODE, null, "10.9.9.9",
                "full", 1, null, null, null), YES)).hasMessageContaining("Unknown node");
    }
}
