package com.cassandrastudio.engine.cql;

import com.cassandrastudio.engine.schema.DdlBuilder;
import com.cassandrastudio.engine.util.ApiException;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import com.datastax.oss.driver.api.core.type.codec.registry.CodecRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a grid edit into CQL (CQL-9). Values arrive in the grid's display
 * form and are converted with the column's codec, so a bad value is rejected
 * here, before anything reaches the cluster. The full primary key is required.
 */
public final class RowEditService {
    private final SessionManager sessions;

    public RowEditService(SessionManager sessions) {
        this.sessions = sessions;
    }

    public enum Op { INSERT, UPDATE, DELETE }

    /** {@code key}: primary key column -> display value. {@code values}: other columns -> display value (null = set null). */
    public record RowEdit(String keyspace, String table, Op op, Map<String, String> key, Map<String, String> values) {}

    public String toCql(String connectionId, RowEdit e) {
        CqlSession s = sessions.session(connectionId);
        CodecRegistry registry = s.getContext().getCodecRegistry();
        KeyspaceMetadata ks = s.getMetadata().getKeyspace(CqlIdentifier.fromInternal(e.keyspace()))
                .orElseThrow(() -> ApiException.notFound("Keyspace " + e.keyspace()));
        TableMetadata t = ks.getTable(CqlIdentifier.fromInternal(e.table()))
                .orElseThrow(() -> ApiException.notFound("Table " + e.table()));
        if (t.isVirtual()) throw ApiException.badRequest("Virtual tables cannot be edited");
        List<ColumnMetadata> pk = new ArrayList<>(t.getPrimaryKey());
        Map<String, String> key = e.key() == null ? Map.of() : e.key();
        Map<String, String> values = e.values() == null ? Map.of() : e.values();
        for (ColumnMetadata c : pk) {
            if (key.get(c.getName().asInternal()) == null) {
                throw ApiException.badRequest("The full primary key is required; missing " + c.getName().asInternal());
            }
        }
        Map<String, String> keyLiterals = new LinkedHashMap<>();
        for (ColumnMetadata c : pk) {
            keyLiterals.put(DdlBuilder.id(c.getName().asInternal()), literal(t, c.getName().asInternal(), key.get(c.getName().asInternal()), registry));
        }
        Map<String, String> valueLiterals = new LinkedHashMap<>();
        values.forEach((col, v) -> {
            if (t.getPrimaryKey().stream().anyMatch(c -> c.getName().asInternal().equals(col))) {
                throw ApiException.badRequest("Primary key column " + col + " cannot be changed; insert a new row instead");
            }
            valueLiterals.put(DdlBuilder.id(col), literal(t, col, v, registry));
        });
        String table = DdlBuilder.qualified(e.keyspace(), e.table());
        String where = String.join(" AND ", keyLiterals.entrySet().stream().map(x -> x.getKey() + " = " + x.getValue()).toList());
        return switch (e.op()) {
            case INSERT -> {
                Map<String, String> all = new LinkedHashMap<>(keyLiterals);
                all.putAll(valueLiterals);
                yield "INSERT INTO " + table + " (" + String.join(", ", all.keySet()) + ") VALUES ("
                        + String.join(", ", all.values()) + ");";
            }
            case UPDATE -> {
                if (valueLiterals.isEmpty()) throw ApiException.badRequest("Nothing to update");
                yield "UPDATE " + table + " SET " + String.join(", ", valueLiterals.entrySet().stream()
                        .map(x -> x.getKey() + " = " + x.getValue()).toList()) + " WHERE " + where + ";";
            }
            case DELETE -> "DELETE FROM " + table + " WHERE " + where + ";";
        };
    }

    private static String literal(TableMetadata t, String column, String display, CodecRegistry registry) {
        ColumnMetadata c = t.getColumn(CqlIdentifier.fromInternal(column))
                .orElseThrow(() -> ApiException.badRequest("Unknown column " + column));
        try {
            return CellCodec.toLiteral(c.getType(), display, registry);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest(ex.getMessage());
        }
    }
}
