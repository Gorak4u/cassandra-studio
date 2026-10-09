package com.cassandrastudio.engine.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.backup.BackupSettings.Privilege;
import com.cassandrastudio.engine.backup.BackupSettings.Provider;
import com.cassandrastudio.engine.util.ApiException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;
import org.junit.jupiter.api.Test;

/** Medusa text output, snapshot details from JMX (3.11 and 4.1+ shapes), settings validation. */
class ProviderFormatsTest {

    @Test
    void medusaListAndStatus() {
        String list = """
                nightly-20261008 (started: 2026-10-08 02:00:01, finished: 2026-10-08 02:04:47)
                studio-20261009-0300 (started: 2026-10-09 03:00:00, finished: Incomplete) [Incomplete!]

                Incomplete backups found. You can run "medusa get-last-complete-cluster-backup" to find the last complete backup
                """;
        List<MedusaFormat.Listed> l = MedusaFormat.parseList(list);
        assertThat(l).hasSize(2);
        assertThat(l.get(0).complete()).isTrue();
        assertThat(l.get(0).finishedMs()).isEqualTo(Instant.parse("2026-10-08T02:04:47Z").toEpochMilli());
        assertThat(l.get(1).complete()).isFalse();
        assertThat(l.get(1).finishedMs()).isNull();

        String status = """
                nightly-20261008
                - Started: 2026-10-08 02:00:01, Finished: 2026-10-08 02:04:47
                - 3 nodes completed, 0 nodes incomplete, 0 nodes missing
                - 1180 files, 12.50 MB
                """;
        MedusaFormat.Status st = MedusaFormat.parseStatus(status);
        assertThat(st.completed()).isEqualTo(3);
        assertThat(st.missing()).isZero();
        assertThat(st.files()).isEqualTo(1180);
        assertThat(st.bytes()).isEqualTo(Math.round(12.5 * 1024 * 1024));
        BackupEntry e = MedusaFormat.entry(l.get(0), st);
        assertThat(e.status()).isEqualTo(BackupEntry.COMPLETE);
        assertThat(e.statusDetail()).isEqualTo("3 nodes completed, 0 incomplete, 0 missing");

        BackupSettings s = new BackupSettings(Provider.MEDUSA, null, null, Privilege.SUDO, null, "/etc/medusa/medusa.ini", null);
        assertThat(MedusaFormat.backupNodeCommand(s, "studio-1", "differential"))
                .isEqualTo("sudo -n 'medusa' --config-file '/etc/medusa/medusa.ini' backup-node --backup-name 'studio-1' --mode differential");
    }

    @Test
    void sizes() {
        assertThat(SnapshotJmx.parseSize("0 bytes")).isZero();
        assertThat(SnapshotJmx.parseSize("512 bytes")).isEqualTo(512);
        assertThat(SnapshotJmx.parseSize("1.5 KiB")).isEqualTo(1536);
        assertThat(SnapshotJmx.parseSize("1.5 KB")).isEqualTo(1536);
        assertThat(SnapshotJmx.parseSize("2 MiB")).isEqualTo(2L << 20);
        assertThat(SnapshotJmx.parseSize("1,25 GiB")).isEqualTo(Math.round(1.25 * (1L << 30)));
        assertThat(SnapshotJmx.parseSize("lots")).isNull();
    }

    private static TabularData table(String[] names, Object[]... rows) throws Exception {
        OpenType<?>[] types = new OpenType<?>[names.length];
        java.util.Arrays.fill(types, SimpleType.STRING);
        CompositeType ct = new CompositeType("SnapshotDetails", "d", names, names, types);
        TabularType tt = new TabularType("SnapshotDetails", "d", ct, new String[]{names[0], names[1], names[2]});
        TabularDataSupport t = new TabularDataSupport(tt);
        for (Object[] r : rows) t.put(new CompositeDataSupport(ct, names, r));
        return t;
    }

    @Test
    void snapshotDetails311And41() throws Exception {
        String[] v311 = {"Snapshot name", "Keyspace name", "Column family name", "True size", "Size on disk"};
        String[] v41 = {"Snapshot name", "Keyspace name", "Column family name", "True size", "Size on disk",
                "Creation time", "Expiration time", "Ephemeral"};
        Map<String, TabularData> details = new LinkedHashMap<>();
        details.put("old", table(v311, new Object[]{"old", "shop", "orders", "1 KB", "2 KB"},
                new Object[]{"old", "shop", "items", "0 bytes", "1 KB"}));
        details.put("studio-1", table(v41, new Object[]{"studio-1", "t5_backup", "events", "1.00 KiB", "4.00 KiB",
                "2026-10-09T08:00:00.123Z", "", "false"}));
        List<BackupEntry> e = SnapshotJmx.entries(SnapshotJmx.rows(details), "10.0.0.1", null, "dc1");
        assertThat(e).hasSize(2);
        BackupEntry old = e.get(0);
        assertThat(old.sizeBytes()).isEqualTo(3072);
        assertThat(old.timeMs()).isNull();
        assertThat(old.tables()).isEqualTo(2);
        assertThat(old.notes()).contains("keyspaces: shop").contains("does not report");
        BackupEntry s = e.get(1);
        assertThat(s.timeMs()).isEqualTo(Instant.parse("2026-10-09T08:00:00.123Z").toEpochMilli());
        assertThat(s.sizeBytes()).isEqualTo(4096);
        assertThat(s.retention()).isEqualTo("kept until cleared (no TTL)");
        assertThat(s.type()).isEqualTo("snapshot");
    }

    @Test
    void settingsValidation() {
        BackupSettings ok = new BackupSettings(Provider.ESTATE, "/usr/local/bin", null, null, null, null, null).validated();
        assertThat(ok.configFile()).isEqualTo("/etc/backup/config.json");
        assertThat(ok.privilege()).isEqualTo(Privilege.SUDO);
        assertThatThrownBy(() -> new BackupSettings(null, "/usr/bin; rm -rf /", null, null, null, null, null).validated())
                .isInstanceOf(ApiException.class).hasMessageContaining("scriptDir");
        assertThatThrownBy(() -> new BackupSettings(null, "relative/dir", null, null, null, null, null).validated())
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new BackupSettings(null, "/a/../etc", null, null, null, null, null).validated())
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new BackupSettings(null, null, null, null, "medusa --x", null, null).validated())
                .isInstanceOf(ApiException.class).hasMessageContaining("medusaCommand");
        assertThatThrownBy(() -> new BackupSettings(null, null, null, null, null, null, 0).validated())
                .isInstanceOf(ApiException.class);
    }

    @Test
    void remotePollParsing() {
        RemoteRun.Poll p = RemoteRun.parsePoll("STATE rc= alive=1 n=12\nline one\nline");
        assertThat(p.exitCode()).isNull();
        assertThat(p.alive()).isTrue();
        assertThat(p.bytes()).isEqualTo(12);
        assertThat(p.chunk()).isEqualTo("line one\nline");
        assertThat(RemoteRun.parsePoll("STATE rc=3 alive=0 n=0\n").exitCode()).isEqualTo(3);
        assertThatThrownBy(() -> RemoteRun.parsePoll("bash: no such file")).isInstanceOf(IllegalStateException.class);
    }
}
