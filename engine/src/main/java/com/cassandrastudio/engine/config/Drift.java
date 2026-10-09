package com.cassandrastudio.engine.config;

import com.cassandrastudio.engine.config.ConfigModel.DriftReport;
import com.cassandrastudio.engine.config.ConfigModel.DriftRow;
import com.cassandrastudio.engine.config.ConfigModel.DriftSummary;
import com.cassandrastudio.engine.config.ConfigModel.HieraStatus;
import com.cassandrastudio.engine.config.ConfigModel.NodeConfig;
import com.cassandrastudio.engine.config.ConfigModel.NodeRef;
import com.cassandrastudio.engine.config.ConfigModel.Setting;
import com.cassandrastudio.engine.config.ConfigModel.Snapshot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The drift report (CFG-2): settings whose values differ between the nodes of a cluster or of
 * one DC, and nodes whose cassandra.yaml settings differ from the values expected from Hiera.
 * Per-node settings (addresses, tokens) are shown but never count. A setting a node does not
 * report at all (another version, or only partly readable) is listed as missing, not as drift.
 */
public final class Drift {
    private Drift() {}

    public static final String SCOPE_CLUSTER = "cluster";
    public static final String SCOPE_DC = "dc";

    /**
     * @param expected per node address: canonical yaml name → expected value (null = no Hiera)
     */
    public static DriftReport report(Snapshot snap, String scope, boolean onlyDifferences,
                                     Map<String, Map<String, Hiera.Expected>> expected, HieraStatus hiera) {
        List<NodeConfig> nodes = snap.nodes();
        Map<String, String> dcOf = new LinkedHashMap<>();
        for (NodeConfig n : nodes) dcOf.put(n.address(), n.datacenter());
        // category|name → address → setting
        Map<String, Map<String, Setting>> grid = new TreeMap<>();
        for (NodeConfig n : nodes) {
            for (Setting s : n.settings()) {
                grid.computeIfAbsent(s.category() + "|" + s.name(), k -> new LinkedHashMap<>()).put(n.address(), s);
            }
        }
        if (expected != null) {
            expected.forEach((addr, exp) -> exp.keySet().forEach(name ->
                    grid.computeIfAbsent(ConfigModel.YAML + "|" + name, k -> new LinkedHashMap<>())));
        }
        List<DriftRow> rows = new ArrayList<>();
        int inCluster = 0;
        int inDc = 0;
        int compared = 0;
        int mismatched = 0;
        for (Map.Entry<String, Map<String, Setting>> e : grid.entrySet()) {
            String category = e.getKey().substring(0, e.getKey().indexOf('|'));
            String name = e.getKey().substring(e.getKey().indexOf('|') + 1);
            Map<String, Setting> byNode = e.getValue();
            boolean perNode = SettingNames.perNode(name);
            Map<String, String> values = new LinkedHashMap<>();
            List<String> missing = new ArrayList<>();
            for (NodeConfig n : nodes) {
                Setting s = byNode.get(n.address());
                if (s == null) missing.add(n.address());
                else values.put(n.address(), s.value());
            }
            boolean differs = !perNode && distinct(values.values()) > 1;
            List<String> dcs = new ArrayList<>();
            if (!perNode) {
                Map<String, List<String>> perDc = new TreeMap<>();
                values.forEach((addr, v) -> perDc.computeIfAbsent(String.valueOf(dcOf.get(addr)), k -> new ArrayList<>())
                        .add(v));
                perDc.forEach((dc, vs) -> {
                    if (distinct(vs) > 1) dcs.add(dc);
                });
            }
            Map<String, String> exp = null;
            Map<String, String> expSrc = null;
            List<String> mismatches = new ArrayList<>();
            if (expected != null && category.equals(ConfigModel.YAML)) {
                exp = new LinkedHashMap<>();
                expSrc = new LinkedHashMap<>();
                for (NodeConfig n : nodes) {
                    Hiera.Expected x = expected.getOrDefault(n.address(), Map.of()).get(name);
                    if (x == null) continue;
                    exp.put(n.address(), x.value());
                    expSrc.put(n.address(), x.source());
                    Setting actual = byNode.get(n.address());
                    if (actual == null) continue; // not reported by this node: nothing to compare
                    if (SettingNames.REDACTED.equals(actual.value())) continue;
                    if (!Objects.equals(actual.value(), x.value())) mismatches.add(n.address());
                }
                if (exp.isEmpty()) {
                    exp = null;
                    expSrc = null;
                }
            }
            if (exp != null) compared++;
            if (!mismatches.isEmpty()) mismatched++;
            if (differs) inCluster++;
            if (!dcs.isEmpty()) inDc++;
            if (values.isEmpty() && exp == null) continue;
            boolean relevant = SCOPE_DC.equals(scope) ? !dcs.isEmpty() : differs;
            if (onlyDifferences && !relevant && mismatches.isEmpty()) continue;
            rows.add(new DriftRow(category, name, perNode, values, differs, dcs, missing, exp, expSrc, mismatches));
        }
        rows.sort((a, b) -> {
            int c = Integer.compare(order(a.category()), order(b.category()));
            return c != 0 ? c : a.name().compareTo(b.name());
        });
        List<NodeRef> refs = nodes.stream().map(n -> new NodeRef(n.address(), n.datacenter(), n.rack(), n.version())).toList();
        return new DriftReport(scope, onlyDifferences, snap.collectedAtMs(), refs, rows,
                new DriftSummary(grid.size(), inCluster, inDc, compared, mismatched), hiera);
    }

    private static int order(String category) {
        return switch (category) {
            case ConfigModel.YAML -> 0;
            case ConfigModel.JVM -> 1;
            default -> 2;
        };
    }

    private static int distinct(java.util.Collection<String> values) {
        Set<String> s = new HashSet<>();
        for (String v : values) s.add(v == null ? "\u0000null" : v);
        return s.size();
    }

    /** The DCs of a snapshot, sorted. */
    public static Set<String> datacenters(Snapshot snap) {
        Set<String> out = new TreeSet<>();
        snap.nodes().forEach(n -> out.add(n.datacenter()));
        return out;
    }
}
