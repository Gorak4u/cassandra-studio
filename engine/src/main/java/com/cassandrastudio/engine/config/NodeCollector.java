package com.cassandrastudio.engine.config;

import com.cassandrastudio.engine.config.ConfigModel.NodeConfig;
import com.cassandrastudio.engine.config.ConfigModel.Setting;
import com.cassandrastudio.engine.cql.ClusterService.NodeInfo;
import com.cassandrastudio.engine.cql.SessionManager;
import com.cassandrastudio.engine.jmx.JmxAccess;
import com.cassandrastudio.engine.jmx.NodeEndpoint;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.JmxMethod;
import com.cassandrastudio.engine.ssh.NodeShell;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.metadata.Node;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

/**
 * Reads one node's effective configuration (CFG-1):
 * <ul>
 *   <li>cassandra.yaml: on 4.0+ {@code system_views.settings}, queried on that node (virtual
 *       tables are node-local); otherwise the file over SSH (path from the JVM's
 *       {@code cassandra.config} property), with the runtime values JMX exposes laid over it;
 *       with neither, only those JMX values.</li>
 *   <li>JVM: input arguments, selected system properties and HotSpot flags over JMX.</li>
 *   <li>OS: limits of the Cassandra process (or the SSH login shell), vm.max_map_count, swap and
 *       transparent huge pages over SSH; open-file limit, memory and CPUs from JMX.</li>
 * </ul>
 * Whatever cannot be read becomes a notice on the node; the rest is still returned.
 */
public final class NodeCollector {
    static final Duration CQL_TIMEOUT = Duration.ofSeconds(15);
    static final Duration SSH_TIMEOUT = Duration.ofSeconds(15);
    static final String DEFAULT_YAML = "/etc/cassandra/cassandra.yaml";

    private final JmxAccess jmx;
    private final NodeShell shell;
    private final Supplier<CqlSession> session;

    public NodeCollector(JmxAccess jmx, NodeShell shell, Supplier<CqlSession> session) {
        this.jmx = jmx;
        this.shell = shell;
        this.session = session;
    }

    /** HotSpot flags read with getVMOption; byte-valued ones are shown as sizes. */
    static final List<String> VM_FLAGS = List.of("MaxHeapSize", "InitialHeapSize", "MaxNewSize", "NewSize",
            "MaxDirectMemorySize", "UseG1GC", "UseConcMarkSweepGC", "UseParallelGC", "UseZGC", "UseShenandoahGC",
            "MaxGCPauseMillis", "G1HeapRegionSize", "InitiatingHeapOccupancyPercent", "ParallelGCThreads",
            "ConcGCThreads", "MaxTenuringThreshold", "SurvivorRatio", "CMSInitiatingOccupancyFraction",
            "AlwaysPreTouch", "UseNUMA", "UseLargePages", "UseTransparentHugePages", "HeapDumpOnOutOfMemoryError",
            "ExitOnOutOfMemoryError", "CrashOnOutOfMemoryError", "UseStringDeduplication", "MaxMetaspaceSize",
            "ReservedCodeCacheSize", "ThreadStackSize", "UseCompressedOops", "PerfDisableSharedMem",
            "UseBiasedLocking", "ResizeTLAB", "UseTLAB");
    private static final java.util.Set<String> BYTE_FLAGS = java.util.Set.of("MaxHeapSize", "InitialHeapSize",
            "MaxNewSize", "NewSize", "MaxDirectMemorySize", "G1HeapRegionSize", "MaxMetaspaceSize",
            "ReservedCodeCacheSize");

    /** System properties compared between nodes (plus every cassandra.* property). */
    static final List<String> SYSTEM_PROPERTIES = List.of("java.version", "java.vendor", "java.vm.name",
            "java.vm.version", "java.specification.version", "os.name", "os.version", "os.arch", "file.encoding",
            "user.timezone", "user.language", "java.net.preferIPv4Stack", "java.rmi.server.hostname",
            "jdk.nio.maxCachedBufferSize", "io.netty.tryReflectionSetAccessible");

    /**
     * Runtime settings JMX exposes on every version from 3.11: canonical name ← bean, attribute,
     * and the old-style name whose unit the value is in.
     */
    record JmxSetting(String bean, String attribute, String rawName) {}

