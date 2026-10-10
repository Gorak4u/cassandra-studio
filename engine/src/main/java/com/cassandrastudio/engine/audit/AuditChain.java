package com.cassandrastudio.engine.audit;

import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Tamper-evident audit log (SRV-6): every entry gets a link in {@code audit_chain} holding the previous
 * link's hash and SHA-256(previous hash, entry). Changing, deleting or slipping in an entry breaks the
 * chain from that point on; {@link #verify} finds the first broken link. Removing the newest entries
 * together with their links cannot be seen locally, which is what the SIEM export (an outside copy, with
 * each entry's hash) is for.
 *
 * <p>Entries written before the chain existed are linked once, on first start after the upgrade.
 */
public final class AuditChain {
    /** The "previous hash" of the first link. */
    public static final String GENESIS = "0".repeat(64);
    /** Serialises appends across Studio Server instances on one PostgreSQL store. */
    static final long LOCK = 0x5354_5544_494f_0002L;
    private static final int PAGE = 2_000;

    private AuditChain() {}

    /** The result of a verification; {@code brokenAt} is the id of the first entry whose link does not hold. */
    public record Verification(boolean ok, long checked, Long firstId, Long lastId, String lastHash, Long brokenAt,
                               String reason) {}

    /** SHA-256 over the previous hash and the entry's fields, as lowercase hex. */
    public static String hash(String prevHash, AuditLog.Entry e) {
        String canonical = Json.write(Arrays.asList(e.id(), e.at(), e.actor(), e.connectionId(), e.connectionName(),
                e.environment(), e.node(), e.category(), e.action(), e.detail(), e.outcome(), e.error()));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(prevHash.getBytes(StandardCharsets.US_ASCII));
            md.update((byte) '\n');
            md.update(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e1) {
            throw new IllegalStateException(e1);
        }
    }

    /**
     * Inserts {@code e} (its id is ignored) and its link in one transaction; returns the entry with its new
     * id and its hash.
     */
    static Linked append(Database db, AuditLog.Entry e) {
        Linked[] out = new Linked[1];
        db.transaction(() -> {
            db.lockInTransaction(LOCK);
            List<Map<String, Object>> ins = db.query("""
                    INSERT INTO audit_log(at, actor, connection_id, connection_name, environment, node, category, action,
                                          detail, outcome, error) VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING id""",
                    e.at(), e.actor(), e.connectionId(), e.connectionName(), e.environment(), e.node(), e.category(),
                    e.action(), e.detail(), e.outcome(), e.error());
            long id = ((Number) ins.get(0).get("id")).longValue();
            AuditLog.Entry saved = new AuditLog.Entry(id, e.at(), e.actor(), e.connectionId(), e.connectionName(),
                    e.environment(), e.node(), e.category(), e.action(), e.detail(), e.outcome(), e.error());
            String prev = head(db);
            String h = hash(prev, saved);
            db.update("INSERT INTO audit_chain(id, prev_hash, hash) VALUES (?,?,?)", id, prev, h);
            out[0] = new Linked(saved, h);
        });
        return out[0];
    }

    /** An entry as written, with its chain hash. */
    public record Linked(AuditLog.Entry entry, String hash) {}

    private static String head(Database db) {
        List<Map<String, Object>> last = db.query("SELECT hash FROM audit_chain ORDER BY id DESC LIMIT 1");
        return last.isEmpty() ? GENESIS : (String) last.get(0).get("hash");
    }

    /**
     * Links the entries written before the chain existed (first start after the upgrade). Does nothing
     * once a chain exists: later unlinked entries are tampering, reported by {@link #verify}.
     */
    public static void initialise(Database db) {
        if (!db.query("SELECT id FROM audit_chain LIMIT 1").isEmpty()) return;
        if (db.query("SELECT id FROM audit_log LIMIT 1").isEmpty()) return;
        db.transaction(() -> {
            db.lockInTransaction(LOCK);
            if (!db.query("SELECT id FROM audit_chain LIMIT 1").isEmpty()) return; // another instance did it
            String prev = GENESIS;
            long after = Long.MIN_VALUE;
            while (true) {
                List<Map<String, Object>> rows = db.query("SELECT * FROM audit_log WHERE id > ? ORDER BY id LIMIT ?", after, PAGE);
                if (rows.isEmpty()) break;
                for (Map<String, Object> r : rows) {
                    AuditLog.Entry e = AuditLog.fromRow(r);
                    String h = hash(prev, e);
                    db.update("INSERT INTO audit_chain(id, prev_hash, hash) VALUES (?,?,?)", e.id(), prev, h);
                    prev = h;
                    after = e.id();
                }
            }
        });
    }

    /** Walks the whole log in id order and reports the first entry whose link does not hold. */
    public static Verification verify(Database db) {
        String prev = GENESIS;
        long checked = 0;
        Long first = null;
        Long last = null;
        long after = Long.MIN_VALUE;
        while (true) {
            List<Map<String, Object>> rows = db.query("""
                    SELECT a.*, c.id AS c_id, c.prev_hash AS c_prev, c.hash AS c_hash
                    FROM audit_log a LEFT JOIN audit_chain c ON c.id = a.id
                    WHERE a.id > ? ORDER BY a.id LIMIT ?""", after, PAGE);
            if (rows.isEmpty()) break;
            for (Map<String, Object> r : rows) {
                AuditLog.Entry e = AuditLog.fromRow(r);
                after = e.id();
                if (r.get("c_id") == null) {
                    return broken(checked, first, last, prev, e.id(), "entry has no chain link: it was added outside Studio");
                }
                if (first == null) first = e.id();
                if (!prev.equals(r.get("c_prev"))) {
                    return broken(checked, first, last, prev, e.id(),
                            "link does not follow the entry before it: an earlier entry was deleted or its link changed");
                }
                String h = hash(prev, e);
                if (!h.equals(r.get("c_hash"))) {
                    return broken(checked, first, last, prev, e.id(), "entry was changed after it was written");
                }
                prev = h;
                last = e.id();
                checked++;
            }
        }
        List<Map<String, Object>> orphan = db.query("""
                SELECT c.id AS id FROM audit_chain c LEFT JOIN audit_log a ON a.id = c.id
                WHERE a.id IS NULL ORDER BY c.id LIMIT 1""");
        if (!orphan.isEmpty()) {
            return broken(checked, first, last, prev, ((Number) orphan.get(0).get("id")).longValue(),
                    "entry was deleted (its link is still there)");
        }
        return new Verification(true, checked, first, last, first == null ? null : prev, null, null);
    }

    private static Verification broken(long checked, Long first, Long last, String prev, long at, String why) {
        return new Verification(false, checked, first, last, first == null ? null : prev, at, why);
    }
}
