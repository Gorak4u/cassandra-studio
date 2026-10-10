package com.cassandrastudio.engine.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.model.ConnectionConfig.Environment;
import com.cassandrastudio.engine.model.Folder;
import com.cassandrastudio.engine.secrets.SecretStores;
import com.cassandrastudio.engine.store.SettingsBackup.Conflict;
import com.cassandrastudio.engine.util.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SettingsBackupTest {
    private Database srcDb;
    private ConnectionRepository src;
    private SettingsBackup srcBackup;
    private Database dstDb;
    private ConnectionRepository dst;
    private SettingsBackup dstBackup;
    private String connId;

    static ConnectionConfig conn(String name, String folder) {
        return new ConnectionConfig(null, folder, name, Environment.PROD, null, false, List.of("10.0.0.1"), "dc1", "app",
                null, null, null, null, null, null, null, List.of("core"), null, null);
    }

    @BeforeEach
    void setUp() {
        srcDb = Database.inMemory();
        src = new ConnectionRepository(srcDb, SecretStores.inMemory());
        srcBackup = new SettingsBackup(srcDb, src);
        dstDb = Database.inMemory();
        dst = new ConnectionRepository(dstDb, SecretStores.inMemory());
        dstBackup = new SettingsBackup(dstDb, dst);

        Folder acme = src.createFolder(null, "acme");
        Folder prod = src.createFolder(acme.id(), "prod");
        connId = src.save(conn("core", prod.id()), Map.of("password", "s3cret", "jmxPassword", "jmx-pw-9")).id();
        new ScriptRepository(srcDb).save(null, "ops", "sizes", "SELECT * FROM system.size_estimates;");
        srcDb.update("INSERT INTO settings(key, value) VALUES (?, ?)", "monitoring.thresholds/" + connId, "{\"heapPct\":80}");
        srcDb.update("INSERT INTO settings(key, value) VALUES ('ui.state', '{}')");
    }

    @AfterEach
    void tearDown() {
        srcDb.close();
        dstDb.close();
    }

    /** Round trip through JSON, as the file on disk would be. */
    private static SettingsBackup.File roundTrip(SettingsBackup.File f) {
        return Json.read(Json.write(f), SettingsBackup.File.class);
    }

    @Test
    void exportWithoutPassphraseHasNoSecrets() {
        SettingsBackup.File f = srcBackup.export(null);
        String json = Json.write(f);
        assertThat(json).doesNotContain("s3cret").doesNotContain("jmx-pw-9");
        assertThat(f.secrets()).isNull();
        assertThat(f.folders()).hasSize(2);
        assertThat(f.connections()).singleElement().satisfies(c -> assertThat(c.secretsSet()).isNull());
        assertThat(f.scripts()).singleElement().satisfies(s -> assertThat(s.content()).contains("size_estimates"));
        assertThat(f.settings()).containsOnlyKeys("monitoring.thresholds/" + connId); // UI layout stays local
    }

    @Test
    void restoreIntoEmptyStudioKeepsIdsAndSettings() {
        SettingsBackup.Result r = dstBackup.restore(roundTrip(srcBackup.export(null)), Conflict.SKIP, null);
        assertThat(r.folders()).isEqualTo(2);
        assertThat(r.connections()).isEqualTo(1);
        assertThat(r.scripts()).isEqualTo(1);
        assertThat(r.settings()).isEqualTo(1);
        assertThat(r.secrets()).isZero();
        ConnectionConfig c = dst.get(connId);
        assertThat(c.name()).isEqualTo("core");
        assertThat(dst.folders()).extracting(Folder::name).containsExactlyInAnyOrder("acme", "prod");
        assertThat(c.folderId()).isEqualTo(dst.folders().stream().filter(f -> f.name().equals("prod")).findFirst().orElseThrow().id());
        assertThat(dst.secret(connId, "password")).isEmpty();
        assertThat(dstDb.query("SELECT value FROM settings WHERE key=?", "monitoring.thresholds/" + connId)).hasSize(1);
    }

    @Test
    void secretsNeedTheRightPassphrase() {
        SettingsBackup.File f = roundTrip(srcBackup.export("correct horse"));
        assertThat(Json.write(f)).doesNotContain("s3cret");
        assertThat(f.secrets().iterations()).isEqualTo(SettingsBackup.KDF_ITERATIONS);
        assertThat(dstBackup.preview(f).hasSecrets()).isTrue();

        assertThatThrownBy(() -> dstBackup.restore(f, Conflict.SKIP, "wrong passphrase")).hasMessageContaining("Wrong passphrase");
        assertThat(dst.list()).isEmpty(); // nothing restored

        SettingsBackup.Result r = dstBackup.restore(f, Conflict.SKIP, "correct horse");
        assertThat(r.secrets()).isEqualTo(2);
        assertThat(dst.secret(connId, "password")).contains("s3cret");
        assertThat(dst.secret(connId, "jmxPassword")).contains("jmx-pw-9");
        assertThat(dst.get(connId).secretsSet()).containsEntry("password", true);
    }

    @Test
    void secretsInFileButNoPassphraseRestoresTheRestWithANote() {
        SettingsBackup.Result r = dstBackup.restore(roundTrip(srcBackup.export("correct horse")), Conflict.SKIP, null);
        assertThat(r.connections()).isEqualTo(1);
        assertThat(r.notes()).singleElement().asString().contains("not restored");
        assertThat(dst.secret(connId, "password")).isEmpty();
    }

    @Test
    void shortPassphraseIsRefused() {
        assertThatThrownBy(() -> srcBackup.export("short")).hasMessageContaining("at least 8");
    }

    @Test
    void conflictsSkipReplaceOrKeepBoth() {
        SettingsBackup.File f = roundTrip(srcBackup.export(null));
        dstBackup.restore(f, Conflict.SKIP, null);
        dst.save(dst.get(connId).withName("core-renamed"), null);

        SettingsBackup.Preview p = dstBackup.preview(f);
        assertThat(p.connections().conflicting()).isEqualTo(1);
        assertThat(p.connections().added()).isZero();
        assertThat(p.folders().conflicting()).isEqualTo(2);

        SettingsBackup.Result skip = dstBackup.restore(f, Conflict.SKIP, null);
        assertThat(skip.connections()).isZero();
        assertThat(skip.skipped()).isEqualTo(2 + 1 + 1 + 1);
        assertThat(dst.get(connId).name()).isEqualTo("core-renamed");

        dstBackup.restore(f, Conflict.REPLACE, null);
        assertThat(dst.get(connId).name()).isEqualTo("core");
        assertThat(dst.list()).hasSize(1);

        SettingsBackup.Result both = dstBackup.restore(f, Conflict.KEEP_BOTH, null);
        assertThat(both.connections()).isEqualTo(1);
        assertThat(dst.list()).extracting(ConnectionConfig::name).containsExactlyInAnyOrder("core", "core (restored)");
        ConnectionConfig copy = dst.list().stream().filter(c -> c.name().endsWith("(restored)")).findFirst().orElseThrow();
        // the copy's per-connection settings follow its new id; its folder is the restored copy of "prod"
        assertThat(dstDb.query("SELECT value FROM settings WHERE key=?", "monitoring.thresholds/" + copy.id())).hasSize(1);
        assertThat(dst.folders().stream().filter(x -> x.id().equals(copy.folderId())).findFirst().orElseThrow().name())
                .isEqualTo("prod (restored)");
        assertThat(new ScriptRepository(dstDb).list()).hasSize(2);
    }

    @Test
    void refusesOtherFilesAndNewerVersions() {
        SettingsBackup.File f = srcBackup.export(null);
        SettingsBackup.File connectionsExport = new SettingsBackup.File("cassandra-studio-connections", 1, null, null,
                List.of(), List.of(), List.of(), Map.of(), null);
        assertThatThrownBy(() -> dstBackup.restore(connectionsExport, Conflict.SKIP, null))
                .hasMessageContaining("Not a Cassandra Studio settings backup");
        SettingsBackup.File newer = new SettingsBackup.File(f.format(), SettingsBackup.FORMAT_VERSION + 1, null, null,
                f.folders(), f.connections(), f.scripts(), f.settings(), null);
        assertThatThrownBy(() -> dstBackup.restore(newer, Conflict.SKIP, null)).hasMessageContaining("newer");
    }
}