    static final List<JmxSetting> JMX_SETTINGS = List.of(
            new JmxSetting("org.apache.cassandra.db:type=StorageService", "CompactionThroughputMbPerSec", "compaction_throughput_mb_per_sec"),
            new JmxSetting("org.apache.cassandra.db:type=StorageService", "StreamThroughputMbPerSec", "stream_throughput_outbound_megabits_per_sec"),
            new JmxSetting("org.apache.cassandra.db:type=StorageService", "InterDCStreamThroughputMbPerSec", "inter_dc_stream_throughput_outbound_megabits_per_sec"),
            new JmxSetting("org.apache.cassandra.db:type=StorageService", "IncrementalBackupsEnabled", "incremental_backups"),
            new JmxSetting("org.apache.cassandra.db:type=StorageService", "TombstoneWarnThreshold", "tombstone_warn_threshold"),
            new JmxSetting("org.apache.cassandra.db:type=StorageService", "TombstoneFailureThreshold", "tombstone_failure_threshold"),
            new JmxSetting("org.apache.cassandra.db:type=StorageService", "BatchSizeFailureThreshold", "batch_size_fail_threshold_in_kb"),
            new JmxSetting("org.apache.cassandra.db:type=StorageService", "ReadRpcTimeout", "read_request_timeout_in_ms"),
            new JmxSetting("org.apache.cassandra.db:type=StorageService", "WriteRpcTimeout", "write_request_timeout_in_ms"),
            new JmxSetting("org.apache.cassandra.db:type=StorageService", "RangeRpcTimeout", "range_request_timeout_in_ms"),
            new JmxSetting("org.apache.cassandra.db:type=StorageService", "CounterWriteRpcTimeout", "counter_write_request_timeout_in_ms"),
            new JmxSetting("org.apache.cassandra.db:type=StorageService", "CasContentionTimeout", "cas_contention_timeout_in_ms"),
            new JmxSetting("org.apache.cassandra.db:type=StorageService", "TruncateRpcTimeout", "truncate_request_timeout_in_ms"),
            new JmxSetting("org.apache.cassandra.db:type=StorageService", "RpcTimeout", "request_timeout_in_ms"),
            new JmxSetting("org.apache.cassandra.db:type=StorageService", "PartitionerName", "partitioner"),
            new JmxSetting("org.apache.cassandra.db:type=StorageProxy", "HintedHandoffEnabled", "hinted_handoff_enabled"),
            new JmxSetting("org.apache.cassandra.db:type=StorageProxy", "MaxHintWindow", "max_hint_window_in_ms"),
            new JmxSetting("org.apache.cassandra.db:type=CompactionManager", "CoreCompactorThreads", "concurrent_compactors"));

    public NodeConfig collect(ConnectionConfig cfg, Map<String, String> secrets, NodeInfo n) {
        Map<String, String> sources = new LinkedHashMap<>();
        List<String> notices = new ArrayList<>();
        List<Setting> settings = new ArrayList<>();
        NodeEndpoint ep = new NodeEndpoint(n.hostId(), n.address(), n.datacenter(), n.rack(), n.version());

        // JVM first: it also tells where cassandra.yaml is
        MBeanServerConnection mbeans = null;
        Map<String, String> sysProps = Map.of();
        if (cfg.jmx() != null && (cfg.jmx().method() == JmxMethod.EXPORTER || cfg.jmx().method() == JmxMethod.SIDECAR
                || cfg.jmx().method() == JmxMethod.NONE)) {
            notices.add("JVM settings and runtime values need JMX; this connection uses " + cfg.jmx().method() + ".");
        } else {
            try {
                JmxAccess.JmxSession s = jmx.session(cfg, secrets, ep);
                mbeans = s.mbeans();
                sources.put(ConfigModel.JVM, "JMX (" + s.route() + ")");
                sysProps = systemProperties(mbeans);
                settings.addAll(jvm(mbeans, sysProps, n.address(), "JMX"));
            } catch (RuntimeException e) {
                notices.add("JMX not reachable, JVM settings missing: " + message(e));
            }
        }

        // cassandra.yaml
        boolean virtualTables = major(n.version()) >= 4;
        boolean haveYaml = false;
        if (virtualTables) {
            try {
                settings.addAll(SettingParsers.yaml(settingsTable(n), "system_views.settings"));
                sources.put(ConfigModel.YAML, "system_views.settings");
                haveYaml = true;
            } catch (RuntimeException e) {
                notices.add("system_views.settings could not be read: " + message(e));
            }
        }
        if (!haveYaml) {
            String path = yamlPath(sysProps.get("cassandra.config"));
            if (!sshConfigured(cfg)) {
                notices.add("cassandra.yaml needs SSH on Cassandra " + (n.version() == null ? "< 4.0" : n.version())
                        + " (no virtual tables); SSH is not configured for this connection.");
            } else {
                try {
                    String text = shell.exec(cfg, secrets, n.address(), "cat -- " + NodeShell.quote(path), SSH_TIMEOUT,
                            4 * 1024 * 1024);
                    Map<String, String> flat = SettingNames.flatten(YamlLite.parseMap(text));
                    List<Setting> fromFile = SettingParsers.yaml(flat, "ssh: " + path);
                    settings.addAll(overlayRuntime(fromFile, mbeans));
                    sources.put(ConfigModel.YAML, "ssh: " + path + (mbeans != null ? " + JMX runtime values" : ""));
                    haveYaml = true;
                } catch (YamlLite.YamlException e) {
                    notices.add(path + " is not valid YAML: " + e.getMessage());
                } catch (RuntimeException e) {
                    notices.add("Could not read " + path + " over SSH: " + message(e));
                }
            }
            if (!haveYaml && mbeans != null) {
                List<Setting> runtime = runtime(mbeans);
                settings.addAll(runtime);
                sources.put(ConfigModel.YAML, "JMX (" + runtime.size() + " runtime settings only)");
                notices.add("Only the " + runtime.size() + " settings JMX exposes are shown for cassandra.yaml.");
            }
        }

        // OS
        List<Setting> os = new ArrayList<>();
        if (sshConfigured(cfg)) {
            try {
                os.addAll(SettingParsers.os(shell.exec(cfg, secrets, n.address(), SettingParsers.OS_SCRIPT, SSH_TIMEOUT,
                        256 * 1024)));
                sources.put(ConfigModel.OS, "SSH");
            } catch (RuntimeException e) {
                notices.add("OS limits not read over SSH: " + message(e));
            }
        } else {
            notices.add("OS limits, swap and huge pages need SSH, which is not configured for this connection.");
        }
        if (mbeans != null) {
            java.util.Set<String> have = new java.util.HashSet<>();
            os.forEach(s -> have.add(s.name()));
            for (Setting s : osFromJmx(mbeans)) if (!have.contains(s.name())) os.add(s);
            sources.putIfAbsent(ConfigModel.OS, "JMX");
        }
        settings.addAll(os);
        return new NodeConfig(n.address(), n.hostId(), n.datacenter(), n.rack(), n.version(), sources, settings, notices);
    }

