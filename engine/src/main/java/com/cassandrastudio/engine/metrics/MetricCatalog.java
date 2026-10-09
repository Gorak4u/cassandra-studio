package com.cassandrastudio.engine.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.management.ObjectName;

/**
 * MON-2: where each logical metric lives, per Cassandra major version and per GC collector.
 * Scalars list candidate (MBean, attribute) pairs in preference order; the first one the node
 * has wins and a node that has none gives null (NFR-COMPAT). Families that exist once per
 * thread pool, verb, request type or table are found with {@code queryNames} patterns.
 */
public final class MetricCatalog {
    private MetricCatalog() {}

    public static final String MEMORY = "java.lang:type=Memory";
    public static final String RUNTIME = "java.lang:type=Runtime";
    public static final String OS = "java.lang:type=OperatingSystem";
    public static final String GC_PATTERN = "java.lang:type=GarbageCollector,name=*";
    public static final String STORAGE_SERVICE = "org.apache.cassandra.db:type=StorageService";
    public static final String COMPACTION_MANAGER = "org.apache.cassandra.db:type=CompactionManager";
    public static final String METRICS = "org.apache.cassandra.metrics";

    /** Cassandra major versions with distinct MBean layouts. Unknown or newer = latest known. */
    public enum Major {
        V3_11, V4_0, V4_1, V5_0;

        public static Major of(String version) {
            if (version == null || version.isBlank()) return V4_1;
            String[] p = version.trim().split("[.\\-]");
            int major = parse(p[0]);
            int minor = p.length > 1 ? parse(p[1]) : 0;
            if (major <= 3) return V3_11;
            if (major == 4) return minor == 0 ? V4_0 : V4_1;
            return V5_0;
        }

        private static int parse(String s) {
            try {
                return Integer.parseInt(s.replaceAll("\\D.*", ""));
            } catch (NumberFormatException e) {
                return 4;
            }
        }

        boolean atLeast(Major other) {
            return compareTo(other) >= 0;
        }
    }

    // ---- GC (MON-16) ---------------------------------------------------------------

    public enum GcFamily { CMS, G1, ZGC, SHENANDOAH, PARALLEL, SERIAL, UNKNOWN }

    /**
     * One collector bean. {@code pause} is true when its CollectionTime is stop-the-world time;
     * concurrent-cycle beans (ZGC Cycles, Shenandoah Cycles, G1 Concurrent GC) count time the
     * application kept running, so they are left out of GC pressure when pause beans exist.
     */
    public record Collector(String name, GcFamily family, boolean pause) {}

    public static Collector collector(String name) {
        String n = name == null ? "" : name;
        return switch (n) {
            case "ParNew", "ConcurrentMarkSweep" -> new Collector(n, GcFamily.CMS, true);
            case "G1 Young Generation", "G1 Old Generation", "G1 Mixed Generation" -> new Collector(n, GcFamily.G1, true);
            case "G1 Concurrent GC" -> new Collector(n, GcFamily.G1, false);
            case "ZGC Pauses", "ZGC Major Pauses", "ZGC Minor Pauses" -> new Collector(n, GcFamily.ZGC, true);
            case "ZGC", "ZGC Cycles", "ZGC Major Cycles", "ZGC Minor Cycles" -> new Collector(n, GcFamily.ZGC, false);
            case "Shenandoah Pauses" -> new Collector(n, GcFamily.SHENANDOAH, true);
            case "Shenandoah Cycles" -> new Collector(n, GcFamily.SHENANDOAH, false);
            case "PS Scavenge", "PS MarkSweep" -> new Collector(n, GcFamily.PARALLEL, true);
            case "Copy", "MarkSweepCompact" -> new Collector(n, GcFamily.SERIAL, true);
            default -> new Collector(n, GcFamily.UNKNOWN, true);
        };
    }

    public static final List<String> GC_ATTRS = List.of("CollectionCount", "CollectionTime");

    // ---- scalars --------------------------------------------------------------------

    /** A place a value can be read from. */
    public record Source(String objectName, String attribute) {}

