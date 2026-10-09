package com.cassandrastudio.engine.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.config.ConfigModel.Setting;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * YAML reading and name/unit normalisation against real fixtures: the stock cassandra.yaml of
 * the 3.11 test node and the system_views.settings rows of a 4.1 node.
 */
class SettingsParsingTest {
    static String resource(String name) throws IOException {
        try (InputStream in = SettingsParsingTest.class.getResourceAsStream("/config/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static Map<String, String> settings41() throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : resource("settings-4.1.tsv").split("\n")) {
            if (line.isBlank()) continue;
            String[] p = line.split("\t", 2);
            m.put(p[0], p.length > 1 ? p[1] : "");
        }
        return m;
    }

    static Map<String, Setting> byName(List<Setting> s) {
        return s.stream().collect(Collectors.toMap(Setting::name, Function.identity()));
    }

    @Test
    void yamlSubset() {
        Object v = YamlLite.parse("""
                ---
                # comment
                a: 1   # trailing
                b: 'it''s'
                c: "x: y"
                d:
                  - one
                  - "two"
                e: [p, 'q r', {k: v}]
                f: {x: 1, y: [2, 3]}
                g: ~
                h:
                - k: v
                  parameters:
                      - seeds: "10.0.0.1,10.0.0.2"
                base: &base
                  x: 1
                ref: *base
                merged:
                  <<: *base
                  y: 2
                text: |
                  line1
                  line2
                folded: >-
                  a
                  b
                url: http://example.com/x#frag
                last: end
                """);
        assertThat(v).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        assertThat(m.get("a")).isEqualTo("1");
        assertThat(m.get("b")).isEqualTo("it's");
        assertThat(m.get("c")).isEqualTo("x: y");
        assertThat(m.get("d")).isEqualTo(List.of("one", "two"));
        assertThat(m.get("e")).isEqualTo(List.of("p", "q r", Map.of("k", "v")));
        assertThat(m.get("f")).isEqualTo(Map.of("x", "1", "y", List.of("2", "3")));
        assertThat(m).containsEntry("g", null);
        assertThat(m.get("h")).isEqualTo(List.of(Map.of("k", "v", "parameters", List.of(Map.of("seeds", "10.0.0.1,10.0.0.2")))));
        assertThat(m.get("ref")).isEqualTo(Map.of("x", "1"));
        assertThat(m.get("merged")).isEqualTo(Map.of("x", "1", "y", "2"));
        assertThat(m.get("text")).isEqualTo("line1\nline2\n");
        assertThat(m.get("folded")).isEqualTo("a b");
        assertThat(m.get("url")).isEqualTo("http://example.com/x#frag");
        assertThat(m.get("last")).isEqualTo("end");
        assertThat(YamlLite.parse("")).isNull();
        assertThatThrownBy(() -> YamlLite.parse("a: *nope")).hasMessageContaining("Unknown alias");
        assertThatThrownBy(() -> YamlLite.parseMap("- a\n- b")).hasMessageContaining("not a mapping");
    }

    @Test
    void namesAndUnits() {
        assertThat(SettingNames.canonical("read_request_timeout_in_ms")).isEqualTo("read_request_timeout");
        assertThat(SettingNames.canonical("enable_user_defined_functions")).isEqualTo("user_defined_functions_enabled");
        // 4.1 then 5.0
        assertThat(SettingNames.canonical("compaction_large_partition_warning_threshold_mb"))
                .isEqualTo("partition_size_warn_threshold");
        assertThat(SettingNames.normalize("max_hint_window_in_ms", "10800000")).isEqualTo("3h");
        assertThat(SettingNames.normalize("max_hint_window", "10800000ms")).isEqualTo("3h");
        assertThat(SettingNames.normalize("compaction_throughput_mb_per_sec", "64")).isEqualTo("64MiB/s");
        assertThat(SettingNames.normalize("compaction_throughput", "64MiB/s")).isEqualTo("64MiB/s");
        assertThat(SettingNames.normalize("key_cache_save_period", "14400")).isEqualTo("4h");
        assertThat(SettingNames.normalize("key_cache_save_period", "4h")).isEqualTo("4h");
        assertThat(SettingNames.normalize("stream_throughput_outbound_megabits_per_sec", "200")).isEqualTo("23.84MiB/s");
        assertThat(SettingNames.normalize("column_index_size_in_kb", "64")).isEqualTo("64KiB");
        assertThat(SettingNames.normalize("x", "1000.0")).isEqualTo("1000");
        assertThat(SettingNames.normalize("x", "TRUE")).isEqualTo("true");
        assertThat(SettingNames.normalize("x", "null")).isNull();
        assertThat(SettingNames.normalize("endpoint_snitch", "org.apache.cassandra.locator.GossipingPropertyFileSnitch"))
                .isEqualTo("GossipingPropertyFileSnitch");
        assertThat(SettingNames.normalize("commitlog_sync_group_window_in_ms", "0.0")).isEqualTo("0ms");
        assertThat(SettingNames.normalize("credentials_update_interval_in_ms", "-1")).isEqualTo("-1");
        assertThat(SettingNames.normalize("data_file_directories", "[/a,  /b]")).isEqualTo("[/a, /b]");
        assertThat(SettingNames.seeds("\"b, a:7000,c\"")).isEqualTo("[a:7000, b, c]");
    }

    @Test
    void cassandra311FileAnd41TableCompare() throws IOException {
        Map<String, String> flat = SettingNames.flatten(YamlLite.parseMap(resource("cassandra-3.11.yaml")));
        Map<String, Setting> v311 = byName(SettingParsers.yaml(flat, "file"));
        Map<String, Setting> v41 = byName(SettingParsers.yaml(settings41(), "vt"));

        assertThat(v311.get("cluster_name").value()).isEqualTo("legacy-311");
        assertThat(v311.get("read_request_timeout").value()).isEqualTo("5s");
        assertThat(v311.get("read_request_timeout").rawName()).isEqualTo("read_request_timeout_in_ms");
        assertThat(v311).containsKey("seed_provider.seeds");
        assertThat(v311.get("seed_provider.class_name").value()).isEqualTo("SimpleSeedProvider");
        assertThat(v311.get("client_encryption_options.enabled").value()).isEqualTo("false");
        assertThat(v311.get("client_encryption_options.keystore_password").value()).isEqualTo(SettingNames.REDACTED);

        // 4.1 reports old and new names; the new one wins and the old one is not listed twice
        assertThat(v41).doesNotContainKey("read_request_timeout_in_ms").doesNotContainKey("compaction_throughput_mb_per_sec");
        assertThat(v41.get("compaction_throughput").rawName()).isEqualTo("compaction_throughput");
        assertThat(v41.get("seed_provider.seeds").value()).isEqualTo("[east1]");
        assertThat(v41.get("client_encryption_options.keystore").value()).isEqualTo(SettingNames.REDACTED);

        // the same defaults read the same across versions
        for (String name : List.of("read_request_timeout", "write_request_timeout", "max_hint_window",
                "hinted_handoff_throttle", "commitlog_sync_period", "batch_size_warn_threshold", "column_index_size",
                "key_cache_save_period", "gc_warn_threshold", "endpoint_snitch", "partitioner", "commitlog_sync",
                "user_defined_functions_enabled", "tombstone_warn_threshold", "concurrent_reads", "commitlog_segment_size")) {
            assertThat(v311).as(name).containsKey(name);
            assertThat(v41).as(name).containsKey(name);
            assertThat(v41.get(name).value()).as(name).isEqualTo(v311.get(name).value());
        }
    }

    @Test
    void jvmArguments() {
        List<Setting> s = SettingParsers.jvmArguments(List.of("-Xmx8192m", "-Xms8G", "-XX:+UseG1GC", "-XX:-UseBiasedLocking",
                "-XX:MaxGCPauseMillis=300", "-XX:G1HeapRegionSize=16m", "-Dcassandra.jmx.local.port=7199",
                "-Djava.rmi.server.hostname=10.0.0.1", "-javaagent:/opt/lib/jmx_prometheus_javaagent-0.20.0.jar=7071:/etc/x.yml",
                "-Xlog:gc=info", "-Xlog:safepoint", "--add-exports=java.base/a=ALL", "--add-exports=java.base/b=ALL", "-ea",
                "-Djavax.net.ssl.keyStorePassword=secret"), "10.0.0.1", "jmx");
        Map<String, Setting> m = byName(s);
        assertThat(m.get("-Xmx").value()).isEqualTo("8GiB");
        assertThat(m.get("-Xms").value()).isEqualTo("8GiB");
        assertThat(m.get("-XX:UseG1GC").value()).isEqualTo("true");
        assertThat(m.get("-XX:UseBiasedLocking").value()).isEqualTo("false");
        assertThat(m.get("-XX:MaxGCPauseMillis").value()).isEqualTo("300");
        assertThat(m.get("-XX:G1HeapRegionSize").value()).isEqualTo("16MiB");
        assertThat(m.get("-Dcassandra.jmx.local.port").value()).isEqualTo("7199");
        assertThat(m.get("-Djava.rmi.server.hostname").value()).isEqualTo("<self>");
        assertThat(m.get("-javaagent:jmx_prometheus_javaagent-0.20.0.jar").value()).isEqualTo("7071:/etc/x.yml");
        assertThat(m.get("-Xlog").value()).isEqualTo("gc=info safepoint");
        assertThat(m.get("--add-exports").value()).isEqualTo("java.base/a=ALL java.base/b=ALL");
        assertThat(m.get("-ea").value()).isEqualTo("true");
        assertThat(m.get("-Djavax.net.ssl.keyStorePassword").value()).isEqualTo(SettingNames.REDACTED);
        assertThat(SettingNames.perNode("-Djava.rmi.server.hostname")).isTrue();
    }

    @Test
    void osOutput() {
        String proc = """
                ##limits-proc 123
                Limit                     Soft Limit           Hard Limit           Units
                Max open files            100000               100000               files
                Max processes             32768                32768                processes
                Max locked memory         unlimited            unlimited            bytes
                ##max_map_count
                1048575
                ##swappiness
                1
                ##swaps
                ##meminfo
                MemTotal:       16303104 kB
                SwapTotal:             0 kB
                ##thp_enabled
                always [madvise] never
                ##thp_defrag
                [always] defer defer+madvise madvise never
                ##nproc
                8
                ##end
                """;
        Map<String, Setting> m = byName(SettingParsers.os(proc));
        assertThat(m.get("limits.nofile").value()).isEqualTo("100000 / 100000");
        assertThat(m.get("limits.memlock").value()).isEqualTo("unlimited / unlimited");
        assertThat(m.get("limits.nofile").source()).isEqualTo("ssh: /proc/123/limits");
        assertThat(m.get("vm.max_map_count").value()).isEqualTo("1048575");
        assertThat(m.get("swap.devices").value()).isEqualTo("0");
        assertThat(m.get("swap.total").value()).isEqualTo("0B");
        assertThat(m.get("memory.total").value()).isEqualTo("15921MiB");
        assertThat(m.get("thp.enabled").value()).isEqualTo("madvise");
        assertThat(m.get("thp.defrag").value()).isEqualTo("always");
        assertThat(m.get("cpu.count").value()).isEqualTo("8");

        String shell = """
                ##limits-soft
                core file size              (blocks, -c) 0
                open files                          (-n) 1024
                max user processes                  (-u) 4096
                ##limits-hard
                -c: core file size (blocks)        unlimited
                -n: file descriptors               4096
                ##max_map_count
                ##end
                """;
        Map<String, Setting> s = byName(SettingParsers.os(shell));
        assertThat(s.get("limits.nofile").value()).isEqualTo("1024 / 4096");
        assertThat(s.get("limits.nproc").value()).isEqualTo("4096 / ?");
        assertThat(s.get("limits.core").value()).isEqualTo("0 / unlimited");
        assertThat(s.get("limits.nofile").source()).contains("login shell");
        assertThat(s).doesNotContainKey("vm.max_map_count");
    }

    @Test
    void yamlPathFromSystemProperty() {
        assertThat(NodeCollector.yamlPath(null)).isEqualTo("/etc/cassandra/cassandra.yaml");
        assertThat(NodeCollector.yamlPath("file:///opt/c/conf/cassandra.yaml")).isEqualTo("/opt/c/conf/cassandra.yaml");
        assertThat(NodeCollector.yamlPath("/x/c.yaml")).isEqualTo("/x/c.yaml");
        assertThat(NodeCollector.major("3.11.19")).isEqualTo(3);
        assertThat(NodeCollector.major(null)).isZero();
    }
}
