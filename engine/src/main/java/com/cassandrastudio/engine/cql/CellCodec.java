package com.cassandrastudio.engine.cql;

import com.datastax.oss.driver.api.core.type.DataType;
import com.datastax.oss.driver.api.core.type.DataTypes;
import com.datastax.oss.driver.api.core.type.codec.TypeCodec;
import com.datastax.oss.driver.api.core.type.codec.registry.CodecRegistry;
import java.util.Set;

/**
 * Converts between driver values, what the grid shows, and CQL literals
 * (CQL-5, CQL-9).
 *
 * <p>The display form IS the CQL literal (codec.format), except that text,
 * timestamps, dates, times, inet and uuids are shown without their quotes.
 * {@link #toLiteral} reverses that, so a value edited in the grid becomes a
 * literal without any guessing. Numbers that JavaScript cannot hold exactly
 * (bigint, varint, decimal, counter) are sent as strings.
 */
public final class CellCodec {
    private static final Set<DataType> TEXT = Set.of(DataTypes.TEXT, DataTypes.ASCII);
    private static final Set<DataType> UNQUOTED_DISPLAY = Set.of(DataTypes.TEXT, DataTypes.ASCII, DataTypes.TIMESTAMP,
            DataTypes.DATE, DataTypes.TIME, DataTypes.INET);
    private static final Set<DataType> JSON_NUMBER = Set.of(DataTypes.INT, DataTypes.SMALLINT, DataTypes.TINYINT,
            DataTypes.FLOAT, DataTypes.DOUBLE);
    static final int MAX_BLOB_DISPLAY_BYTES = 64 * 1024;

    private CellCodec() {}

    public static Object toDisplay(DataType type, Object value, CodecRegistry registry) {
        if (value == null) return null;
        if (JSON_NUMBER.contains(type)) {
            if (value instanceof Float f && (f.isNaN() || f.isInfinite())) return f.toString();
            if (value instanceof Double d && (d.isNaN() || d.isInfinite())) return d.toString();
            return value;
        }
        if (type.equals(DataTypes.BOOLEAN)) return value;
        if (TEXT.contains(type)) return value.toString();
        if (type.equals(DataTypes.BLOB) && value instanceof java.nio.ByteBuffer bb && bb.remaining() > MAX_BLOB_DISPLAY_BYTES) {
            java.nio.ByteBuffer head = bb.duplicate();
            head.limit(head.position() + MAX_BLOB_DISPLAY_BYTES);
            return format(type, head, registry) + "... (" + bb.remaining() + " bytes)";
        }
        String literal = format(type, value, registry);
        if (UNQUOTED_DISPLAY.contains(type) && literal.length() >= 2 && literal.startsWith("'") && literal.endsWith("'")) {
            return literal.substring(1, literal.length() - 1).replace("''", "'");
        }
        return literal;
    }

    /** The CQL literal for something the user typed into a grid cell. */
    public static String toLiteral(DataType type, String display, CodecRegistry registry) {
        if (display == null) return "null";
        String d = display;
        if (UNQUOTED_DISPLAY.contains(type) && !(d.startsWith("'") && d.endsWith("'") && d.length() >= 2)) {
            d = "'" + d.replace("'", "''") + "'";
        }
        // Parse and re-format: rejects values that are not valid for the column type.
        TypeCodec<Object> codec = registry.codecFor(type);
        Object parsed;
        try {
            parsed = codec.parse(d);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("'" + display + "' is not a valid " + type.asCql(false, true) + ": " + e.getMessage(), e);
        }
        return codec.format(parsed);
    }

    private static String format(DataType type, Object value, CodecRegistry registry) {
        TypeCodec<Object> codec = registry.codecFor(type);
        return codec.format(value);
    }
}