    /** Logical single-valued metrics. {@code perPoll} = read on every poll, else on request. */
    public enum Scalar {
        HEAP_USAGE(true), UPTIME_MS(true), VM_VENDOR(true), VM_VERSION(true), SPEC_VERSION(true),
        PROCESS_CPU_LOAD(true), SYSTEM_CPU_LOAD(true), OPEN_FDS(true), MAX_FDS(true),
        RELEASE_VERSION(true), LOAD_BYTES(true), LOCAL_TOKENS(true), LOCAL_HOST_ID(true), OPERATION_MODE(true),
        DATA_FILE_LOCATIONS(true),
        LIVE_NODES(true), UNREACHABLE_NODES(true), JOINING_NODES(true), LEAVING_NODES(true), MOVING_NODES(true),
        HOST_ID_MAP(true),
        PENDING_COMPACTIONS(true), COMPLETED_COMPACTIONS(true), ACTIVE_COMPACTIONS(true),
        TOTAL_HINTS(true), HINTS_IN_PROGRESS(true),
        LIVE_SSTABLES(true), MEMTABLE_OFF_HEAP(true), BLOOM_FILTER_OFF_HEAP(true), INDEX_SUMMARY_OFF_HEAP(true),
        COMPRESSION_OFF_HEAP(true),
        PARTITIONER(false), OWNERSHIP(false), TOKEN_TO_ENDPOINT(false);

        public final boolean perPoll;

        Scalar(boolean perPoll) {
            this.perPoll = perPoll;
        }
    }

    public static String metric(String type, String name) {
        return METRICS + ":type=" + type + ",name=" + name;
    }

    /** Table metrics are "ColumnFamily" in 3.11 and "Table" from 4.0 (both may be registered). */
    public static List<String> tableTypes(Major m) {
        return m == Major.V3_11 ? List.of("ColumnFamily", "Table") : List.of("Table", "ColumnFamily");
    }

    public static List<Source> sources(Scalar s, Major m) {
        boolean withPort = m.atLeast(Major.V4_0);
        return switch (s) {
            case HEAP_USAGE -> List.of(src(MEMORY, "HeapMemoryUsage"));
            case UPTIME_MS -> List.of(src(RUNTIME, "Uptime"));
            case VM_VENDOR -> List.of(src(RUNTIME, "VmVendor"));
            case VM_VERSION -> List.of(src(RUNTIME, "VmVersion"));
            case SPEC_VERSION -> List.of(src(RUNTIME, "SpecVersion"));
            case PROCESS_CPU_LOAD -> List.of(src(OS, "ProcessCpuLoad"));
            // CpuLoad replaced SystemCpuLoad in JDK 14; Cassandra's JDK varies independently of its version.
            case SYSTEM_CPU_LOAD -> List.of(src(OS, "CpuLoad"), src(OS, "SystemCpuLoad"));
            case OPEN_FDS -> List.of(src(OS, "OpenFileDescriptorCount"));
            case MAX_FDS -> List.of(src(OS, "MaxFileDescriptorCount"));
            case RELEASE_VERSION -> List.of(src(STORAGE_SERVICE, "ReleaseVersion"));
            case LOAD_BYTES -> List.of(src(STORAGE_SERVICE, "Load"), src(metric("Storage", "Load"), "Count"));
            case LOCAL_TOKENS -> List.of(src(STORAGE_SERVICE, "Tokens"));
            case LOCAL_HOST_ID -> List.of(src(STORAGE_SERVICE, "LocalHostId"));
            case OPERATION_MODE -> List.of(src(STORAGE_SERVICE, "OperationMode"));
            case DATA_FILE_LOCATIONS -> List.of(src(STORAGE_SERVICE, "AllDataFileLocations"));
            case LIVE_NODES -> List.of(src(STORAGE_SERVICE, "LiveNodes"));
            case UNREACHABLE_NODES -> List.of(src(STORAGE_SERVICE, "UnreachableNodes"));
            case JOINING_NODES -> List.of(src(STORAGE_SERVICE, "JoiningNodes"));
            case LEAVING_NODES -> List.of(src(STORAGE_SERVICE, "LeavingNodes"));
            case MOVING_NODES -> List.of(src(STORAGE_SERVICE, "MovingNodes"));
            case HOST_ID_MAP -> List.of(src(STORAGE_SERVICE, "HostIdMap"), src(STORAGE_SERVICE, "EndpointToHostId"));
            case PENDING_COMPACTIONS -> List.of(src(metric("Compaction", "PendingTasks"), "Value"));
            case COMPLETED_COMPACTIONS -> List.of(src(metric("Compaction", "CompletedTasks"), "Value"));
            case ACTIVE_COMPACTIONS -> List.of(src(COMPACTION_MANAGER, "Compactions"));
            case TOTAL_HINTS -> List.of(src(metric("Storage", "TotalHints"), "Count"));
            case HINTS_IN_PROGRESS -> List.of(src(metric("Storage", "TotalHintsInProgress"), "Count"));
            case LIVE_SSTABLES -> tableGlobal(m, "LiveSSTableCount");
            case MEMTABLE_OFF_HEAP -> tableGlobal(m, "MemtableOffHeapSize");
            case BLOOM_FILTER_OFF_HEAP -> tableGlobal(m, "BloomFilterOffHeapMemoryUsed");
            case INDEX_SUMMARY_OFF_HEAP -> tableGlobal(m, "IndexSummaryOffHeapMemoryUsed");
            case COMPRESSION_OFF_HEAP -> tableGlobal(m, "CompressionMetadataOffHeapMemoryUsed");
            case PARTITIONER -> List.of(src(STORAGE_SERVICE, "PartitionerName"));
            case OWNERSHIP -> withPort
                    ? List.of(src(STORAGE_SERVICE, "OwnershipWithPort"), src(STORAGE_SERVICE, "Ownership"))
                    : List.of(src(STORAGE_SERVICE, "Ownership"));
            case TOKEN_TO_ENDPOINT -> withPort
                    ? List.of(src(STORAGE_SERVICE, "TokenToEndpointWithPortMap"), src(STORAGE_SERVICE, "TokenToEndpointMap"))
                    : List.of(src(STORAGE_SERVICE, "TokenToEndpointMap"));
        };
    }

