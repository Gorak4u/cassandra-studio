package com.cassandrastudio.engine.config;

import com.cassandrastudio.engine.config.ConfigModel.Setting;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns what a node reports (settings rows, yaml, JVM arguments, shell output) into {@link Setting}s. */
public final class SettingParsers {
    private SettingParsers() {}

    /**
     * cassandra.yaml settings from raw name → value pairs (system_views.settings rows or a
     * flattened file). Names become canonical; when a node reports both an old and a new name
     * (4.1 lists both) the newest wins; secrets are redacted; seeds become an ordered set.
     */
    public static List<Setting> yaml(Map<String, String> raw, String source) {
        Map<String, Setting> byName = new TreeMap<>();
        Map<String, Integer> ageOf = new HashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String rawName = e.getKey();
            String rawValue = e.getValue();
            String name;
            String value;
            if (rawName.equals("seed_provider.parameters")) {
                name = "seed_provider.seeds";
                value = SettingNames.seedsFromParameters(rawValue);
                if (value == null) {
                    name = rawName;
                    value = rawValue;
                }
            } else {
                name = SettingNames.canonical(rawName);
                value = SettingNames.normalize(rawName, rawValue);
            }
            if (SettingNames.secret(name)) {
                value = rawValue == null || rawValue.equals("null") ? null : SettingNames.REDACTED;
                rawValue = value;
            }
            int age = SettingNames.age(rawName);
            Integer prev = ageOf.get(name);
            if (prev != null && prev <= age) continue;
            ageOf.put(name, age);
            byName.put(name, new Setting(ConfigModel.YAML, name, value, rawName, rawValue, source));
        }
        return new ArrayList<>(byName.values());
    }

    // ---- JVM ----------------------------------------------------------------------------------

    private static final Pattern SIZE = Pattern.compile("^(\\d+)([kKmMgGtT])?$");

    /** -Xmx8G, -Xmx8192m and MaxHeapSize=8589934592 all read 8GiB. */
    public static String jvmSize(String v) {
        Matcher m = SIZE.matcher(v.strip());
        if (!m.matches()) return v;
        long mult = switch (m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT)) {
            case "k" -> 1L << 10;
            case "m" -> 1L << 20;
            case "g" -> 1L << 30;
            case "t" -> 1L << 40;
            default -> 1L;
        };
        String r = SettingNames.withUnit(new BigDecimal(m.group(1)).multiply(BigDecimal.valueOf(mult)), "B");
        return r == null ? v : r;
    }

    private static final java.util.Set<String> SIZE_FLAGS = java.util.Set.of("-Xmx", "-Xms", "-Xmn", "-Xss");

    /**
     * JVM input arguments as settings: "-Xmx" = 8GiB, "-XX:UseG1GC" = true, "-Dkey" = value,
     * "-javaagent:jmx_prometheus_javaagent.jar" = its options. Repeated options are joined. The
     * node's own address is replaced by {@code <self>} so per-node values compare.
     */
    public static List<Setting> jvmArguments(List<String> args, String selfAddress, String source) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        Map<String, String> rawOf = new LinkedHashMap<>();
        for (String a : args) {
            if (a == null || a.isBlank()) continue;
            String key;
            String value;
            if (a.startsWith("-XX:+") || a.startsWith("-XX:-")) {
                key = "-XX:" + a.substring(5);
                value = String.valueOf(a.charAt(4) == '+');
            } else if (a.startsWith("-XX:") && a.contains("=")) {
                int eq = a.indexOf('=');
                key = a.substring(0, eq);
                String v = a.substring(eq + 1);
                value = v.matches("\\d+[kKmMgGtT]") ? jvmSize(v) : v;
            } else if (a.startsWith("-D")) {
                int eq = a.indexOf('=');
                key = eq < 0 ? a : a.substring(0, eq);
                value = eq < 0 ? "" : a.substring(eq + 1);
            } else if (a.startsWith("-javaagent:") || a.startsWith("-agentpath:") || a.startsWith("-agentlib:")) {
                int colon = a.indexOf(':');
                String spec = a.substring(colon + 1);
                int eq = spec.indexOf('=');
                String jar = eq < 0 ? spec : spec.substring(0, eq);
                key = a.substring(0, colon + 1) + jar.substring(jar.lastIndexOf('/') + 1);
                value = eq < 0 ? "" : spec.substring(eq + 1);
            } else if (sizeFlag(a) != null) {
                key = sizeFlag(a);
                value = jvmSize(a.substring(key.length()));
            } else if (a.startsWith("-Xlog") || a.startsWith("-Xloggc") || a.startsWith("--")) {
                int sep = a.startsWith("--") ? a.indexOf('=') : a.indexOf(':');
                key = sep < 0 ? a : a.substring(0, sep);
                value = sep < 0 ? "true" : a.substring(sep + 1);
            } else {
                key = a;
                value = "true";
            }
            if (SettingNames.secret(key) || key.toLowerCase(Locale.ROOT).contains("password")) value = SettingNames.REDACTED;
            if (selfAddress != null && !selfAddress.isBlank()) value = value.replace(selfAddress, "<self>");
            values.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            rawOf.merge(key, a, (x, y) -> x + " " + y);
        }
        List<Setting> out = new ArrayList<>();
        values.forEach((k, vs) -> {
            List<String> sorted = vs.size() > 1 ? vs.stream().sorted().toList() : vs;
            out.add(new Setting(ConfigModel.JVM, k, String.join(" ", sorted), k, rawOf.get(k), source));
        });
        return out;
    }

    private static String sizeFlag(String a) {
        for (String f : SIZE_FLAGS) if (a.startsWith(f) && a.length() > f.length()) return f;
        return null;
    }

    // ---- OS over SSH ------------------------------------------------------------------------

    /** The script run over SSH; each section starts with a "##name" marker line. */
    public static final String OS_SCRIPT = String.join("; ",
            "pid=$(pgrep -f CassandraDaemon 2>/dev/null | head -n 1)",
            "if [ -n \"$pid\" ] && [ -r /proc/$pid/limits ]; then echo \"##limits-proc $pid\"; cat /proc/$pid/limits; "
                    + "else echo '##limits-soft'; ulimit -S -a 2>/dev/null; echo '##limits-hard'; ulimit -H -a 2>/dev/null; fi",
            "echo '##max_map_count'; cat /proc/sys/vm/max_map_count 2>/dev/null",
            "echo '##swappiness'; cat /proc/sys/vm/swappiness 2>/dev/null",
            "echo '##swaps'; tail -n +2 /proc/swaps 2>/dev/null",
            "echo '##meminfo'; grep -E '^(MemTotal|SwapTotal):' /proc/meminfo 2>/dev/null",
            "echo '##thp_enabled'; cat /sys/kernel/mm/transparent_hugepage/enabled 2>/dev/null",
            "echo '##thp_defrag'; cat /sys/kernel/mm/transparent_hugepage/defrag 2>/dev/null",
            "echo '##nproc'; nproc 2>/dev/null",
            "echo '##end'");

    private static final Map<String, String> PROC_LIMITS = Map.of(
            "Max open files", "nofile", "Max processes", "nproc", "Max locked memory", "memlock",
            "Max address space", "as", "Max file size", "fsize", "Max core file size", "core",
            "Max stack size", "stack", "Max resident set", "rss", "Max data size", "data");

    private static final Map<String, String> ULIMIT_FLAGS = Map.of(
            "n", "nofile", "u", "nproc", "p", "nproc", "l", "memlock", "v", "as", "f", "fsize", "c", "core",
            "s", "stack", "m", "rss", "d", "data");

    /** Parses {@link #OS_SCRIPT} output; sections that came back empty are skipped. */
    public static List<Setting> os(String output) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        String current = null;
        for (String line : output.split("\n")) {
            String l = line.stripTrailing();
            if (l.startsWith("##")) {
                current = l.substring(2).strip();
                sections.put(current, new ArrayList<>());
            } else if (current != null && !l.isBlank()) {
                sections.get(current).add(l);
            }
        }
        List<Setting> out = new ArrayList<>();
        String procKey = sections.keySet().stream().filter(k -> k.startsWith("limits-proc")).findFirst().orElse(null);
        if (procKey != null) {
            String src = "ssh: /proc/" + procKey.substring("limits-proc".length()).strip() + "/limits";
            for (String l : sections.get(procKey)) {
                for (Map.Entry<String, String> e : PROC_LIMITS.entrySet()) {
                    if (l.startsWith(e.getKey() + " ")) {
                        String[] f = l.substring(e.getKey().length()).strip().split("\\s+");
                        if (f.length >= 2) {
                            out.add(new Setting(ConfigModel.OS, "limits." + e.getValue(), f[0] + " / " + f[1],
                                    e.getKey(), l.strip(), src));
                        }
                    }
                }
            }
        } else {
            Map<String, String> soft = ulimits(sections.getOrDefault("limits-soft", List.of()));
            Map<String, String> hard = ulimits(sections.getOrDefault("limits-hard", List.of()));
            soft.forEach((k, v) -> out.add(new Setting(ConfigModel.OS, "limits." + k,
                    v + " / " + hard.getOrDefault(k, "?"), k, v, "ssh: ulimit (login shell, Cassandra process not visible)")));
        }
        first(sections, "max_map_count").ifPresent(v -> out.add(os("vm.max_map_count", v, "ssh: /proc/sys/vm/max_map_count")));
        first(sections, "swappiness").ifPresent(v -> out.add(os("vm.swappiness", v, "ssh: /proc/sys/vm/swappiness")));
        if (sections.containsKey("swaps")) {
            int devices = sections.get("swaps").size();
            out.add(os("swap.devices", String.valueOf(devices), "ssh: /proc/swaps"));
        }
        for (String l : sections.getOrDefault("meminfo", List.of())) {
            String[] f = l.split("\\s+");
            if (f.length >= 2) {
                String name = f[0].equals("MemTotal:") ? "memory.total" : "swap.total";
                String v = SettingNames.withUnit(new BigDecimal(f[1]), "KiB");
                out.add(new Setting(ConfigModel.OS, name, v, f[0].replace(":", ""), l, "ssh: /proc/meminfo"));
            }
        }
        first(sections, "thp_enabled").ifPresent(v -> out.add(os("thp.enabled", bracketed(v), "ssh: /sys/kernel/mm/transparent_hugepage/enabled")));
        first(sections, "thp_defrag").ifPresent(v -> out.add(os("thp.defrag", bracketed(v), "ssh: /sys/kernel/mm/transparent_hugepage/defrag")));
        first(sections, "nproc").ifPresent(v -> out.add(os("cpu.count", v, "ssh: nproc")));
        return out;
    }

    private static Setting os(String name, String value, String source) {
        return new Setting(ConfigModel.OS, name, value.strip(), name, value.strip(), source);
    }

    private static java.util.Optional<String> first(Map<String, List<String>> s, String key) {
        List<String> l = s.get(key);
        return l == null || l.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(l.get(0));
    }

    /** "always [madvise] never" → "madvise". */
    static String bracketed(String v) {
        Matcher m = Pattern.compile("\\[(\\w+)]").matcher(v);
        return m.find() ? m.group(1) : v.strip();
    }

    /**
     * bash: "open files                      (-n) 1024"; dash/busybox: "-n: file descriptors  1024".
     */
    static Map<String, String> ulimits(List<String> lines) {
        Map<String, String> out = new LinkedHashMap<>();
        Pattern bash = Pattern.compile("\\((?:[^)]*,\\s*)?-(\\w)\\)\\s+(\\S+)\\s*$");
        Pattern dash = Pattern.compile("^-(\\w):.*?\\s(\\S+)\\s*$");
        for (String l : lines) {
            Matcher m = bash.matcher(l);
            if (!m.find()) {
                m = dash.matcher(l);
                if (!m.find()) continue;
            }
            String name = ULIMIT_FLAGS.get(m.group(1));
            if (name != null) out.putIfAbsent(name, m.group(2));
        }
        return out;
    }
}
