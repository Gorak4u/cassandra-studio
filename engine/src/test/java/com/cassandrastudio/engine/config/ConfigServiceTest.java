package com.cassandrastudio.engine.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.audit.AuditLog;
import com.cassandrastudio.engine.config.ConfigModel.DriftReport;
import com.cassandrastudio.engine.config.ConfigModel.DriftRow;
import com.cassandrastudio.engine.config.ConfigModel.HieraSettings;
import com.cassandrastudio.engine.config.ConfigModel.NodeConfig;
import com.cassandrastudio.engine.config.ConfigModel.Setting;
import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.cql.ClusterService.ClusterInfo;
import com.cassandrastudio.engine.cql.ClusterService.NodeInfo;
import com.cassandrastudio.engine.jobs.Job;
import com.cassandrastudio.engine.jobs.JobService;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.Environment;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.ApiException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Collect as a job with a fake per-node reader, then the drift report with and without Hiera. */
class ConfigServiceTest {
    @TempDir
    Path dir;
    private Database db;
    private JobService jobs;
    private ConfigService svc;
    private String id;

    private static final List<NodeInfo> NODES = List.of(
            new NodeInfo("h1", "10.0.0.1", 9042, "dc_east", "r1", "4.1.12", "UP", 16, null, 1),
            new NodeInfo("h2", "10.0.0.2", 9042, "dc_east", "r1", "4.1.12", "UP", 16, null, 1),
            new NodeInfo("h3", "10.0.0.3", 9042, "dc_west", "r1", "3.11.19", "UP", 16, null, 1));

    static Setting yaml(String name, String value) {
        return new Setting(ConfigModel.YAML, name, value, name, value, "test");
    }

    /** Same config everywhere except compaction throughput on node 2, plus per-node addresses. */
    static NodeConfig node(NodeInfo n) {
        List<Setting> s = new ArrayList<>(List.of(
                yaml("cluster_name", "acme-core"),
                yaml("num_tokens", "16"),
                yaml("compaction_throughput", n.address().equals("10.0.0.2") ? "17MiB/s" : "64MiB/s"),
                yaml("listen_address", n.address()),
                new Setting(ConfigModel.JVM, "-Xmx", n.datacenter().equals("dc_west") ? "4GiB" : "8GiB", "-Xmx", "", "t")));
        if (n.version().startsWith("4")) s.add(yaml("uuid_sstable_identifiers_enabled", "false"));
        return new NodeConfig(n.address(), n.hostId(), n.datacenter(), n.rack(), n.version(), Map.of(), s, List.of());
    }

    @BeforeEach
    void setUp() {
        db = Database.inMemory();
        ConnectionRepository repo = new ConnectionRepository(db, SecretStores.inMemory());
        id = repo.save(new ConnectionConfig(null, null, "acme", Environment.DEV, null, false, List.of("10.0.0.1"), "dc_east",
                null, null, null, null, null, null, null, null, List.of(), null, null), Map.of()).id();
        jobs = new JobService(repo, new AuditLog(db, "tester"));
        svc = new ConfigService(db, repo, connId -> new ClusterInfo("acme-core", null, List.of("dc_east", "dc_west"), NODES,
                true, List.of(), null), jobs, connId -> Map.of(), connId -> (cfg, secrets, n) -> {
                    if (n.address().equals("10.0.0.9")) throw new IllegalStateException("boom");
                    return node(n);
                });
    }

    @AfterEach
    void tearDown() {
        svc.close();
        jobs.close();
        db.close();
    }

    private static DriftRow row(DriftReport r, String name) {
        return r.rows().stream().filter(x -> x.name().equals(name)).findFirst().orElse(null);
    }

