package com.cassandrastudio.engine.bulk;

import com.cassandrastudio.engine.util.ApiException;
import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Request bodies of the bulk API (docs/api/bulk.md), validated: 400 with a clear message otherwise. */
public final class BulkOptions {
    private BulkOptions() {}

    public enum Format { CSV, JSON }

    /** How values look in the file (both directions). */
    public record TextOptions(char delimiter, String nullString, boolean header, String timestampFormat,
                              String dateFormat, ZoneId zone, ValueConverter.BlobFormat blobFormat) {}

    public record Unload(String mode, String keyspace, String table, List<String> columns, String query, Path path,
                         boolean overwrite, Format format, boolean gzip, TextOptions text, ConsistencyLevel consistency,
                         int pageSize, int concurrency, long maxRows, int timeoutMs) {
        public boolean tableMode() {
            return "table".equals(mode);
        }
    }

    /** One table column fed from one file column (CSV header name, c1..cN without a header, or JSON key). */
    public record Mapping(String column, String source) {}

    public record Load(String keyspace, String table, Path path, Format format, Boolean gzip, TextOptions text,
                       List<Mapping> mapping, Integer ttlSeconds, String ttlField, Long timestampMicros,
                       String timestampField, int batchSize, int concurrency, int rateLimit, long maxErrors,
                       boolean dryRun, ConsistencyLevel consistency, int timeoutMs) {}

    public static Unload unload(JsonNode b) {
        String mode = text(b, "mode", "table").toLowerCase(Locale.ROOT);
        if (!mode.equals("table") && !mode.equals("query")) throw ApiException.badRequest("mode must be 'table' or 'query'");
        String ks = text(b, "keyspace", null);
        String table = text(b, "table", null);
        String query = text(b, "query", null);
        if (mode.equals("table") && (ks == null || table == null)) throw ApiException.badRequest("keyspace and table are required");
        if (mode.equals("query") && query == null) throw ApiException.badRequest("query is required");
        List<String> columns = new ArrayList<>();
        if (b.has("columns") && b.get("columns").isArray()) b.get("columns").forEach(c -> {
            if (!c.asText().isBlank()) columns.add(c.asText());
        });
        Format format = format(b, null);
        boolean gzip = "gzip".equalsIgnoreCase(text(b, "compression", "none"));
        String defaultName = (mode.equals("table") ? ks + "." + table : "query") + (format == Format.JSON ? ".jsonl" : ".csv")
                + (gzip ? ".gz" : "");
        Path path = BulkFiles.resolve(text(b, "path", defaultName));
        return new Unload(mode, ks, table, columns, query, path, b.path("overwrite").asBoolean(false),
                format, gzip, textOptions(b), consistency(b, "LOCAL_ONE"),
                intIn(b, "pageSize", 5000, 10, 100_000), intIn(b, "concurrency", 8, 1, 64),
                longIn(b, "maxRows", 0, 0, Long.MAX_VALUE), intIn(b, "timeoutMs", 60_000, 1000, 600_000));
    }

    public static Load load(JsonNode b) {
        return load(b, true);
    }

    /** {@code requireTable} false for the file preview, which works before a table is chosen. */
    public static Load load(JsonNode b, boolean requireTable) {
        String ks = requireTable ? required(b, "keyspace") : text(b, "keyspace", null);
        String table = requireTable ? required(b, "table") : text(b, "table", null);
        Path path = BulkFiles.resolve(required(b, "path"));
        Format format = format(b, path);
        String comp = text(b, "compression", "auto").toLowerCase(Locale.ROOT);
        Boolean gzip = switch (comp) {
            case "auto" -> null;
            case "gzip" -> true;
            case "none" -> false;
            default -> throw ApiException.badRequest("compression must be auto, none or gzip");
        };
        List<Mapping> mapping = new ArrayList<>();
        JsonNode m = b.get("mapping");
        if (m != null && m.isArray()) {
            for (JsonNode e : m) {
                String col = e.path("column").asText("");
                String src = e.path("source").asText("");
                if (col.isBlank()) throw ApiException.badRequest("every mapping entry needs a column");
                if (!src.isBlank()) mapping.add(new Mapping(col, src));
            }
        }
        Integer ttl = b.hasNonNull("ttlSeconds") && !b.get("ttlSeconds").asText().isBlank()
                ? intIn(b, "ttlSeconds", 0, 0, 630_720_000) : null;
        Long ts = null;
        if (b.hasNonNull("timestampMicros") && !b.get("timestampMicros").asText().isBlank()) {
            ts = longIn(b, "timestampMicros", 0, 0, Long.MAX_VALUE);
        }
        String ttlField = text(b, "ttlField", null);
        String tsField = text(b, "timestampField", null);
        if (ttl != null && ttlField != null) throw ApiException.badRequest("Set either a fixed TTL or a TTL column, not both");
        if (ts != null && tsField != null) throw ApiException.badRequest("Set either a fixed timestamp or a timestamp column, not both");
        return new Load(ks, table, path, format, gzip, textOptions(b), mapping, ttl, ttlField, ts, tsField,
                intIn(b, "batchSize", 32, 1, 500), intIn(b, "concurrency", 16, 1, 256), intIn(b, "rateLimit", 0, 0, 10_000_000),
                longIn(b, "maxErrors", 100, -1, Long.MAX_VALUE), b.path("dryRun").asBoolean(false),
                consistency(b, "LOCAL_QUORUM"), intIn(b, "timeoutMs", 30_000, 1000, 600_000));
    }

