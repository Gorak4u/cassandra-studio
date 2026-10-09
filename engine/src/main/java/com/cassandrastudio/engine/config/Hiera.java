package com.cassandrastudio.engine.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Expected cassandra.yaml values from a Puppet control repo (CFG-2): reads hiera.yaml's
 * hierarchy, looks keys up first-match through the data files for a set of facts, and maps
 * Hiera keys to cassandra.yaml settings through the module's cassandra.yaml.erb template and the
 * profile's {@code $x = lookup('profile::x', {'default_value' => ...})} calls (defaults included).
 * Encrypted (eyaml) layers are skipped: secrets are never compared.
 *
 * <p>Facts use friendly names (customer, environment, product, cluster, datacenter, role,
 * certname, os.name, os.family, os.release.major), each standing for the trusted-extension and
 * fact variables the hierarchy interpolates; any other variable can be given by its own name.
 */
public final class Hiera {
    /** friendly fact name → hierarchy variables it fills. */
    public static final Map<String, List<String>> ALIASES = new LinkedHashMap<>();

    static {
        ALIASES.put("customer", List.of("trusted.extensions.pp_project", "facts.customer", "customer"));
        ALIASES.put("environment", List.of("trusted.extensions.pp_environment", "facts.customer_environment",
                "facts.environment", "environment", "server_facts.environment"));
        ALIASES.put("product", List.of("trusted.extensions.pp_product", "facts.product", "product"));
        ALIASES.put("cluster", List.of("trusted.extensions.pp_cluster", "facts.cluster_id", "cluster_id"));
        ALIASES.put("datacenter", List.of("trusted.extensions.pp_datacenter", "facts.datacenter", "datacenter"));
        ALIASES.put("role", List.of("trusted.extensions.pp_role", "facts.puppet_role", "facts.role", "role"));
        ALIASES.put("certname", List.of("trusted.certname", "facts.fqdn", "facts.networking.fqdn", "clientcert"));
        ALIASES.put("os.name", List.of("facts.os.name"));
        ALIASES.put("os.family", List.of("facts.os.family"));
        ALIASES.put("os.release.major", List.of("facts.os.release.major"));
    }

    private static final Pattern VAR = Pattern.compile("%\\{(?:::)?([^}]+)}");
    private static final Pattern LOOKUP = Pattern.compile(
            "\\$(\\w+)\\s*=\\s*lookup\\(\\s*'([\\w:]+)'\\s*(?:,\\s*\\{\\s*'default_value'\\s*=>\\s*(.+?)\\s*}\\s*)?\\)");
    private static final Pattern PASS = Pattern.compile("^\\s*(\\w+)\\s*=>\\s*\\$(\\w+)\\s*,?\\s*$");
    private static final Pattern TEMPLATE_LINE = Pattern.compile(
            "^(\\w+):\\s*['\"]?<%=\\s*@(\\w+)\\s*%>['\"]?(\\S*)\\s*$");
    private static final Pattern TEMPLATE_IF = Pattern.compile("^<%-?\\s*if\\s+@(\\w+)\\s*-?%>\\s*$");
    private static final Pattern TEMPLATE_LIST_ITEM = Pattern.compile("^\\s+-\\s*<%=\\s*@(\\w+)\\s*%>\\s*$");
    private static final Pattern TEMPLATE_SEEDS = Pattern.compile("seeds:\\s*\"?<%=\\s*@(\\w+)\\.join");

    public record Level(String name, List<String> paths) {}

    /** One template variable feeding one cassandra.yaml setting. */
    public record Mapping(String yamlKey, String variable, String suffix, boolean truthyOnly, boolean list,
                          boolean seeds) {}

    /** A Hiera key and the profile's default for it (null: no usable default). */
    public record KeySpec(String key, Object defaultValue) {}

    /** One expected setting: canonical name, normalised value, where it came from. */
    public record Expected(String name, String value, String source) {}

    private final Path root;
    private final Path dataDir;
    private final List<Level> levels;
    private final List<Mapping> mappings;
    private final Map<String, KeySpec> keyForVariable;
    private final String extraOptionsKey;
    private final Map<Path, Map<String, Object>> files = new HashMap<>();

    private Hiera(Path root, Path dataDir, List<Level> levels, List<Mapping> mappings,
                  Map<String, KeySpec> keyForVariable, String extraOptionsKey) {
        this.root = root;
        this.dataDir = dataDir;
        this.levels = levels;
        this.mappings = mappings;
        this.keyForVariable = keyForVariable;
        this.extraOptionsKey = extraOptionsKey;
    }

