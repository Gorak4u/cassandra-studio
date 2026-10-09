package com.cassandrastudio.engine.bulk;

import com.datastax.oss.driver.api.core.data.CqlDuration;
import com.datastax.oss.driver.api.core.data.TupleValue;
import com.datastax.oss.driver.api.core.data.UdtValue;
import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.api.core.type.ListType;
import com.datastax.oss.driver.api.core.type.MapType;
import com.datastax.oss.driver.api.core.type.SetType;
import com.datastax.oss.driver.api.core.type.TupleType;
import com.datastax.oss.driver.api.core.type.UserDefinedType;
import com.datastax.oss.driver.api.core.type.codec.TypeCodec;
import com.datastax.oss.driver.api.core.type.codec.registry.CodecRegistry;
import com.datastax.oss.protocol.internal.util.Bytes;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Converts CQL values to and from the text of a CSV field or a JSON value (BLK-1, BLK-2).
 *
 * <p>Unload writes what load reads back: scalars in their natural text form (text raw,
 * timestamps ISO-8601 or a chosen pattern, blobs 0x-hex or base64, uuids, inet addresses),
 * collections, UDTs, tuples and vectors as CQL literals via the driver's codecs. In JSON lines
 * numbers and booleans are JSON numbers and booleans, collections arrays, maps and UDTs objects.
 * Load also accepts JSON for collections inside a CSV field, epoch millis for timestamps,
 * cqlsh-style timestamps ({@code 2024-01-02 03:04:05.000+0000}) and yes/no booleans.
 */
public final class ValueConverter {
    public enum BlobFormat { HEX, BASE64 }

