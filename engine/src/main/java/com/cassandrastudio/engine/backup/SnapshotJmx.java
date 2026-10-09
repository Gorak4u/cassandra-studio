package com.cassandrastudio.engine.backup;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.management.JMException;
import javax.management.MBeanException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

/**
 * Snapshots over JMX (StorageService), the "snapshots only" provider (BAK-1 c). Operation
 * signatures differ by version, so each call tries the newest first:
 * <ul>
 *   <li>takeSnapshot(String, Map, String...) since 3.4; takeSnapshot(String, String...) before.</li>
 *   <li>getSnapshotDetails(Map) since 4.1 (rows gain "Creation time"/"Expiration time");
 *       the SnapshotDetails attribute (getter) before.</li>
 *   <li>clearSnapshot(Map, String, String...) since 4.1; clearSnapshot(String, String...) before.</li>
 * </ul>
 * Sizes come as text ("1.21 KiB" in 4.x/5.0, "1.21 KB" in 3.11) and are parsed back to bytes.
 */
final class SnapshotJmx {
    private SnapshotJmx() {}

    static final ObjectName STORAGE = name("org.apache.cassandra.db:type=StorageService");
    static final Pattern TAG = Pattern.compile("[A-Za-z0-9._-]{1,100}");
    private static final Pattern SIZE = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*([A-Za-z]*)");
    private static final String STRING = "java.lang.String";
    private static final String MAP = "java.util.Map";
    private static final String STRINGS = "[Ljava.lang.String;";

    static void take(MBeanServerConnection m, String tag, List<String> keyspaces) throws IOException, JMException {
        String[] ks = keyspaces.toArray(String[]::new);
        Map<String, String> options = new HashMap<>(Map.of("skipFlush", "false"));
        try {
            m.invoke(STORAGE, "takeSnapshot", new Object[]{tag, options, ks}, new String[]{STRING, MAP, STRINGS});
        } catch (ReflectionException e) {
            m.invoke(STORAGE, "takeSnapshot", new Object[]{tag, ks}, new String[]{STRING, STRINGS});
        }
    }

    static void clear(MBeanServerConnection m, String tag, List<String> keyspaces) throws IOException, JMException {
        String[] ks = keyspaces.toArray(String[]::new);
        try {
            m.invoke(STORAGE, "clearSnapshot", new Object[]{new HashMap<String, Object>(), tag, ks},
                    new String[]{MAP, STRING, STRINGS});
        } catch (ReflectionException e) {
            m.invoke(STORAGE, "clearSnapshot", new Object[]{tag, ks}, new String[]{STRING, STRINGS});
        }
    }

    /** Snapshot tag → its rows (one per table). */
    @SuppressWarnings("unchecked")
    static Map<String, TabularData> details(MBeanServerConnection m) throws IOException, JMException {
        Object r;
        try {
            r = m.invoke(STORAGE, "getSnapshotDetails", new Object[]{new HashMap<String, String>()}, new String[]{MAP});
        } catch (ReflectionException e) {
            // Before 4.1 the no-argument getter is all there is, which JMX exposes as an attribute.
            r = m.getAttribute(STORAGE, "SnapshotDetails");
        }
        return r == null ? Map.of() : (Map<String, TabularData>) r;
    }

    /** One table's row of a snapshot, version-neutral. */
    record Row(String tag, String keyspace, String table, Long trueSize, Long sizeOnDisk, Long createdMs, Long expiresMs) {}

    static List<Row> rows(Map<String, TabularData> details) {
        List<Row> out = new ArrayList<>();
        for (var e : details.entrySet()) {
            if (e.getValue() == null) continue;
            for (Object o : e.getValue().values()) {
                CompositeData cd = (CompositeData) o;
                out.add(new Row(str(cd, "Snapshot name", e.getKey()), str(cd, "Keyspace name", null),
                        str(cd, "Column family name", null), parseSize(str(cd, "True size", null)),
                        parseSize(str(cd, "Size on disk", null)), instant(str(cd, "Creation time", null)),
                        instant(str(cd, "Expiration time", null))));
            }
        }
        return out;
    }

    /** Catalogue rows: one per (node, tag), summed over its tables. */
    static List<BackupEntry> entries(List<Row> rows, String node, String host, String datacenter) {
        Map<String, List<Row>> byTag = new LinkedHashMap<>();
        for (Row r : rows) byTag.computeIfAbsent(r.tag(), k -> new ArrayList<>()).add(r);
        List<BackupEntry> out = new ArrayList<>();
        for (var e : byTag.entrySet()) {
            long size = 0;
            boolean sized = false;
            Long created = null, expires = null;
            TreeSet<String> keyspaces = new TreeSet<>();
            for (Row r : e.getValue()) {
                Long s = r.sizeOnDisk() != null ? r.sizeOnDisk() : r.trueSize();
                if (s != null) {
                    size += s;
                    sized = true;
                }
                if (r.createdMs() != null) created = created == null ? r.createdMs() : Math.min(created, r.createdMs());
                if (r.expiresMs() != null) expires = r.expiresMs();
                if (r.keyspace() != null) keyspaces.add(r.keyspace());
            }
            List<String> notes = new ArrayList<>();
            notes.add("keyspaces: " + String.join(", ", keyspaces));
            if (created == null) notes.add("time: this Cassandra version does not report a snapshot's creation time");
            out.add(new BackupEntry(e.getKey(), "snapshot", node, host, datacenter, "snapshot", created,
                    sized ? size : null, null, BackupEntry.COMPLETE, e.getValue().size() + " tables",
                    "node-local: <data_file_directories>/<ks>/<table>/snapshots/" + e.getKey() + "/",
                    expires == null ? "kept until cleared (no TTL)" : "TTL: removed by Cassandra at expiry", expires,
                    "n/a (node-local files)", null, e.getValue().size(), null, String.join("; ", notes)));
        }
        return out;
    }

    /** "1.21 KiB", "1.21 KB", "512 bytes", "0 bytes", "12.34 MB" → bytes; null when unreadable. */
    static Long parseSize(String s) {
        if (s == null || s.isBlank()) return null;
        Matcher m = SIZE.matcher(s.strip());
        if (!m.matches()) return null;
        double v;
        try {
            v = Double.parseDouble(m.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
        String unit = m.group(2).toUpperCase(Locale.ROOT);
        long mult = switch (unit) {
            case "", "B", "BYTE", "BYTES" -> 1L;
            case "K", "KB", "KIB" -> 1L << 10;
            case "M", "MB", "MIB" -> 1L << 20;
            case "G", "GB", "GIB" -> 1L << 30;
            case "T", "TB", "TIB" -> 1L << 40;
            default -> -1L;
        };
        return mult < 0 ? null : Math.round(v * mult);
    }

    /** JMX failures as one readable line: the server's own message where it gave one. */
    static String message(Exception e) {
        Throwable t = e instanceof MBeanException me && me.getCause() != null ? me.getCause() : e;
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private static String str(CompositeData cd, String key, String dflt) {
        if (!cd.containsKey(key)) return dflt;
        Object v = cd.get(key);
        return v == null ? dflt : v.toString();
    }

    private static Long instant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s.strip()).toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static ObjectName name(String n) {
        try {
            return new ObjectName(n);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
