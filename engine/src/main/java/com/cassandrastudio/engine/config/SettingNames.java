package com.cassandrastudio.engine.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes cassandra.yaml settings comparable across versions (CFG-1). Cassandra 4.1 renamed many
 * settings and gave them typed values ({@code read_request_timeout_in_ms: 5000} became
 * {@code read_request_timeout: 5000ms}); 5.0 renamed a few more. Every name is mapped to the newest
 * name and every value to one canonical text, so 3.11, 4.0, 4.1 and 5.0 nodes compare:
 * durations, data sizes and rates are rendered in the largest unit that keeps them whole
 * ({@code 10800000ms} and {@code max_hint_window_in_ms: 10800000} both become {@code 3h}).
 *
 * <p>The rename table is Cassandra's own: the {@code @Replaces(oldName, converter)} annotations
 * on {@code org.apache.cassandra.config.Config} in 4.1.12 and 5.0.9.
 */
public final class SettingNames {
    private SettingNames() {}

    /** old name, new name, unit an unsuffixed old value is in ("" = none / already typed). */
    private static final String RENAMES = """
            permissions_validity_in_ms permissions_validity ms
            permissions_update_interval_in_ms permissions_update_interval ms
            roles_validity_in_ms roles_validity ms
            roles_update_interval_in_ms roles_update_interval ms
            credentials_validity_in_ms credentials_validity ms
            credentials_update_interval_in_ms credentials_update_interval ms
            max_hint_window_in_ms max_hint_window ms
            native_transport_idle_timeout_in_ms native_transport_idle_timeout ms
            request_timeout_in_ms request_timeout ms
            read_request_timeout_in_ms read_request_timeout ms
            range_request_timeout_in_ms range_request_timeout ms
            write_request_timeout_in_ms write_request_timeout ms
            counter_write_request_timeout_in_ms counter_write_request_timeout ms
            cas_contention_timeout_in_ms cas_contention_timeout ms
            truncate_request_timeout_in_ms truncate_request_timeout ms
            repair_request_timeout_in_ms repair_request_timeout ms
            streaming_keep_alive_period_in_secs streaming_keep_alive_period s
            cross_node_timeout internode_timeout -
            slow_query_log_timeout_in_ms slow_query_log_timeout ms
            memtable_heap_space_in_mb memtable_heap_space MiB
            memtable_offheap_space_in_mb memtable_offheap_space MiB
            repair_session_space_in_mb repair_session_space MiB
            internode_max_message_size_in_bytes internode_max_message_size B
            internode_socket_send_buffer_size_in_bytes internode_socket_send_buffer_size B
            internode_send_buff_size_in_bytes internode_socket_send_buffer_size B
            internode_socket_receive_buffer_size_in_bytes internode_socket_receive_buffer_size B
            internode_recv_buff_size_in_bytes internode_socket_receive_buffer_size B
            internode_application_send_queue_capacity_in_bytes internode_application_send_queue_capacity B
            internode_application_send_queue_reserve_endpoint_capacity_in_bytes internode_application_send_queue_reserve_endpoint_capacity B
            internode_application_send_queue_reserve_global_capacity_in_bytes internode_application_send_queue_reserve_global_capacity B
            internode_application_receive_queue_capacity_in_bytes internode_application_receive_queue_capacity B
            internode_application_receive_queue_reserve_endpoint_capacity_in_bytes internode_application_receive_queue_reserve_endpoint_capacity B
            internode_application_receive_queue_reserve_global_capacity_in_bytes internode_application_receive_queue_reserve_global_capacity B
            internode_tcp_connect_timeout_in_ms internode_tcp_connect_timeout ms
            internode_tcp_user_timeout_in_ms internode_tcp_user_timeout ms
            internode_streaming_tcp_user_timeout_in_ms internode_streaming_tcp_user_timeout ms
            native_transport_max_frame_size_in_mb native_transport_max_frame_size MiB
            native_transport_max_concurrent_requests_in_bytes_per_ip native_transport_max_request_data_in_flight_per_ip B
            native_transport_max_concurrent_requests_in_bytes native_transport_max_request_data_in_flight B
            native_transport_receive_queue_capacity_in_bytes native_transport_receive_queue_capacity B
            max_value_size_in_mb max_value_size MiB
            column_index_size_in_kb column_index_size KiB
            column_index_cache_size_in_kb column_index_cache_size KiB
            batch_size_warn_threshold_in_kb batch_size_warn_threshold KiB
            batch_size_fail_threshold_in_kb batch_size_fail_threshold KiB
            compaction_throughput_mb_per_sec compaction_throughput MiB/s
            min_free_space_per_drive_in_mb min_free_space_per_drive MiB
            stream_throughput_outbound_megabits_per_sec stream_throughput_outbound Mbit/s
            inter_dc_stream_throughput_outbound_megabits_per_sec inter_dc_stream_throughput_outbound Mbit/s
            commitlog_total_space_in_mb commitlog_total_space MiB
            commitlog_sync_group_window_in_ms commitlog_sync_group_window ms
            commitlog_sync_period_in_ms commitlog_sync_period ms
            commitlog_segment_size_in_mb commitlog_segment_size MiB
            periodic_commitlog_sync_lag_block_in_ms periodic_commitlog_sync_lag_block ms
            max_mutation_size_in_kb max_mutation_size KiB
            cdc_total_space_in_mb cdc_total_space MiB
            cdc_free_space_check_interval_ms cdc_free_space_check_interval ms
            dynamic_snitch_update_interval_in_ms dynamic_snitch_update_interval ms
            dynamic_snitch_reset_interval_in_ms dynamic_snitch_reset_interval ms
            hinted_handoff_throttle_in_kb hinted_handoff_throttle KiB
            batchlog_replay_throttle_in_kb batchlog_replay_throttle KiB
            hints_flush_period_in_ms hints_flush_period ms
            max_hints_file_size_in_mb max_hints_file_size MiB
            trickle_fsync_interval_in_kb trickle_fsync_interval KiB
            sstable_preemptive_open_interval_in_mb sstable_preemptive_open_interval MiB
            key_cache_size_in_mb key_cache_size MiB
            key_cache_save_period key_cache_save_period s
            row_cache_size_in_mb row_cache_size MiB
            row_cache_save_period row_cache_save_period s
            counter_cache_size_in_mb counter_cache_size MiB
            counter_cache_save_period counter_cache_save_period s
            cache_load_timeout_seconds cache_load_timeout s
            networking_cache_size_in_mb networking_cache_size MiB
            file_cache_size_in_mb file_cache_size MiB
            index_summary_capacity_in_mb index_summary_capacity MiB
            index_summary_resize_interval_in_minutes index_summary_resize_interval m
            gc_log_threshold_in_ms gc_log_threshold ms
            gc_warn_threshold_in_ms gc_warn_threshold ms
            tracetype_query_ttl trace_type_query_ttl s
            tracetype_repair_ttl trace_type_repair_ttl s
            prepared_statements_cache_size_mb prepared_statements_cache_size MiB
            enable_user_defined_functions user_defined_functions_enabled -
            enable_scripted_user_defined_functions scripted_user_defined_functions_enabled -
            enable_materialized_views materialized_views_enabled -
            enable_transient_replication transient_replication_enabled -
            enable_sasi_indexes sasi_indexes_enabled -
            enable_drop_compact_storage drop_compact_storage_enabled -
            enable_user_defined_functions_threads user_defined_functions_threads_enabled -
            user_defined_function_warn_timeout user_defined_functions_warn_timeout ms
            user_defined_function_fail_timeout user_defined_functions_fail_timeout ms
            validation_preview_purge_head_start_in_sec validation_preview_purge_head_start s
            compaction_large_partition_warning_threshold_mb compaction_large_partition_warning_threshold MiB
            compaction_large_partition_warning_threshold partition_size_warn_threshold -
            compaction_tombstone_warning_threshold partition_tombstones_warn_threshold -
            keyspace_count_warn_threshold keyspaces_warn_threshold -
            table_count_warn_threshold tables_warn_threshold -
            """;