    /** SSH counts as configured once a user name is set (saved connections always carry SSH defaults). */
    static boolean sshConfigured(ConnectionConfig cfg) {
        return cfg.ssh() != null && cfg.ssh().username() != null && !cfg.ssh().username().isBlank();
    }

    static int major(String version) {
        if (version == null) return 0;
        try {
            return Integer.parseInt(version.split("\\.")[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** cassandra.config is a URL or a path; default when unset. */
    static String yamlPath(String prop) {
        if (prop == null || prop.isBlank()) return DEFAULT_YAML;
        String p = prop.strip();
        if (p.startsWith("file://")) p = p.substring("file://".length());
        else if (p.startsWith("file:")) p = p.substring("file:".length());
        return p.isBlank() ? DEFAULT_YAML : p;
    }

    private Map<String, String> settingsTable(NodeInfo n) {
        CqlSession s = session.get();
        Node node = SessionManager.findNode(s, n.hostId() != null ? n.hostId() : n.address());
        if (node == null) throw new IllegalStateException("node " + n.address() + " is not known to the driver");
        SimpleStatement st = SimpleStatement.newInstance("SELECT name, value FROM system_views.settings")
                .setNode(node).setTimeout(CQL_TIMEOUT).setPageSize(2000);
        ResultSet rs = s.execute(st);
        Map<String, String> out = new TreeMap<>();
        for (Row r : rs) out.put(r.getString("name"), r.getString("value"));
        if (rs.getExecutionInfo().getCoordinator() != null && !rs.getExecutionInfo().getCoordinator().equals(node)) {
            throw new IllegalStateException("the query ran on another node");
        }
        return out;
    }

    /** Values JMX reports at runtime replace the file's (a setting changed with nodetool since start). */
    static List<Setting> overlayRuntime(List<Setting> fromFile, MBeanServerConnection mbeans) {
        if (mbeans == null) return fromFile;
        Map<String, Setting> byName = new LinkedHashMap<>();
        fromFile.forEach(s -> byName.put(s.name(), s));
        for (Setting r : runtime(mbeans)) {
            Setting f = byName.get(r.name());
            if (f == null || !java.util.Objects.equals(f.value(), r.value())) byName.put(r.name(), r);
        }
        return new ArrayList<>(byName.values());
    }

    static List<Setting> runtime(MBeanServerConnection mbeans) {
        List<Setting> out = new ArrayList<>();
        for (JmxSetting js : JMX_SETTINGS) {
            Object v = attribute(mbeans, js.bean(), js.attribute());
            if (v == null) continue;
            String raw = String.valueOf(v);
            String name = SettingNames.canonical(js.rawName());
            String value = SettingNames.normalize(js.rawName(), raw);
            out.add(new Setting(ConfigModel.YAML, name, value, js.attribute(), raw, "JMX runtime"));
        }
        return out;
    }

    private static Map<String, String> systemProperties(MBeanServerConnection m) {
        Map<String, String> out = new TreeMap<>();
        Object v = attribute(m, "java.lang:type=Runtime", "SystemProperties");
        if (v instanceof TabularData td) {
            for (Object row : td.values()) {
                if (row instanceof CompositeData cd && cd.containsKey("key") && cd.containsKey("value")) {
                    out.put(String.valueOf(cd.get("key")), String.valueOf(cd.get("value")));
                }
            }
        }
        return out;
    }

    static List<Setting> jvm(MBeanServerConnection m, Map<String, String> sysProps, String self, String source) {
        List<Setting> out = new ArrayList<>();
        Object args = attribute(m, "java.lang:type=Runtime", "InputArguments");
        if (args instanceof String[] a) out.addAll(SettingParsers.jvmArguments(Arrays.asList(a), self, source));
        else if (args instanceof List<?> l) out.addAll(SettingParsers.jvmArguments(l.stream().map(String::valueOf).toList(), self, source));
        sysProps.forEach((k, v) -> {
            if (!SYSTEM_PROPERTIES.contains(k) && !k.startsWith("cassandra.")) return;
            String value = SettingNames.secret(k) ? SettingNames.REDACTED : self == null ? v : v.replace(self, "<self>");
            out.add(new Setting(ConfigModel.JVM, "sys." + k, value, k, value, source + " system property"));
        });
        for (String flag : VM_FLAGS) {
            Object o = invoke(m, "com.sun.management:type=HotSpotDiagnostic", "getVMOption", flag);
            if (o instanceof CompositeData cd && cd.containsKey("value")) {
                String raw = String.valueOf(cd.get("value"));
                String value = BYTE_FLAGS.contains(flag) ? SettingParsers.jvmSize(raw)
                        : flag.equals("ThreadStackSize") ? SettingParsers.jvmSize(raw + "k") : raw;
                out.add(new Setting(ConfigModel.JVM, "vm." + flag, value, flag, raw,
                        source + " HotSpot flag (" + cd.get("origin") + ")"));
            }
        }
        Map<String, Object> rt = new LinkedHashMap<>();
        for (String a : List.of("VmVendor", "VmVersion", "SpecVersion")) {
            Object v = attribute(m, "java.lang:type=Runtime", a);
            if (v != null) rt.put(a, v);
        }
        rt.forEach((k, v) -> out.add(new Setting(ConfigModel.JVM, "runtime." + k, String.valueOf(v), k, String.valueOf(v), source)));
        return out;
    }

    static List<Setting> osFromJmx(MBeanServerConnection m) {
        List<Setting> out = new ArrayList<>();
        String bean = "java.lang:type=OperatingSystem";
        Object fd = attribute(m, bean, "MaxFileDescriptorCount");
        if (fd instanceof Number num) {
            out.add(new Setting(ConfigModel.OS, "process.max_open_files", String.valueOf(num.longValue()),
                    "MaxFileDescriptorCount", String.valueOf(fd), "JMX OperatingSystem (Cassandra process)"));
        }
        Object mem = attribute(m, bean, "TotalPhysicalMemorySize");
        if (mem == null) mem = attribute(m, bean, "TotalMemorySize");
        if (mem instanceof Number num) {
            out.add(new Setting(ConfigModel.OS, "memory.total", SettingParsers.jvmSize(String.valueOf(num.longValue())),
                    "TotalPhysicalMemorySize", String.valueOf(mem), "JMX OperatingSystem"));
        }
        Object swap = attribute(m, bean, "TotalSwapSpaceSize");
        if (swap instanceof Number num) {
            out.add(new Setting(ConfigModel.OS, "swap.total", SettingParsers.jvmSize(String.valueOf(num.longValue())),
                    "TotalSwapSpaceSize", String.valueOf(swap), "JMX OperatingSystem"));
        }
        Object cpus = attribute(m, bean, "AvailableProcessors");
        if (cpus instanceof Number num) {
            out.add(new Setting(ConfigModel.OS, "cpu.count", String.valueOf(num.intValue()), "AvailableProcessors",
                    String.valueOf(cpus), "JMX OperatingSystem"));
        }
        return out;
    }

    private static Object attribute(MBeanServerConnection m, String bean, String attr) {
        try {
            return m.getAttribute(new ObjectName(bean), attr);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (Exception e) {
            return null; // missing bean or attribute on this version
        }
    }

    private static Object invoke(MBeanServerConnection m, String bean, String op, String arg) {
        try {
            return m.invoke(new ObjectName(bean), op, new Object[] {arg}, new String[] {String.class.getName()});
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (Exception e) {
            return null; // flag unknown to this JVM
        }
    }

    static String message(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && (t.getMessage() == null || t instanceof java.io.UncheckedIOException
                || t instanceof java.util.concurrent.ExecutionException)) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