    /** {@code effectiveOwnership(keyspace)} operation names, preferred first. */
    public static List<String> effectiveOwnershipOps(Major m) {
        return m.atLeast(Major.V4_0) ? List.of("effectiveOwnershipWithPort", "effectiveOwnership")
                : List.of("effectiveOwnership");
    }

    private static List<Source> tableGlobal(Major m, String name) {
        List<Source> out = new ArrayList<>();
        for (String type : tableTypes(m)) out.add(src(metric(type, name), "Value"));
        return out;
    }

    private static Source src(String bean, String attr) {
        return new Source(bean, attr);
    }

    // ---- families ---------------------------------------------------------------------

    /** All thread pool metrics (3.11: paths request/internal; 4.0+: also transport). Group by scope. */
    public static final String THREAD_POOLS_PATTERN = METRICS + ":type=ThreadPools,*";
    /** Gauges expose Value, counters Count; reading both in one call works on every version. */
    public static final List<String> THREAD_POOL_ATTRS = List.of("Value", "Count");
    public static final List<String> THREAD_POOL_NAMES = List.of("ActiveTasks", "PendingTasks", "CurrentlyBlockedTasks",
            "CompletedTasks", "TotalBlockedTasks");

    public static final String DROPPED_PATTERN = METRICS + ":type=DroppedMessage,name=Dropped,*";

    public static final List<String> CLIENT_SCOPES = List.of("Read", "Write", "RangeSlice", "CASRead", "CASWrite");
    public static final List<String> TIMER_ATTRS = List.of("50thPercentile", "95thPercentile", "99thPercentile", "Max",
            "OneMinuteRate", "Count", "DurationUnit");

    public static String clientRequest(String scope, String name) {
        return METRICS + ":type=ClientRequest,scope=" + scope + ",name=" + name;
    }

    /** Per-table metric pattern for one metric name; keyspace null = all keyspaces. */
    public static String tablePattern(String type, String name, String keyspace) {
        return METRICS + ":type=" + type + (keyspace == null ? "" : ",keyspace=" + quoteIfNeeded(keyspace))
                + ",name=" + name + ",*";
    }

    private static String quoteIfNeeded(String v) {
        return v.matches("[A-Za-z0-9_]+") ? v : ObjectName.quote(v);
    }