    /** Loads a control repo; throws {@link IllegalArgumentException} with a readable reason. */
    @SuppressWarnings("unchecked")
    public static Hiera load(Path root) {
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Control repo not found: " + root);
        Path hieraYaml = root.resolve("hiera.yaml");
        if (!Files.isRegularFile(hieraYaml)) throw new IllegalArgumentException("No hiera.yaml in " + root);
        Map<String, Object> h;
        try {
            h = YamlLite.parseMap(Files.readString(hieraYaml));
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Cannot read hiera.yaml: " + e.getMessage());
        }
        String datadir = "data";
        if (h.get("defaults") instanceof Map<?, ?> d && d.get("datadir") instanceof String s) datadir = s;
        List<Level> levels = new ArrayList<>();
        if (h.get("hierarchy") instanceof List<?> hier) {
            for (Object o : hier) {
                if (!(o instanceof Map<?, ?> m)) continue;
                if (m.containsKey("lookup_key") || m.containsKey("data_hash") && !"yaml_data".equals(m.get("data_hash"))) {
                    continue; // eyaml and custom backends: never read
                }
                List<String> paths = new ArrayList<>();
                if (m.get("path") instanceof String p) paths.add(p);
                if (m.get("paths") instanceof List<?> ps) ps.forEach(p -> paths.add(String.valueOf(p)));
                if (!paths.isEmpty()) levels.add(new Level(String.valueOf(m.get("name")), paths));
            }
        }
        if (levels.isEmpty()) throw new IllegalArgumentException("hiera.yaml has no plain YAML hierarchy levels");

        // profile lookups and parameter passing, from every manifest under site-modules
        Map<String, KeySpec> lookups = new HashMap<>(); // local variable -> key
        Map<String, String> passes = new HashMap<>(); // class parameter -> local variable
        Path template = null;
        Path modules = root.resolve("site-modules");
        if (Files.isDirectory(modules)) {
            try (Stream<Path> walk = Files.walk(modules, 6)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    String fn = p.getFileName().toString();
                    if (fn.equals("cassandra.yaml.erb") && template == null) template = p;
                    if (!fn.endsWith(".pp")) continue;
                    String text = Files.readString(p);
                    Matcher lm = LOOKUP.matcher(text);
                    while (lm.find()) {
                        lookups.putIfAbsent(lm.group(1), new KeySpec(lm.group(2), puppetLiteral(lm.group(3))));
                    }
                    for (String line : text.split("\n")) {
                        Matcher pm = PASS.matcher(line);
                        if (pm.matches()) passes.putIfAbsent(pm.group(1), pm.group(2));
                    }
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read site-modules: " + e.getMessage());
            }
        }
        if (template == null) throw new IllegalArgumentException("No cassandra.yaml.erb template under site-modules");
        List<Mapping> mappings;
        try {
            mappings = parseTemplate(Files.readString(template));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read " + template + ": " + e.getMessage());
        }
        Map<String, KeySpec> keyForVariable = new HashMap<>();
        for (Mapping m : mappings) keyForVariable.put(m.variable(), keyFor(m.variable(), passes, lookups));
        KeySpec extra = keyFor("cassandra_yaml_extra_options", passes, lookups);
        return new Hiera(root, root.resolve(datadir), levels, mappings, keyForVariable, extra.key());
    }

    private static KeySpec keyFor(String variable, Map<String, String> passes, Map<String, KeySpec> lookups) {
        String local = passes.getOrDefault(variable, variable);
        KeySpec k = lookups.get(local);
        if (k == null) k = lookups.get(variable);
        return k != null ? k : new KeySpec("profile_cassandra_pfpt::" + variable, null);
    }

    /** 'x' / "x" / 42 / true / false / undef; anything else (expressions) gives null. */
    static Object puppetLiteral(String s) {
        if (s == null) return null;
        String t = s.strip();
        if (t.equals("undef")) return null;
        if (t.length() >= 2 && (t.startsWith("'") && t.endsWith("'") || t.startsWith("\"") && t.endsWith("\""))) {
            String in = t.substring(1, t.length() - 1);
            return in.contains("${") ? null : in;
        }
        if (t.matches("-?\\d+(\\.\\d+)?") || t.equals("true") || t.equals("false")) return t;
        return null;
    }

