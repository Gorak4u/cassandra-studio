# Runbook: backup run-now

Runs a backup immediately through the cluster's backup provider. Panel: **Backups → Run a backup
now** (BAK-3).

## When to use

- Before a risky change (upgrade, schema migration, bulk load, scrub) when the scheduled backups
  are not recent enough.
- To test that backups work on a cluster (then check the catalogue).

## Before you start

- A provider must be saved for the cluster: **Backups → Provider → Detect on nodes**, check the
  recommendation and the settings, **Save**.
  - *Estate backup scripts*: `full-backup-to-s3.sh` / `incremental-backup-to-s3.sh` in the script
    directory (default `/usr/local/bin`), configured by `/etc/backup/config.json`; usually run as
    root via `sudo -n` (see the [install guide](../install.md#sudo-for-the-estate-scripts)).
  - *Cassandra Medusa*: `medusa` on every node, optionally with a `medusa.ini`.
  - *Snapshots only (JMX)*: `nodetool snapshot` on each node; the backup stays on the node.
- Load: each node takes a snapshot and (estate scripts, Medusa) uploads it, so expect extra disk,
  CPU and network load. Use **Nodes at a time** = 1 and a **Throttle** (estate scripts, for example
  `50M/s`) on busy clusters.
- Nodes that are not UP are skipped and named in the confirmation.
- The **per-node time limit** (default 360 minutes) stops waiting for a node that takes longer.

## Steps in Studio

1. Open **Backups**. Under **Run a backup now** choose the **Scope**: whole cluster, one
   datacenter or one node.
2. Choose the **Type**: Full or Incremental (estate scripts), Full or Differential (Medusa), or
   Snapshot.
3. Set **Nodes at a time** (1–16) and, as offered: **Throttle**, **Backup name** (Medusa; the same
   name is used on every node), **Tag** and **Keyspaces** (snapshots).
4. Click **Run backup now…** and confirm.

## What the confirmation shows

One line per node with the exact command, for example:

```text
10.0.0.11: sudo -n '/usr/local/bin/full-backup-to-s3.sh' --throttle '50M/s'
10.0.0.11: sudo -n 'medusa' backup-node --backup-name 'studio-20261010-0900' --mode full
10.0.0.11: nodetool -h 10.0.0.11 snapshot -t studio-20261010-0900 -- shop
```

Warnings: how many nodes run at a time, which nodes are skipped because they are not UP, and the
load note for uploading providers. On PROD the connection name must be typed.

## Verify

- The job shows overall progress; the table below it shows each node's state, progress, backup id
  and the script's summary. Progress comes from the scripts' own log lines (snapshot taken, tables
  uploaded out of the total, manifest uploaded, summary).
- When it ends, the **Catalogue** refreshes: the new backup should be listed for every node with
  status COMPLETE. An estate backup set without `backup_manifest.json` is listed as INCOMPLETE.
- The job fails if any node fails, with each node's reason (the script's last error line and its
  exit code).

## Stop or roll back

- Scripts run detached on the node (`setsid nohup`, output under `/tmp/cassandra-studio-<uid>/`),
  so closing Studio or losing SSH does **not** stop a backup.
- **Cancel** (estate scripts, Medusa) sends SIGTERM to the run's process group on each node; the
  estate scripts' exit traps remove their lock and temporary files. A partly uploaded set stays in
  storage and is listed as INCOMPLETE; remove it with your storage tools if needed.
- Snapshot runs cannot be cancelled. Remove an unwanted snapshot with **Clear…** in the catalogue
  (or [Operations → Snapshots](snapshots.md)).
- Restoring a backup is not part of Studio 1.0; use the estate's `restore-from-s3.sh` or Medusa.
