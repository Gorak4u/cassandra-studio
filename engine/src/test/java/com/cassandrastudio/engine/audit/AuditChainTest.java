package com.cassandrastudio.engine.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.cassandrastudio.engine.store.Database;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AuditChainTest {
    private final Database db = Database.inMemory();
    private final AuditLog audit = new AuditLog(db, "tester");

    @AfterEach
    void close() {
        db.close();
    }

    private void write(int n) {
        for (int i = 0; i < n; i++) audit.record(null, null, "test", "action " + i, "detail " + i, AuditLog.Outcome.SUCCESS, null);
    }

    @Test
    void intactChainVerifies() {
        assertThat(AuditChain.verify(db).ok()).isTrue();
        write(5);
        AuditChain.Verification v = AuditChain.verify(db);
        assertThat(v.ok()).isTrue();
        assertThat(v.checked()).isEqualTo(5);
        assertThat(v.firstId()).isEqualTo(1);
        assertThat(v.lastId()).isEqualTo(5);
        assertThat(v.lastHash()).hasSize(64);
    }

    @Test
    void listenersGetEachEntryWithItsHash() {
        List<String> hashes = new ArrayList<>();
        audit.addListener((e, h) -> hashes.add(e.id() + ":" + h));
        audit.addListener((e, h) -> {
            throw new IllegalStateException("broken exporter");
        });
        write(2);
        assertThat(hashes).hasSize(2);
        assertThat(hashes.get(1)).startsWith("2:").endsWith(AuditChain.verify(db).lastHash());
    }

    @Test
    void changedEntryIsFound() {
        write(5);
        db.update("UPDATE audit_log SET detail = 'nothing happened' WHERE id = 3");
        AuditChain.Verification v = AuditChain.verify(db);
        assertThat(v.ok()).isFalse();
        assertThat(v.brokenAt()).isEqualTo(3);
        assertThat(v.reason()).contains("changed");
        assertThat(v.checked()).isEqualTo(2);
    }

    @Test
    void deletedEntryIsFound() {
        write(5);
        db.update("DELETE FROM audit_log WHERE id = 2");
        db.update("DELETE FROM audit_chain WHERE id = 2");
        AuditChain.Verification v = AuditChain.verify(db);
        assertThat(v.ok()).isFalse();
        assertThat(v.brokenAt()).isEqualTo(3);
        assertThat(v.reason()).contains("deleted");
    }

    @Test
    void deletedEntryWithLinkLeftIsFound() {
        write(3);
        db.update("DELETE FROM audit_log WHERE id = 3");
        AuditChain.Verification v = AuditChain.verify(db);
        assertThat(v.ok()).isFalse();
        assertThat(v.brokenAt()).isEqualTo(3);
    }

    @Test
    void entryAddedOutsideStudioIsFound() {
        write(2);
        db.update("INSERT INTO audit_log(at, actor, category, action, outcome) VALUES ('2026-01-01T00:00:00Z', 'mallory', 'x', 'y', 'SUCCESS')");
        write(1);
        AuditChain.Verification v = AuditChain.verify(db);
        assertThat(v.ok()).isFalse();
        assertThat(v.brokenAt()).isEqualTo(3);
        assertThat(v.reason()).contains("outside Studio");
    }

    @Test
    void entriesFromBeforeTheChainAreLinkedOnce() {
        db.update("DELETE FROM audit_chain");
        // as written by v1.0, before the chain existed
        for (int i = 0; i < 3; i++) {
            db.update("INSERT INTO audit_log(at, actor, category, action, outcome) VALUES ('2026-01-01T00:00:00Z', 'old', 'x', 'y', 'SUCCESS')");
        }
        new AuditLog(db, "tester");
        write(1);
        AuditChain.Verification v = AuditChain.verify(db);
        assertThat(v.ok()).isTrue();
        assertThat(v.checked()).isEqualTo(4);
    }

    @Test
    void hashIsStable() {
        AuditLog.Entry e = new AuditLog.Entry(1, "2026-01-01T00:00:00Z", "a", null, null, null, null, "c", "x", null, "SUCCESS", null);
        assertThat(AuditChain.hash(AuditChain.GENESIS, e)).isEqualTo(AuditChain.hash(AuditChain.GENESIS, e)).hasSize(64)
                .isNotEqualTo(AuditChain.hash("1".repeat(64), e));
    }
}