    static List<Mapping> parseTemplate(String erb) {
        List<Mapping> out = new ArrayList<>();
        Set<String> truthyOnly = new TreeSet<>();
        String[] lines = erb.split("\n");
        String listKey = null;
        for (String line : lines) {
            Matcher im = TEMPLATE_IF.matcher(line.strip());
            if (im.matches()) truthyOnly.add(im.group(1));
        }
        Set<String> seen = new TreeSet<>();
        for (String line : lines) {
            Matcher tm = TEMPLATE_LINE.matcher(line);
            if (tm.matches()) {
                String canonical = SettingNames.canonical(tm.group(1));
                // the typed (4.1+) line and the legacy line feed the same setting: keep the first
                if (seen.add(canonical)) {
                    out.add(new Mapping(tm.group(1), tm.group(2), tm.group(3), truthyOnly.contains(tm.group(2)), false, false));
                }
                listKey = null;
                continue;
            }
            if (line.matches("^\\w+:\\s*$")) {
                listKey = line.strip().replace(":", "");
                continue;
            }
            Matcher li = TEMPLATE_LIST_ITEM.matcher(line);
            if (li.matches() && listKey != null && seen.add(listKey)) {
                out.add(new Mapping(listKey, li.group(1), "", false, true, false));
                continue;
            }
            Matcher sm = TEMPLATE_SEEDS.matcher(line);
            if (sm.find() && seen.add("seed_provider.seeds")) {
                out.add(new Mapping("seed_provider.seeds", sm.group(1), "", false, false, true));
            }
            if (!line.startsWith(" ") && !line.startsWith("<")) listKey = null;
        }
        return out;
    }

    public List<Level> levels() {
        return levels;
    }

    public List<Mapping> mappings() {
        return mappings;
    }

    /** The hierarchy variables, e.g. trusted.extensions.pp_project. */
    public Set<String> variables() {
        Set<String> out = new TreeSet<>();
        for (Level l : levels) for (String p : l.paths()) {
            Matcher m = VAR.matcher(p);
            while (m.find()) out.add(m.group(1));
        }
        return out;
    }

    /** Expands friendly facts to the variables they fill. Explicit variable names win. */
    public static Map<String, String> variablesFor(Map<String, String> facts) {
        Map<String, String> vars = new HashMap<>();
        facts.forEach((k, v) -> {
            if (v == null || v.isBlank()) return;
            List<String> targets = ALIASES.get(k);
            if (targets != null) targets.forEach(t -> vars.putIfAbsent(t, v.strip()));
        });
        facts.forEach((k, v) -> {
            if (v != null && !v.isBlank() && !ALIASES.containsKey(k)) vars.put(k, v.strip());
        });
        return vars;
    }

    /** Data files (relative to the data dir) the hierarchy resolves to, most specific first, existing only. */
    public List<String> layers(Map<String, String> vars) {
        List<String> out = new ArrayList<>();
        for (Level l : levels) {
            for (String p : l.paths()) {
                String rel = interpolate(p, vars);
                if (rel == null) continue;
                Path f = dataDir.resolve(rel).normalize();
                if (!f.startsWith(dataDir) || !Files.isRegularFile(f)) continue;
                if (!out.contains(rel)) out.add(rel);
            }
        }
        return out;
    }

