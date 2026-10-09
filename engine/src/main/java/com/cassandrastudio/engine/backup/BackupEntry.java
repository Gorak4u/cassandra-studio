package com.cassandrastudio.engine.backup;

/**
 * BAK-2: one backup in the catalogue, whatever the provider. Null = the provider does not tell
 * (the UI shows "unknown"); {@code notes} says why when that is not obvious.
 *
 * @param id            the backup's id: estate tag (2026-10-09-02-00-00), Medusa backup name or
 *                      snapshot tag
 * @param node          the node's address (null for a cluster-wide Medusa backup)
 * @param host          the host name the backup is stored under (estate: hostname -s)
 * @param type          full, incremental, differential, snapshot or unknown
 * @param timeMs        when the backup was taken (completion time where the provider records it)
 * @param status        COMPLETE, INCOMPLETE or UNKNOWN
 * @param location      where the data lives, e.g. s3://bucket/host/tag/
 * @param retention     the retention rule as text, e.g. "bucket lifecycle: 14 days"
 * @param expiresAtMs   when that rule removes it, if known
 * @param objectLock    object-lock state as text, e.g. "off", "COMPLIANCE, 30 days"
 * @param lockedUntilMs when the lock ends, if known
 */
public record BackupEntry(String id, String provider, String node, String host, String datacenter, String type,
                          Long timeMs, Long sizeBytes, String schemaVersion, String status, String statusDetail,
                          String location, String retention, Long expiresAtMs, String objectLock, Long lockedUntilMs,
                          Integer tables, Integer objects, String notes) {

    public static final String COMPLETE = "COMPLETE";
    public static final String INCOMPLETE = "INCOMPLETE";
    public static final String UNKNOWN = "UNKNOWN";

    BackupEntry withSchemaVersion(String v) {
        return new BackupEntry(id, provider, node, host, datacenter, type, timeMs, sizeBytes, v, status, statusDetail,
                location, retention, expiresAtMs, objectLock, lockedUntilMs, tables, objects, notes);
    }
}