    static TextOptions textOptions(JsonNode b) {
        String d = b.has("delimiter") ? b.get("delimiter").asText() : ",";
        char delim = switch (d) {
            case "\\t", "\t", "tab", "TAB" -> '\t';
            default -> {
                if (d.length() != 1) throw ApiException.badRequest("delimiter must be a single character (or \\t)");
                if (d.charAt(0) == '"' || d.charAt(0) == '\n' || d.charAt(0) == '\r') {
                    throw ApiException.badRequest("delimiter cannot be a quote or a line break");
                }
                yield d.charAt(0);
            }
        };
        String nullString = b.has("nullString") && !b.get("nullString").isNull() ? b.get("nullString").asText() : "";
        String tsf = text(b, "timestampFormat", null);
        String df = text(b, "dateFormat", null);
        for (String p : new String[] {tsf, df}) {
            String err = ValueConverter.checkPattern(p);
            if (err != null) throw ApiException.badRequest(err);
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(text(b, "timeZone", "UTC"));
        } catch (DateTimeException e) {
            throw ApiException.badRequest("timeZone '" + b.get("timeZone").asText() + "' is not a known zone (e.g. UTC, Europe/Berlin)");
        }
        ValueConverter.BlobFormat blob = "base64".equalsIgnoreCase(text(b, "blobFormat", "hex"))
                ? ValueConverter.BlobFormat.BASE64 : ValueConverter.BlobFormat.HEX;
        return new TextOptions(delim, nullString, b.path("header").asBoolean(true), tsf, df, zone, blob);
    }

    static Format format(JsonNode b, Path path) {
        String f = text(b, "format", null);
        if (f == null && path != null) {
            String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (n.endsWith(".gz")) n = n.substring(0, n.length() - 3);
            f = n.endsWith(".json") || n.endsWith(".jsonl") || n.endsWith(".ndjson") ? "json" : "csv";
        }
        if (f == null) return Format.CSV;
        return switch (f.toLowerCase(Locale.ROOT)) {
            case "csv" -> Format.CSV;
            case "json", "jsonl" -> Format.JSON;
            default -> throw ApiException.badRequest("format must be csv or json");
        };
    }

    static ConsistencyLevel consistency(JsonNode b, String dflt) {
        String c = text(b, "consistency", dflt).toUpperCase(Locale.ROOT);
        try {
            DefaultConsistencyLevel cl = DefaultConsistencyLevel.valueOf(c);
            if (cl == DefaultConsistencyLevel.SERIAL || cl == DefaultConsistencyLevel.LOCAL_SERIAL) {
                throw ApiException.badRequest("consistency " + c + " is only for lightweight transactions");
            }
            return cl;
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("consistency '" + c + "' is not a consistency level");
        }
    }

    private static String text(JsonNode b, String f, String dflt) {
        JsonNode v = b.get(f);
        return v == null || v.isNull() || v.asText().isBlank() ? dflt : v.asText().trim();
    }

    private static String required(JsonNode b, String f) {
        String v = text(b, f, null);
        if (v == null) throw ApiException.badRequest(f + " is required");
        return v;
    }

    private static int intIn(JsonNode b, String f, int dflt, int min, int max) {
        return (int) longIn(b, f, dflt, min, max);
    }

    private static long longIn(JsonNode b, String f, long dflt, long min, long max) {
        JsonNode v = b.get(f);
        if (v == null || v.isNull() || v.asText().isBlank()) return dflt;
        long n;
        try {
            n = v.isNumber() ? v.asLong() : Long.parseLong(v.asText().trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(f + " must be a whole number");
        }
        if (n < min || n > max) throw ApiException.badRequest(f + " must be between " + min + " and " + max);
        return n;
    }
}