    private record Rename(String newName, String unit) {}

    private static final Map<String, Rename> RENAME = new HashMap<>();

    static {
        for (String line : RENAMES.strip().split("\n")) {
            String[] p = line.strip().split(" ");
            RENAME.put(p[0], new Rename(p[1], p[2].equals("-") ? "" : p[2]));
        }
    }

    /**
     * Settings that are per node by design and never count as drift: addresses, interfaces,
     * tokens, host identity.
     */
    private static final Set<String> PER_NODE = Set.of("listen_address", "listen_interface", "rpc_address",
            "rpc_interface", "broadcast_address", "broadcast_rpc_address", "initial_token", "local_host_id", "host_id",
            "native_transport_address", "-Djava.rmi.server.hostname", "sys.java.rmi.server.hostname",
            "-Dcassandra.replace_address", "-Dcassandra.replace_address_first_boot", "-Dcassandra.initial_token");

    public static boolean perNode(String canonicalName) {
        return PER_NODE.contains(canonicalName);
    }

    /** The newest name for a (possibly old) setting name; follows 4.1 then 5.0 renames. */
    public static String canonical(String name) {
        String n = name;
        for (int i = 0; i < 4; i++) {
            Rename r = RENAME.get(n);
            if (r == null || r.newName.equals(n)) break;
            n = r.newName;
        }
        return n;
    }