    @Test
    void collectThenDrift() throws Exception {
        assertThatThrownBy(() -> svc.snapshot(id)).isInstanceOf(ApiException.class).hasMessageContaining("Collect");
        Job job = svc.collect(id);
        Job done = jobs.await(job.id(), 10_000);
        assertThat(done.state()).isEqualTo(com.cassandrastudio.engine.jobs.Job.State.SUCCEEDED);
        assertThat(svc.snapshot(id).nodes()).hasSize(3);

        svc.setHieraSettings(id, new HieraSettings(false, "/nowhere", Map.of(), Map.of()));
        DriftReport cluster = svc.drift(id, "cluster", true, true);
        assertThat(cluster.rows()).extracting(DriftRow::name).containsExactly("compaction_throughput", "-Xmx");
        DriftRow ct = row(cluster, "compaction_throughput");
        assertThat(ct.values()).containsEntry("10.0.0.2", "17MiB/s").containsEntry("10.0.0.1", "64MiB/s");
        assertThat(ct.dcsDiffering()).containsExactly("dc_east");
        // only within a DC: the heap differs between DCs, not inside one
        DriftReport dc = svc.drift(id, "dc", true, true);
        assertThat(dc.rows()).extracting(DriftRow::name).containsExactly("compaction_throughput");
        assertThat(dc.summary().differInCluster()).isEqualTo(2);
        assertThat(dc.summary().differInDc()).isEqualTo(1);
        // everything: per-node settings shown but never drift; a 4.1-only setting is missing on 3.11, not drift
        DriftReport all = svc.drift(id, "cluster", false, false);
        assertThat(row(all, "listen_address").perNode()).isTrue();
        assertThat(row(all, "listen_address").differsInCluster()).isFalse();
        assertThat(row(all, "uuid_sstable_identifiers_enabled").missingOn()).containsExactly("10.0.0.3");
        assertThat(row(all, "uuid_sstable_identifiers_enabled").differsInCluster()).isFalse();
        assertThatThrownBy(() -> svc.drift(id, "rack", true, true)).hasMessageContaining("scope");
    }

    @Test
    void hieraComparison() throws Exception {
        Path repo = HieraTest.miniRepo(dir.resolve("repo"));
        svc.put(id, new ConfigModel.Snapshot(1L, NODES.stream().map(ConfigServiceTest::node).toList()));
        svc.setHieraSettings(id, new HieraSettings(true, repo.toString(),
                Map.of("customer", "acme", "environment", "prod", "product", "cassandra", "cluster", "core"),
                Map.of("10.0.0.3", "west1.example")));
        DriftReport r = svc.drift(id, "cluster", true, true);
        assertThat(r.hiera().error()).isNull();
        assertThat(r.hiera().layersByNode().get("10.0.0.3")).contains("nodes/west1.example.yaml");
        // the datacenter fact defaults to each node's DC
        assertThat(r.hiera().layersByNode().get("10.0.0.1"))
                .contains("customers/acme/prod/products/cassandra/clusters/core.yaml");
        DriftRow name = row(r, "cluster_name");
        assertThat(name.expected()).containsEntry("10.0.0.1", "acme-prod-core");
        assertThat(name.mismatches()).containsExactly("10.0.0.1", "10.0.0.2", "10.0.0.3");
        assertThat(row(r, "num_tokens")).isNull(); // matches Hiera and the other nodes
        DriftRow ct = row(r, "compaction_throughput");
        assertThat(ct.expected()).containsEntry("10.0.0.1", "32MiB/s").containsEntry("10.0.0.3", "128MiB/s");
        assertThat(r.summary().hieraMismatches()).isPositive();

        svc.setHieraSettings(id, new HieraSettings(true, dir.resolve("none").toString(), Map.of(), Map.of()));
        assertThat(svc.drift(id, "cluster", true, true).hiera().error()).contains("not found");
    }

    @Test
    void hieraSettingsValidation() {
        HieraSettings d = svc.hieraSettings(id);
        assertThat(d.repoPath()).isEqualTo(ConfigService.DEFAULT_REPO);
        assertThat(d.facts()).containsEntry("product", "cassandra");
        assertThatThrownBy(() -> svc.setHieraSettings(id, new HieraSettings(true, "relative", Map.of(), Map.of())))
                .hasMessageContaining("absolute");
        assertThatThrownBy(() -> svc.setHieraSettings(id, new HieraSettings(true, "/r", Map.of("customer", "../x"), Map.of())))
                .hasMessageContaining("must not contain");
        HieraSettings s = svc.setHieraSettings(id, new HieraSettings(true, "/r", Map.of("customer", " acme ", "role", ""),
                Map.of("10.0.0.1", "east1.example")));
        assertThat(svc.hieraSettings(id)).isEqualTo(s);
        assertThat(s.facts()).containsExactly(Map.entry("customer", "acme"));
        assertThat(svc.hieraOptions(id, "/does/not/exist").found()).isFalse();
    }
}