    /** MON-18 per-table metrics: logical field -> metric names (preferred first) and attribute. */
    public enum TableMetric {
        READ_LATENCY("Count", "ReadLatency"),
        READ_LATENCY_P99("99thPercentile", "ReadLatency"),
        WRITE_LATENCY("Count", "WriteLatency"),
        WRITE_LATENCY_P99("99thPercentile", "WriteLatency"),
        LIVE_DISK("Count", "LiveDiskSpaceUsed"),
        TOTAL_DISK("Count", "TotalDiskSpaceUsed"),
        SSTABLES("Value", "LiveSSTableCount"),
        MEAN_PARTITION("Value", "MeanPartitionSize", "MeanRowSize"),
        MAX_PARTITION("Value", "MaxPartitionSize", "MaxRowSize"),
        TOMBSTONES_P99("99thPercentile", "TombstoneScannedHistogram"),
        SSTABLES_PER_READ_P99("99thPercentile", "SSTablesPerReadHistogram"),
        BLOOM_FALSE_RATIO("Value", "BloomFilterFalseRatio"),
        PENDING_COMPACTIONS("Value", "PendingCompactions"),
        KEY_CACHE_HIT_RATE("Value", "KeyCacheHitRate");

        public final String attribute;
        public final List<String> names;

        TableMetric(String attribute, String... names) {
            this.attribute = attribute;
            this.names = List.of(names);
        }
    }

    /** Timer durations to microseconds using the reporter's DurationUnit (Cassandra: microseconds). */
    public static Double toMicros(Double v, Object durationUnit) {
        if (v == null) return null;
        String u = durationUnit == null ? "microseconds" : durationUnit.toString().toLowerCase(Locale.ROOT);
        return switch (u) {
            case "nanoseconds" -> v / 1000.0;
            case "milliseconds" -> v * 1000.0;
            case "seconds" -> v * 1_000_000.0;
            default -> v;
        };
    }

    // ---- jmx_exporter (EXPORTER method) ---------------------------------------------

    /**
     * Sample names produced by the standard Cassandra jmx_exporter config
     * (lowercase names: {@code cassandra_<type>_<name>} with the scope as a label named after the
     * type) plus the agent's built-in JVM collectors (0.x and 1.x names). Best effort: a custom
     * exporter config gives nulls, not errors.
     */
    public static final class Exporter {
        private Exporter() {}

        public static final List<String> HEAP_USED = List.of("jvm_memory_bytes_used", "jvm_memory_used_bytes");
        public static final List<String> HEAP_MAX = List.of("jvm_memory_bytes_max", "jvm_memory_max_bytes");
        public static final List<String> GC_COUNT = List.of("jvm_gc_collection_seconds_count");
        public static final List<String> GC_SECONDS = List.of("jvm_gc_collection_seconds_sum");
        public static final List<String> JVM_INFO = List.of("jvm_info", "jvm_runtime_info");
        public static final List<String> START_TIME = List.of("process_start_time_seconds");
        public static final List<String> OPEN_FDS = List.of("process_open_fds");
        public static final List<String> MAX_FDS = List.of("process_max_fds");
        public static final List<String> LOAD = List.of("cassandra_storage_load");
        public static final List<String> TOTAL_HINTS = List.of("cassandra_storage_totalhints");
        public static final List<String> HINTS_IN_PROGRESS = List.of("cassandra_storage_totalhintsinprogress");
        public static final List<String> PENDING_COMPACTIONS = List.of("cassandra_compaction_pendingtasks");
        public static final List<String> COMPLETED_COMPACTIONS = List.of("cassandra_compaction_completedtasks");
        public static final List<String> LIVE_SSTABLES = List.of("cassandra_table_livesstablecount",
                "cassandra_columnfamily_livesstablecount");
        public static final String THREAD_POOL_PREFIX = "cassandra_threadpools_";
        public static final String THREAD_POOL_LABEL = "threadpools";
        public static final String DROPPED = "cassandra_droppedmessage_dropped";
        public static final String DROPPED_LABEL = "droppedmessage";
        public static final String CLIENT_PREFIX = "cassandra_clientrequest_";
        public static final String CLIENT_LABEL = "clientrequest";
        public static final String TABLE_PREFIX = "cassandra_table_";
    }
}