    /** Reads JSON numbers exactly (decimal, varint, bigint). */
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Pattern INTEGER = Pattern.compile("-?\\d{1,19}");
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.%]+");
    private static final Pattern HEX = Pattern.compile("(?:[0-9A-Fa-f]{2})*");
    /** ISO date, optional 'T' or space and time, optional offset as +HH:MM, +HHMM or Z. */
    private static final DateTimeFormatter FLEXIBLE_TIMESTAMP = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .optionalStart().appendLiteral('T').append(DateTimeFormatter.ISO_LOCAL_TIME).optionalEnd()
            .optionalStart().appendOffset("+HH:MM", "Z").optionalEnd()
            .optionalStart().appendOffset("+HHMM", "Z").optionalEnd()
            .optionalStart().appendOffset("+HH", "Z").optionalEnd()
            .toFormatter(Locale.ROOT);

    private final CodecRegistry registry;
    private final DateTimeFormatter timestampFormat;
    private final DateTimeFormatter dateFormat;
    private final ZoneId zone;
    private final BlobFormat blobFormat;

    /**
     * @param timestampPattern a {@link DateTimeFormatter} pattern, or null for ISO-8601 instants
     * @param datePattern      a pattern for {@code date} columns, or null for ISO dates
     * @param zone             the zone for patterns without an offset (UTC when null)
     */
    public ValueConverter(CodecRegistry registry, String timestampPattern, String datePattern, ZoneId zone,
                          BlobFormat blobFormat) {
        this.registry = registry;
        this.zone = zone == null ? ZoneOffset.UTC : zone;
        this.timestampFormat = timestampPattern == null ? null : DateTimeFormatter.ofPattern(timestampPattern, Locale.ROOT).withZone(this.zone);
        this.dateFormat = datePattern == null ? null : DateTimeFormatter.ofPattern(datePattern, Locale.ROOT);
        this.blobFormat = blobFormat == null ? BlobFormat.HEX : blobFormat;
    }

    /** Checks a pattern the user typed; null when it is fine, else why not. */
    public static String checkPattern(String pattern) {
        if (pattern == null) return null;
        try {
            DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
            return null;
        } catch (IllegalArgumentException e) {
            return "'" + pattern + "' is not a valid date/time pattern: " + e.getMessage();
        }
    }

    public CodecRegistry registry() {
        return registry;
    }

    // ---- value -> text ----------------------------------------------------------------------

    /** The text form of a value (null stays null: the writer decides how nulls look). */
    public String format(DataType type, Object value) {
        if (value == null) return null;
        if (type.equals(DataTypes.TEXT) || type.equals(DataTypes.ASCII)) return value.toString();
        if (type.equals(DataTypes.TIMESTAMP)) {
            Instant i = (Instant) value;
            return timestampFormat == null ? DateTimeFormatter.ISO_INSTANT.format(i) : timestampFormat.format(i);
        }
        if (type.equals(DataTypes.DATE)) {
            LocalDate d = (LocalDate) value;
            return dateFormat == null ? d.toString() : dateFormat.format(d);
        }
        if (type.equals(DataTypes.TIME)) return DateTimeFormatter.ISO_LOCAL_TIME.format((LocalTime) value);
        if (type.equals(DataTypes.BLOB)) {
            ByteBuffer bb = (ByteBuffer) value;
            if (blobFormat == BlobFormat.BASE64) {
                ByteBuffer d = bb.duplicate();
                byte[] b = new byte[d.remaining()];
                d.get(b);
                return Base64.getEncoder().encodeToString(b);
            }
            return Bytes.toHexString(bb);
        }
        if (type.equals(DataTypes.INET)) return ((InetAddress) value).getHostAddress();
        if (value instanceof BigDecimal bd) return bd.toString();
        if (value instanceof Number || value instanceof Boolean || value instanceof UUID || value instanceof CqlDuration) {
            return value.toString();
        }
        // Collections, UDTs, tuples, vectors, custom types: the CQL literal.
        return codec(type).format(value);
    }

    /** The JSON form of a value for JSON lines. */
    public JsonNode toJson(DataType type, Object value) {
        if (value == null) return NODES.nullNode();
        if (type instanceof ListType || type instanceof SetType) {
            DataType et = type instanceof ListType l ? l.getElementType() : ((SetType) type).getElementType();
            ArrayNode a = NODES.arrayNode();
            for (Object o : (Collection<?>) value) a.add(toJson(et, o));
            return a;
        }
        if (type instanceof MapType m) {
            ObjectNode o = NODES.objectNode();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                o.set(format(m.getKeyType(), e.getKey()), toJson(m.getValueType(), e.getValue()));
            }
            return o;
        }
        if (type instanceof UserDefinedType u) {
            UdtValue v = (UdtValue) value;
            ObjectNode o = NODES.objectNode();
            for (int i = 0; i < u.getFieldTypes().size(); i++) {
                DataType ft = u.getFieldTypes().get(i);
                o.set(u.getFieldNames().get(i).asInternal(), toJson(ft, v.get(i, codec(ft))));
            }
            return o;
        }
        if (type instanceof TupleType t) {
            TupleValue v = (TupleValue) value;
            ArrayNode a = NODES.arrayNode();
            for (int i = 0; i < t.getComponentTypes().size(); i++) {
                DataType ct = t.getComponentTypes().get(i);
                a.add(toJson(ct, v.get(i, codec(ct))));
            }
            return a;
        }
        if (value instanceof Boolean b) return NODES.booleanNode(b);
        if (value instanceof Integer n) return NODES.numberNode(n);
        if (value instanceof Long n) return NODES.numberNode(n);
        if (value instanceof Short n) return NODES.numberNode(n);
        if (value instanceof Byte n) return NODES.numberNode(n);
        if (value instanceof BigInteger n) return NODES.numberNode(n);
        if (value instanceof BigDecimal n) return com.fasterxml.jackson.databind.node.DecimalNode.valueOf(n);
        if (value instanceof Float f) return f.isNaN() || f.isInfinite() ? NODES.textNode(f.toString()) : NODES.numberNode(f);
        if (value instanceof Double d) return d.isNaN() || d.isInfinite() ? NODES.textNode(d.toString()) : NODES.numberNode(d);
        return NODES.textNode(format(type, value));
    }

    // ---- text -> value ----------------------------------------------------------------------

    /** Parses a field's text into the column's Java value; throws IllegalArgumentException with a clear reason. */
    public Object parse(DataType type, String text) {
        try {
            return parseUnchecked(type, text);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalid(type, text, e.getMessage());
        }
    }

    private Object parseUnchecked(DataType type, String raw) {
        if (type.equals(DataTypes.TEXT)) return raw;
        if (type.equals(DataTypes.ASCII)) {
            for (int i = 0; i < raw.length(); i++) {
                if (raw.charAt(i) > 127) throw invalid(type, raw, "it contains non-ASCII characters");
            }
            return raw;
        }
        String s = raw.trim();
        if (type.equals(DataTypes.INT)) return number(type, s).intValueExact();
        if (type.equals(DataTypes.BIGINT)) return number(type, s).longValueExact();
        if (type.equals(DataTypes.SMALLINT)) return number(type, s).shortValueExact();
        if (type.equals(DataTypes.TINYINT)) return number(type, s).byteValueExact();
        if (type.equals(DataTypes.VARINT)) return number(type, s).toBigIntegerExact();
        if (type.equals(DataTypes.DECIMAL)) return number(type, s);
        if (type.equals(DataTypes.FLOAT)) return Float.parseFloat(s);
        if (type.equals(DataTypes.DOUBLE)) return Double.parseDouble(s);
        if (type.equals(DataTypes.COUNTER)) {
            throw new IllegalArgumentException("counter columns cannot be loaded (counters can only be incremented)");
        }
        if (type.equals(DataTypes.BOOLEAN)) {
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "true", "yes", "y", "1" -> Boolean.TRUE;
                case "false", "no", "n", "0" -> Boolean.FALSE;
                default -> throw invalid(type, raw, "use true/false, yes/no or 1/0");
            };
        }
        if (type.equals(DataTypes.UUID)) return UUID.fromString(s);
        if (type.equals(DataTypes.TIMEUUID)) {
            UUID u = UUID.fromString(s);
            if (u.version() != 1) throw invalid(type, raw, "it is a version " + u.version() + " UUID, not time-based (version 1)");
            return u;
        }
        if (type.equals(DataTypes.TIMESTAMP)) return parseTimestamp(s);
        if (type.equals(DataTypes.DATE)) {
            if (dateFormat != null) return LocalDate.parse(s, dateFormat);
            return LocalDate.parse(s);
        }
        if (type.equals(DataTypes.TIME)) {
            if (INTEGER.matcher(s).matches()) return LocalTime.ofNanoOfDay(Long.parseLong(s));
            return LocalTime.parse(s);
        }
        if (type.equals(DataTypes.INET)) {
            if (!IP_LITERAL.matcher(s).matches()) throw invalid(type, raw, "only IP addresses are accepted, not host names");
            try {
                return InetAddress.getByName(s);
            } catch (java.net.UnknownHostException e) {
                throw invalid(type, raw, "not an IP address");
            }
        }
        if (type.equals(DataTypes.BLOB)) return parseBlob(s);
        if (type.equals(DataTypes.DURATION)) return CqlDuration.from(s);
        // Collections, UDTs, tuples, vectors: a CQL literal, or JSON.
        TypeCodec<Object> c = codec(type);
        try {
            return c.parse(s);
        } catch (RuntimeException literalError) {
            if (s.startsWith("[") || s.startsWith("{")) {
                JsonNode n;
                try {
                    n = JSON.readTree(s);
                } catch (Exception jsonError) {
                    throw invalid(type, raw, "neither a CQL literal nor JSON");
                }
                return fromJson(type, n);
            }
            throw invalid(type, raw, literalError.getMessage());
        }
    }

    /** Converts a JSON value (JSON lines, or JSON inside a CSV field) to the column's Java value. */
    public Object fromJson(DataType type, JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        // Text is the field's text form (a CQL literal or JSON for collections): same rules as CSV.
        if (n.isTextual()) return parse(type, n.asText());
        if (type instanceof ListType l) {
            List<Object> out = new ArrayList<>();
            for (JsonNode e : array(type, n)) out.add(element(l.getElementType(), e));
            return out;
        }
        if (type instanceof SetType st) {
            Set<Object> out = new LinkedHashSet<>();
            for (JsonNode e : array(type, n)) out.add(element(st.getElementType(), e));
            return out;
        }
        if (type instanceof MapType m) {
            Map<Object, Object> out = new LinkedHashMap<>();
            if (n.isObject()) {
                for (Map.Entry<String, JsonNode> e : n.properties()) {
                    out.put(parse(m.getKeyType(), e.getKey()), element(m.getValueType(), e.getValue()));
                }
            } else if (n.isArray()) {
                for (JsonNode pair : n) {
                    if (!pair.isArray() || pair.size() != 2) throw invalid(type, n.toString(), "map entries must be [key, value] pairs");
                    out.put(element(m.getKeyType(), pair.get(0)), element(m.getValueType(), pair.get(1)));
                }
            } else {
                throw invalid(type, n.toString(), "expected a JSON object");
            }
            return out;
        }
        if (type instanceof UserDefinedType u) {
            if (!n.isObject()) throw invalid(type, n.toString(), "expected a JSON object");
            UdtValue v = u.newValue();
            for (Map.Entry<String, JsonNode> e : n.properties()) {
                int i = u.firstIndexOf(e.getKey());
                if (i < 0) throw invalid(type, n.toString(), "field '" + e.getKey() + "' is not part of the type");
                DataType ft = u.getFieldTypes().get(i);
                v = v.set(i, fromJson(ft, e.getValue()), codec(ft));
            }
            return v;
        }
        if (type instanceof TupleType t) {
            if (!n.isArray() || n.size() != t.getComponentTypes().size()) {
                throw invalid(type, n.toString(), "expected a JSON array of " + t.getComponentTypes().size() + " values");
            }
            TupleValue v = t.newValue();
            for (int i = 0; i < n.size(); i++) {
                DataType ct = t.getComponentTypes().get(i);
                v = v.set(i, fromJson(ct, n.get(i)), codec(ct));
            }
            return v;
        }
        if (n.isBoolean() && type.equals(DataTypes.BOOLEAN)) return n.booleanValue();
        if (n.isNumber()) {
            if (type.equals(DataTypes.FLOAT)) return n.floatValue();
            if (type.equals(DataTypes.DOUBLE)) return n.doubleValue();
            if (type.equals(DataTypes.TIMESTAMP) && n.canConvertToLong()) return Instant.ofEpochMilli(n.longValue());
            return parse(type, n.decimalValue().toPlainString());
        }
        if (n.isContainerNode()) return parse(type, n.toString());
        return parse(type, n.asText());
    }

    private Object element(DataType type, JsonNode e) {
        Object v = fromJson(type, e);
        if (v == null) throw invalid(type, "null", "collections cannot contain null");
        return v;
    }

    private JsonNode array(DataType type, JsonNode n) {
        if (!n.isArray()) throw invalid(type, n.toString(), "expected a JSON array");
        return n;
    }

    private BigDecimal number(DataType type, String s) {
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw invalid(type, s, "not a number");
        }
    }

    private Instant parseTimestamp(String s) {
        if (INTEGER.matcher(s).matches()) return Instant.ofEpochMilli(Long.parseLong(s));
        if (timestampFormat != null) {
            try {
                return toInstant(timestampFormat.parse(s));
            } catch (DateTimeParseException e) {
                // fall through: ISO input still works with a custom pattern set
            }
        }
        String norm = s.length() > 10 && s.charAt(10) == ' ' ? s.substring(0, 10) + 'T' + s.substring(11) : s;
        try {
            return toInstant(FLEXIBLE_TIMESTAMP.parse(norm));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + s + "' is not a valid timestamp"
                    + (timestampFormat == null ? " (expected ISO-8601, e.g. 2024-05-01T10:00:00Z, or epoch milliseconds)"
                    : " for the pattern set"));
        }
    }

    private Instant toInstant(TemporalAccessor t) {
        ZoneId z = t.isSupported(ChronoField.OFFSET_SECONDS) ? ZoneOffset.ofTotalSeconds(t.get(ChronoField.OFFSET_SECONDS)) : zone;
        if (t.isSupported(ChronoField.INSTANT_SECONDS)) return Instant.from(t);
        LocalDate d = LocalDate.from(t);
        LocalTime time = t.isSupported(ChronoField.HOUR_OF_DAY) ? LocalTime.from(t) : LocalTime.MIDNIGHT;
        return LocalDateTime.of(d, time).atZone(z).toInstant();
    }

    private ByteBuffer parseBlob(String s) {
        if (s.startsWith("0x") || s.startsWith("0X")) {
            if (!HEX.matcher(s.substring(2)).matches()) throw invalid(DataTypes.BLOB, s, "not valid hex");
            return Bytes.fromHexString("0x" + s.substring(2));
        }
        if (blobFormat == BlobFormat.HEX && HEX.matcher(s).matches()) return Bytes.fromHexString("0x" + s);
        try {
            return ByteBuffer.wrap(Base64.getDecoder().decode(s));
        } catch (IllegalArgumentException e) {
            throw invalid(DataTypes.BLOB, s, "expected 0x-prefixed hex or base64");
        }
    }

    private TypeCodec<Object> codec(DataType type) {
        return registry.codecFor(type);
    }

    static IllegalArgumentException invalid(DataType type, String text, String why) {
        String shown = text == null ? "null" : text.length() > 80 ? text.substring(0, 80) + "…" : text;
        return new IllegalArgumentException("'" + shown + "' is not a valid " + type.asCql(false, true)
                + (why == null || why.isBlank() ? "" : ": " + why));
    }
}