    static String interpolate(String path, Map<String, String> vars) {
        Matcher m = VAR.matcher(path);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = vars.get(m.group(1));
            if (v == null || v.contains("/") || v.contains("..")) return null;
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Map<String, Object> data(String rel) {
        Path f = dataDir.resolve(rel);
        return files.computeIfAbsent(f, p -> {
            try {
                return YamlLite.parseMap(Files.readString(p));
            } catch (IOException | RuntimeException e) {
                return Map.of();
            }
        });
    }

    /** First-match lookup: the value and the file it came from, or null. */
    public Map.Entry<Object, String> lookup(String key, List<String> layers) {
        for (String rel : layers) {
            Map<String, Object> d = data(rel);
            if (d.containsKey(key)) return Map.entry(nullSafe(d.get(key)), rel);
        }
        return null;
    }

    private static Object nullSafe(Object o) {
        return o == null ? "" : o;
    }

    /** Expected cassandra.yaml settings for one node's facts, by canonical name. */
    public Map<String, Expected> expected(Map<String, String> vars) {
        List<String> layers = layers(vars);
        Map<String, Expected> out = new TreeMap<>();
        for (Mapping m : mappings) {
            KeySpec ks = keyForVariable.get(m.variable());
            Map.Entry<Object, String> hit = lookup(ks.key(), layers);
            Object value = hit != null ? hit.getKey() : ks.defaultValue();
            String source = hit != null ? hit.getValue() + " (" + ks.key() + ")" : "module default (" + ks.key() + ")";
            if (value == null || "".equals(value)) continue;
            if (m.truthyOnly() && "false".equals(String.valueOf(value))) continue;
            String name;
            String v;
            if (m.seeds()) {
                name = "seed_provider.seeds";
                v = SettingNames.seeds(value instanceof List<?> l ? String.join(",", l.stream().map(String::valueOf).toList())
                        : String.valueOf(value));
            } else if (m.list()) {
                name = SettingNames.canonical(m.yamlKey());
                v = value instanceof List<?> l ? "[" + String.join(", ", l.stream().map(String::valueOf).toList()) + "]"
                        : "[" + value + "]";
            } else {
                if (value instanceof Map || value instanceof List) continue;
                name = SettingNames.canonical(m.yamlKey());
                v = SettingNames.normalize(m.yamlKey(), value + m.suffix());
            }
            if (v != null && !SettingNames.secret(name)) out.put(name, new Expected(name, v, source));
        }
        if (extraOptionsKey != null) {
            Map.Entry<Object, String> hit = lookup(extraOptionsKey, layers);
            if (hit != null && hit.getKey() instanceof Map<?, ?> extra) {
                extra.forEach((k, val) -> {
                    if (val instanceof Map || val instanceof List || val == null) return;
                    String raw = String.valueOf(k);
                    String name = SettingNames.canonical(raw);
                    if (out.containsKey(name) || SettingNames.secret(name)) return;
                    String v = SettingNames.normalize(raw, String.valueOf(val));
                    if (v != null) out.put(name, new Expected(name, v, hit.getValue() + " (" + extraOptionsKey + "." + raw + ")"));
                });
            }
        }
        return out;
    }

    /**
     * The values each friendly fact takes in this repo, found by matching the hierarchy's path
     * templates against the data directory (for the UI's pickers).
     */
    public Map<String, List<String>> factValues() {
        Map<String, Set<String>> byVar = new TreeMap<>();
        for (Level l : levels) for (String p : l.paths()) collect(dataDir, p.split("/"), 0, new HashMap<>(), byVar);
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> a : ALIASES.entrySet()) {
            Set<String> vals = new TreeSet<>();
            for (String v : a.getValue()) vals.addAll(byVar.getOrDefault(v, Set.of()));
            if (!vals.isEmpty()) out.put(a.getKey(), List.copyOf(vals));
        }
        return out;
    }

    private static void collect(Path dir, String[] segs, int i, Map<String, String> bound, Map<String, Set<String>> out) {
        if (i >= segs.length || !Files.isDirectory(dir)) return;
        String seg = segs[i];
        boolean last = i == segs.length - 1;
        Matcher vm = VAR.matcher(seg);
        if (!vm.find()) {
            if (!last) collect(dir.resolve(seg), segs, i + 1, bound, out);
            return;
        }
        // segment with variables → regex with one group per variable
        List<String> names = new ArrayList<>();
        StringBuilder rx = new StringBuilder();
        Matcher m = VAR.matcher(seg);
        int at = 0;
        while (m.find()) {
            rx.append(Pattern.quote(seg.substring(at, m.start())));
            String var = m.group(1);
            if (bound.containsKey(var)) {
                rx.append(Pattern.quote(bound.get(var)));
            } else {
                rx.append("([^/]+?)");
                names.add(var);
            }
            at = m.end();
        }
        rx.append(Pattern.quote(seg.substring(at)));
        Pattern p = Pattern.compile(rx.toString());
        try (Stream<Path> children = Files.list(dir)) {
            for (Path c : children.toList()) {
                Matcher cm = p.matcher(c.getFileName().toString());
                if (!cm.matches() || last != Files.isRegularFile(c)) continue;
                Map<String, String> b = new HashMap<>(bound);
                for (int g = 0; g < names.size(); g++) b.put(names.get(g), cm.group(g + 1));
                if (last) {
                    b.forEach((k, v) -> out.computeIfAbsent(k, x -> new TreeSet<>()).add(v));
                } else {
                    collect(c, segs, i + 1, b, out);
                }
            }
        } catch (IOException e) {
            // unreadable directory: nothing to offer
        }
    }

    public Path root() {
        return root;
    }
}
