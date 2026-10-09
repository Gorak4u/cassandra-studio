package com.cassandrastudio.engine.backup;

import static org.assertj.core.api.Assertions.assertThat;

import com.cassandrastudio.engine.backup.BackupSettings.Privilege;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parsing of the estate scripts' output. Fixtures in resources/backup were captured from the
 * test-env stub, which prints the exact lines, colours and JSON of the real scripts
 * (control repo cassandra_pfpt/files).
 */
class EstateFormatTest {

    static String fixture(String name) throws IOException {
        try (InputStream in = EstateFormatTest.class.getResourceAsStream("/backup/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void fullBackupLogDrivesProgressAndFindsTagAndSummary() throws IOException {
        EstateFormat.Progress p = new EstateFormat.Progress(true, 3);
        double last = 0;
        for (String line : fixture("full-backup-output.txt").split("\n")) {
            String clean = p.accept(line);
            assertThat(clean).doesNotContain("\u001B").doesNotStartWith("[2026");
            assertThat(p.fraction).isGreaterThanOrEqualTo(last);
            last = p.fraction;
        }
        assertThat(p.backupId).matches(EstateFormat.TAG.pattern());
        assertThat(p.tablesDone).isEqualTo(3);
        assertThat(p.tablesFailed).isZero();
        assertThat(p.summary).startsWith("Summary: all 3 table(s) selected for backup were uploaded to ");
        assertThat(p.fraction).isEqualTo(1.0);
        assertThat(p.lastError).isNull();
    }

    @Test
    void progressMidwayThroughTables() {
        EstateFormat.Progress p = new EstateFormat.Progress(true, 4);
        p.accept("[2026-10-09 02:00:00] \u001B[0;34mBackup Timestamp (Tag): 2026-10-09-02-00-00\u001B[0m");
        p.accept("[2026-10-09 02:00:01] \u001B[0;32mFull snapshot taken successfully.\u001B[0m");
        p.accept("[2026-10-09 02:00:02] \u001B[0;34m--- Starting Parallel Backup of Tables ---\u001B[0m");
        p.accept("[2026-10-09 02:00:03] \u001B[0;32mSuccessfully uploaded backup for shop.orders\u001B[0m");
        p.accept("[2026-10-09 02:00:04] \u001B[0;31mFailed to upload backup for shop.items to s3://b/h/t/shop/items.tar.gz.enc\u001B[0m");
        assertThat(p.fraction).isEqualTo(0.15 + 0.7 * 0.5);
        assertThat(p.phase).isEqualTo("2 / 4 tables uploaded, 1 failed");
        assertThat(p.lastError).startsWith("Failed to upload backup for shop.items");
    }

    @Test
    void failedRunKeepsTheLastErrorLine() {
        EstateFormat.Progress p = new EstateFormat.Progress(true, 0);
        p.accept("[2026-10-09 02:00:00] \u001B[0;34mVerifying s3 credentials...\u001B[0m");
        p.accept("[2026-10-09 02:00:01] \u001B[0;31ms3 credentials not found or invalid.\u001B[0m");
        p.accept("[2026-10-09 02:00:01] \u001B[0;31mAborting backup before taking a snapshot to prevent wasted effort.\u001B[0m");
        assertThat(p.lastError).isEqualTo("Aborting backup before taking a snapshot to prevent wasted effort.");
    }

    @Test
    void incrementalLogAndNothingToDo() throws IOException {
        EstateFormat.Progress p = new EstateFormat.Progress(false, 0);
        for (String line : fixture("incremental-backup-output.txt").split("\n")) p.accept(line);
        assertThat(p.fraction).isEqualTo(1.0);
        assertThat(p.tablesDone).isEqualTo(1);
        assertThat(p.nothingToDo).isFalse();

        EstateFormat.Progress idle = new EstateFormat.Progress(false, 0);
        idle.accept("[2026-10-09 02:00:00] \u001B[0;34mNo new incremental backup files found. Nothing to do.\u001B[0m");
        assertThat(idle.nothingToDo).isTrue();
        assertThat(idle.summary).contains("nothing to back up");
    }

    @Test
    void listBackupsOutput() throws IOException {
        EstateFormat.Listing l = EstateFormat.parseListBackups(fixture("list-backups-incomplete.txt"));
        assertThat(l.host()).isNotBlank();
        assertThat(l.types()).hasSize(3).containsEntry("2026-10-08-02-00", "unknown");
        assertThat(l.types().values()).contains("full", "incremental");

        JsonNode status = EstateFormat.parseStatusJson(fixture("backup-status.json"));
        assertThat(status.path("status").asText()).isEqualTo("OK");
        List<BackupEntry> e = EstateFormat.entriesFromListing(l, status, "10.0.0.1", "dc1");
        BackupEntry incomplete = e.stream().filter(x -> x.id().equals("2026-10-08-02-00")).findFirst().orElseThrow();
        assertThat(incomplete.status()).isEqualTo(BackupEntry.INCOMPLETE);
        assertThat(incomplete.timeMs()).isEqualTo(Instant.parse("2026-10-08T02:00:00Z").toEpochMilli());
        BackupEntry latest = e.stream().filter(x -> x.id().equals(status.path("backup_id").asText())).findFirst().orElseThrow();
        assertThat(latest.status()).isEqualTo(BackupEntry.COMPLETE);
        assertThat(latest.tables()).isEqualTo(status.path("tables_count").asInt());
        assertThat(latest.location()).contains("acme-test-core-backups");
    }

    @Test
    void probeOutputToCatalogue() {
        String out = String.join("\n",
                "CONFIG {\"backup_backend\":\"s3\",\"s3_bucket_name\":\"acme-prod-core-backups\",\"storage_local_path\":null,"
                        + "\"storage_azure_account\":null,\"s3_retention_period\":30,\"s3_object_lock_enabled\":true,"
                        + "\"s3_object_lock_mode\":\"COMPLIANCE\",\"s3_object_lock_retention\":14}",
                "HOST cass1",
                "SET 2026-10-09-02-00-05 49 123456789",
                "MANIFEST {\"cluster_name\":\"acme-core\",\"backup_id\":\"2026-10-09-02-00-05\",\"backup_type\":\"full\","
                        + "\"timestamp_utc\":\"2026-10-09T02:04:11+00:00\",\"source_node\":{\"ip_address\":\"10.0.0.1\","
                        + "\"datacenter\":\"dc_east\",\"rack\":\"rack1\",\"tokens\":[\"1\"]},\"tables_backed_up_count\":26}",
                "SET 2026-10-09-06-00 3 -",
                "MANIFEST null",
                "END");
        EstateFormat.Probe p = EstateFormat.parseProbe(out);
        assertThat(p.error()).isNull();
        assertThat(p.host()).isEqualTo("cass1");
        List<BackupEntry> e = EstateFormat.entries(p, "10.0.0.1", "dcX");
        BackupEntry full = e.get(0);
        assertThat(full.type()).isEqualTo("full");
        assertThat(full.status()).isEqualTo(BackupEntry.COMPLETE);
        assertThat(full.datacenter()).isEqualTo("dc_east");
        assertThat(full.sizeBytes()).isEqualTo(123456789L);
        assertThat(full.tables()).isEqualTo(26);
        assertThat(full.timeMs()).isEqualTo(Instant.parse("2026-10-09T02:04:11Z").toEpochMilli());
        assertThat(full.location()).isEqualTo("s3://acme-prod-core-backups/cass1/2026-10-09-02-00-05/");
        assertThat(full.retention()).isEqualTo("bucket lifecycle: expires after 30 days");
        assertThat(full.expiresAtMs()).isEqualTo(Instant.parse("2026-11-08T02:04:11Z").toEpochMilli());
        assertThat(full.objectLock()).startsWith("COMPLIANCE, 14 days");
        assertThat(full.lockedUntilMs()).isEqualTo(Instant.parse("2026-10-23T02:04:11Z").toEpochMilli());
        assertThat(full.schemaVersion()).isNull();

        BackupEntry partial = e.get(1);
        assertThat(partial.status()).isEqualTo(BackupEntry.INCOMPLETE);
        assertThat(partial.type()).isEqualTo("unknown");
        assertThat(partial.sizeBytes()).isNull();
        assertThat(partial.datacenter()).isEqualTo("dcX");
        assertThat(partial.timeMs()).isEqualTo(Instant.parse("2026-10-09T06:00:00Z").toEpochMilli());
    }

    @Test
    void probeErrorsAndTruncation() {
        assertThat(EstateFormat.parseProbe("ERROR cannot read /etc/backup/config.json\n").error())
                .isEqualTo("cannot read /etc/backup/config.json");
        assertThat(EstateFormat.parseProbe("HOST x\nSET 2026-10-09-02-00 1 -\n").error()).isEqualTo("the listing did not complete");
        assertThat(EstateFormat.parseProbe("HOST x\nTRUNCATED\nEND\n").truncated()).isTrue();
    }

    @Test
    void retentionByBackend() {
        EstateFormat.Config local = new EstateFormat.Config("local", "b", "/var/backups", null, 14, true, "GOVERNANCE", 7);
        EstateFormat.Retention r = EstateFormat.retention(local, 0L);
        assertThat(r.text()).isEqualTo("14 days configured, not enforced by the local backend");
        assertThat(r.expiresAt()).isNull();
        assertThat(r.lock()).isEqualTo("enabled in config, not supported by the local backend");
        EstateFormat.Config none = new EstateFormat.Config("s3", "b", null, null, 0, false, null, null);
        assertThat(EstateFormat.retention(none, 0L).text()).isEqualTo("none configured (kept until deleted)");
        assertThat(EstateFormat.retention(none, 0L).lock()).isEqualTo("off");
        assertThat(EstateFormat.location(local, "east1", "2026-10-09-02-00")).isEqualTo("/var/backups/east1/2026-10-09-02-00/");
    }

    @Test
    void commandsAndValidation() {
        BackupSettings sudo = BackupSettings.DEFAULTS;
        assertThat(EstateFormat.runCommand(sudo, "full", null)).isEqualTo("sudo -n '/usr/local/bin/full-backup-to-s3.sh'");
        BackupSettings plain = new BackupSettings(BackupSettings.Provider.ESTATE, "/opt/bin/", null, Privilege.NONE, null, null, null);
        assertThat(EstateFormat.runCommand(plain, "incremental", "50M/s"))
                .isEqualTo("'/opt/bin/incremental-backup-to-s3.sh' --throttle '50M/s'");
        assertThat(EstateFormat.validThrottle("50M/s")).isTrue();
        assertThat(EstateFormat.validThrottle("1G/s")).isTrue();
        assertThat(EstateFormat.validThrottle("50M/s; rm -rf /")).isFalse();
        assertThat(EstateFormat.tagTime("2026-10-09-02-00-05")).isEqualTo(Instant.parse("2026-10-09T02:00:05Z").toEpochMilli());
        assertThat(EstateFormat.tagTime("adhoc_x")).isNull();
        assertThat(EstateFormat.probeCommand(sudo)).startsWith("sudo -n bash -c ");
    }
}