    /** How many renames separate {@code name} from its canonical name (0 = already the newest). */
    public static int age(String name) {
        int hops = 0;
        String n = name;
        for (int i = 0; i < 4; i++) {
            Rename r = RENAME.get(n);
            if (r == null || r.newName.equals(n)) break;
            n = r.newName;
            hops++;
        }
        return hops;
    }

    /** The unit a bare number under this (raw) name is in, or "" when unitless. */
    public static String impliedUnit(String rawName) {
        Rename r = RENAME.get(rawName);
        return r == null ? "" : r.unit;
    }

    // ---- values ---------------------------------------------------------------------------

    private static final Pattern NUMBER = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final Pattern WITH_UNIT = Pattern.compile("^(-?\\d+(?:\\.\\d+)?)\\s*([A-Za-zµ/]+)$");
    private static final Pattern CLASS = Pattern.compile("^org\\.apache\\.cassandra\\.(?:[a-z0-9_]+\\.)+([A-Z]\\w*)$");

    private static final Map<String, BigDecimal> DURATION = new LinkedHashMap<>();
    private static final Map<String, BigDecimal> STORAGE = new LinkedHashMap<>();
    private static final Map<String, BigDecimal> RATE = new LinkedHashMap<>();

    static {
        // largest first: rendering picks the first unit that divides the value
        DURATION.put("d", BigDecimal.valueOf(86_400_000_000_000L));
        DURATION.put("h", BigDecimal.valueOf(3_600_000_000_000L));
        DURATION.put("m", BigDecimal.valueOf(60_000_000_000L));
        DURATION.put("s", BigDecimal.valueOf(1_000_000_000L));
        DURATION.put("ms", BigDecimal.valueOf(1_000_000L));
        DURATION.put("us", BigDecimal.valueOf(1_000L));
        DURATION.put("ns", BigDecimal.ONE);
        STORAGE.put("GiB", BigDecimal.valueOf(1L << 30));
        STORAGE.put("MiB", BigDecimal.valueOf(1L << 20));
        STORAGE.put("KiB", BigDecimal.valueOf(1L << 10));
        STORAGE.put("B", BigDecimal.ONE);
        RATE.put("MiB/s", BigDecimal.valueOf(1L << 20));
        RATE.put("KiB/s", BigDecimal.valueOf(1L << 10));
        RATE.put("B/s", BigDecimal.ONE);
    }

    private static final Map<String, String> UNIT_ALIASES = Map.ofEntries(
            Map.entry("µs", "us"), Map.entry("nanoseconds", "ns"), Map.entry("microseconds", "us"),
            Map.entry("milliseconds", "ms"), Map.entry("seconds", "s"), Map.entry("minutes", "m"),
            Map.entry("hours", "h"), Map.entry("days", "d"), Map.entry("bytes", "B"),
            Map.entry("kibibytes", "KiB"), Map.entry("mebibytes", "MiB"), Map.entry("gibibytes", "GiB"));

