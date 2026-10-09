# Backups API (Phase 3, Track 5: BAK-1..3)

Backups go through a **provider chosen per cluster** (Q-6): the estate's backup scripts on the nodes,
Cassandra Medusa's CLI over SSH, or snapshots only over JMX. Shapes: `engine/.../backup/*.java` =
`ui/src/panels/backup/backupApi.ts`. All routes are under `/api/clusters/{id}/backup`.

| Method | Path | Returns | Notes |
|---|---|---|---|
| GET | `/settings` | `BackupSettings` | Effective settings (defaults filled in). `provider` null = not chosen yet. |
| PUT | `/settings` | `BackupSettings` | Saved in the `settings` table as `backup.provider/<connectionId>`. 400 on a bad path or command. Studio-local, so no ActionGuard. |
| POST | `/detect` | `Detection` | Per node over SSH (20 s): estate scripts in `scriptDir` or on the PATH, `/etc/backup/config.json`, `medusa`, `/etc/medusa/medusa.ini`, `sudo -n`; JMX for snapshots. Read-only. |
| GET | `/catalogue` | `Catalogue` | BAK-2. 409 `no_provider` until a provider is saved. Per-node listing errors are in `nodes[]`, never fail the whole call. |
| POST | `/run` | `202 Job` | BAK-3. ActionGuard category `backup` (428 with the exact command per node as `preview`). Body below. |
| GET | `/runs/{jobId}` | `RunStatus` | Live per-node state of a run (the job carries overall progress, message and the log). |
| POST | `/snapshots/clear` | `{node, tag, cleared}` | Snapshot provider: `{node, tag}`; destructive, guarded (`nodetool clearsnapshot -t`). |

## BackupSettings

`{provider: "ESTATE"|"MEDUSA"|"SNAPSHOT"|null, scriptDir: "/usr/local/bin", configFile: "/etc/backup/config.json",
privilege: "SUDO"|"NONE", medusaCommand: "medusa", medusaConfig: null, nodeTimeoutMinutes: 360}`.
`privilege` SUDO prefixes node commands with `sudo -n` (the estate scripts and their root-only config
need root; `cass-ops` refuses non-root too). Paths must be absolute and plain (`[A-Za-z0-9._/@+-]`).

## Run request

`{scope: "CLUSTER"|"DC"|"NODE", datacenter?, node?, mode, concurrency?: 1-16 (1 = one node at a time),
throttle?, name?, keyspaces?, confirmed?, confirmName?}`

| Provider | mode | Command per node (preview) |
|---|---|---|
| ESTATE | `full` / `incremental` | `sudo -n '/usr/local/bin/full-backup-to-s3.sh' [--throttle '50M/s']` (or `incremental-backup-to-s3.sh`) |
| MEDUSA | `full` / `differential` | `sudo -n 'medusa' [--config-file 'f'] backup-node --backup-name 'studio-…' --mode full` (same name on every node) |
| SNAPSHOT | `snapshot` | `nodetool -h <node> snapshot -t <tag> [-- ks…]`, run as JMX `takeSnapshot` |

Nodes that are not UP are skipped (named in the guard's warnings and as `SKIPPED` in the results).
Scripts run **detached** on the node (`setsid nohup`, output in `/tmp/cassandra-studio-<uid>/`), and
Studio polls the output every 2 s: a dropped SSH session does not kill a backup. Progress comes from
the scripts' own log lines (snapshot taken, `Successfully uploaded backup for ks.t` against the number
of tables, manifest uploaded, `Summary: …`). Cancel sends SIGTERM to the run's process group on each
node (the scripts' EXIT traps remove their lock and temp files; a set without `backup_manifest.json`
is listed as INCOMPLETE). Snapshot runs are not cancellable. The job fails when any node fails, with
each node's reason (the script's last red log line and exit code); per-node results stay in `/runs/{jobId}`.

## Catalogue (BAK-2)

`BackupEntry`: `id, provider, node, host, datacenter, type (full|incremental|differential|snapshot|unknown),
timeMs, sizeBytes, schemaVersion, status (COMPLETE|INCOMPLETE|UNKNOWN), statusDetail, location, retention,
expiresAtMs, objectLock, lockedUntilMs, tables, objects, notes`. **Null = the provider does not tell**
(the UI shows "unknown"; `notes` says why).

- **ESTATE**: on every node, a read-only listing that sources the estate's own `backup-storage-lib.sh`
  (so s3, gcs, azure and local backends all work) and reads only the storage keys of `config.json`
  (never passwords or the encryption key): `<host>/<tag>/` prefixes (newest 100), each set's
  `backup_manifest.json` (type, completion time, DC, tables), object count and size (s3: `aws s3 ls
  --summarize`; local: file sizes). Retention = `s3_retention_period` (bucket lifecycle, s3 only), object
  lock = `s3_object_lock_*` (s3: mode + days; gcs: event-based hold; others: not supported). If sudo
  allows only the scripts, it falls back to `restore-from-s3.sh --list-backups` + `backup-status.sh --json`.
- **MEDUSA**: `medusa list-backups` (text), and `medusa status --backup-name` for the newest 10 (nodes
  complete/incomplete/missing, files, size). Cluster-wide rows (`node` null).
- **SNAPSHOT**: JMX `getSnapshotDetails` per node (4.1+: operation with options, creation and expiry
  time; 3.11/4.0: the `SnapshotDetails` attribute, no time). Sizes are parsed from Cassandra's text.
- `schemaVersion` is recorded by Studio when it starts a backup (the node's schema version from the
  driver) and is unknown for backups taken elsewhere.

## Test environment

`test-env/estate-stub/` is a stub of the estate scripts with the same install path, CLI arguments,
config file, log lines and object layout, plus the estate's unchanged `backup-storage-lib.sh` (local
backend). It copies the live SSTables instead of `nodetool snapshot` (no nodetool, cqlsh, openssl or
root in the sshd sidecar) and never writes to the data volume. Compose mounts it into the sidecars at
`/usr/local/bin` and `/etc/backup`, with a shared `estate-backups` volume as the bucket; use provider
ESTATE with privilege NONE there.