    /**
     * Canonical text for a yaml setting value. {@code rawName} is the name the value was found
     * under (it decides the unit of an old-style bare number); null and "null" give null.
     */
    public static String normalize(String rawName, String value) {
        if (value == null) return null;
        String v = value.strip();
        if (v.isEmpty() || v.equals("null")) return null;
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) return v.toLowerCase(Locale.ROOT);
        String unit = impliedUnit(rawName);
        if (NUMBER.matcher(v).matches()) {
            if (!unit.isEmpty() && !v.startsWith("-")) return withUnit(new BigDecimal(v), unit);
            return plainNumber(v);
        }
        Matcher m = WITH_UNIT.matcher(v);
        if (m.matches()) {
            String rendered = withUnit(new BigDecimal(m.group(1)), m.group(2));
            if (rendered != null) return rendered;
        }
        Matcher c = CLASS.matcher(v);
        if (c.matches()) return c.group(1);
        if (v.startsWith("[") && v.endsWith("]")) return list(v.substring(1, v.length() - 1).split(","), false);
        return v;
    }

    /** "5.0" → "5", "0.33333334" unchanged, "NaN" unchanged. */
    static String plainNumber(String v) {
        try {
            BigDecimal d = new BigDecimal(v).stripTrailingZeros();
            return d.scale() < 0 ? d.setScale(0).toPlainString() : d.toPlainString();
        } catch (NumberFormatException e) {
            return v;
        }
    }

    /** Renders an amount with a unit in the largest whole unit of its kind; null for unknown units. */
    static String withUnit(BigDecimal amount, String unitIn) {
        String unit = UNIT_ALIASES.getOrDefault(unitIn, unitIn);
        Map<String, BigDecimal> kind;
        BigDecimal base;
        if (unit.equals("Mbit/s")) {
            kind = RATE;
            base = amount.multiply(BigDecimal.valueOf(125_000)); // megabits per second to bytes per second
        } else if (DURATION.containsKey(unit)) {
            kind = DURATION;
            base = amount.multiply(DURATION.get(unit));
        } else if (STORAGE.containsKey(unit)) {
            kind = STORAGE;
            base = amount.multiply(STORAGE.get(unit));
        } else if (RATE.containsKey(unit)) {
            kind = RATE;
            base = amount.multiply(RATE.get(unit));
        } else {
            return null;
        }
        if (base.signum() == 0) {
            String smallest = kind == DURATION ? "ms" : kind == STORAGE ? "B" : "B/s";
            return "0" + smallest;
        }
        // the largest unit the value reaches: whole there, else whole in the next smaller unit,
        // else two decimals in the larger one (25000000 B/s reads 23.84MiB/s, 90000ms reads 90s)
        List<Map.Entry<String, BigDecimal>> units = new ArrayList<>(kind.entrySet());
        int i = 0;
        while (i < units.size() - 1 && base.abs().compareTo(units.get(i).getValue()) < 0) i++;
        for (int j = i; j <= Math.min(i + 1, units.size() - 1); j++) {
            BigDecimal[] qr = base.divideAndRemainder(units.get(j).getValue());
            if (qr[1].signum() == 0) return qr[0].stripTrailingZeros().toPlainString() + units.get(j).getKey();
        }
        return base.divide(units.get(i).getValue(), 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                + units.get(i).getKey();
    }

    static String list(String[] items, boolean sort) {
        List<String> out = new ArrayList<>();
        for (String i : items) {
            String t = i.strip();
            if (!t.isEmpty()) out.add(t);
        }
        if (sort) out.sort(String::compareTo);
        return "[" + String.join(", ", out) + "]";
    }

    /** Seeds compare as a set: "b, a:7000,c" → "[a:7000, b, c]". */
    public static String seeds(String csv) {
        if (csv == null) return null;
        return list(csv.replace("\"", "").split(","), true);
    }

    /** Values never shown: passwords, and key/trust store paths (Cassandra 4.1 redacts these too). */
    public static boolean secret(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("password") || n.endsWith("encryption_options.keystore")
                || n.endsWith("encryption_options.truststore");
    }

    public static final String REDACTED = "<REDACTED>";

    // ---- flattening cassandra.yaml ------------------------------------------------------------

    /**
     * Flattens a parsed cassandra.yaml the way {@code system_views.settings} names things:
     * nested mappings joined with '.', single-entry lists of mappings (seed_provider) merged into
     * their parent, {@code parameters} rendered as {k=v}, scalar lists as [a, b].
     */
    public static Map<String, String> flatten(Map<String, Object> yaml) {
        Map<String, String> out = new LinkedHashMap<>();
        yaml.forEach((k, v) -> flatten(k, v, out));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Object v, Map<String, String> out) {
        if (v instanceof Map<?, ?> m) {
            if (prefix.endsWith(".parameters")) {
                out.put(prefix, braces((Map<String, Object>) m));
                return;
            }
            if (m.isEmpty()) out.put(prefix, "{}");
            m.forEach((k, val) -> flatten(prefix + "." + k, val, out));
        } else if (v instanceof List<?> l) {
            if (l.size() == 1 && l.get(0) instanceof Map<?, ?> only) {
                flatten(prefix, only, out);
            } else if (l.stream().allMatch(i -> i == null || i instanceof String)) {
                out.put(prefix, "[" + String.join(", ", l.stream().map(String::valueOf).toList()) + "]");
            } else {
                out.put(prefix, l.toString());
            }
        } else {
            out.put(prefix, v == null ? null : v.toString());
        }
    }

    private static String braces(Map<String, Object> m) {
        List<String> parts = new ArrayList<>();
        m.forEach((k, v) -> parts.add(k + "=" + v));
        return "{" + String.join(", ", parts) + "}";
    }

    /** The seed list out of {@code seed_provider.parameters} ("{seeds=a,b}"), or null. */
    public static String seedsFromParameters(String parameters) {
        if (parameters == null) return null;
        Matcher m = Pattern.compile("seeds=([^}]*)").matcher(parameters);
        return m.find() ? seeds(m.group(1)) : null;
    }
}
