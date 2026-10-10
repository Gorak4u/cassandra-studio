# Runbook: snapshots

Takes, lists and clears snapshots (`nodetool snapshot`, `listsnapshots`, `clearsnapshot`). Panel:
**Operations → Snapshots**. (Backups that use the *snapshot* provider work the same way; see
[backup run-now](backup-run-now.md).)

## When to use

- Before risky changes: schema changes, bulk loads, upgrades, scrub without its own snapshot.
- To keep a point-in-time copy on the nodes that you then copy elsewhere.
- **Clear** old snapshots to free disk space.

## Before you start

- A snapshot is a set of hard links: it costs no space when taken, but keeps deleted and compacted
  data on disk until it is cleared. Watch disk usage in **Monitoring** and *Space used by
  snapshots* in **Views → tablestats**.
- By default a snapshot flushes memtables first. **Skip flush (-sf)** is faster, but data still in
  memtables is not in the snapshot.
- Tags: letters, digits, `_`, `.` and `-`, up to 128 characters. Use the same tag on every node.
- Clearing deletes the snapshot's files; it cannot be undone.

## Steps in Studio

**Take a snapshot**

1. **Operations**, select the node(s) (usually all), **Snapshots**.
2. Enter a **Tag** (a timestamped default is filled in), tick keyspaces (none = all keyspaces) and
   *Skip flush* if wanted.
3. Click **Snapshot on N nodes…** and confirm.

**List**: the list shows snapshots on the selected nodes (or all nodes when none are selected) by
tag, with nodes, table count and sizes, and per table. Filter by tag or keyspace; **Refresh** reads
them again. Nodes that could not be read are named with the reason.

**Clear**: click **Clear…** on a tag's row and confirm.

## What the confirmation shows

```text
nodetool -h 10.0.0.11 snapshot -t before-upgrade -- shop
nodetool -h 10.0.0.11 clearsnapshot -t before-upgrade
```

Warnings: "Snapshots are hard links: they cost no space now, but keep deleted and compacted data on
disk until cleared." (plus the skip-flush note); for clear, "Deletes the snapshot's files; it
cannot be restored from afterwards." Clear is marked destructive (red Run button).

## Verify

- The job ends SUCCEEDED; the snapshot appears in the list on every selected node with its size
  (on Cassandra 4.1+ also its creation time).
- After clearing, the tag disappears from the list and *Space used by snapshots* drops.

## Stop or roll back

- Snapshot jobs are short; **Cancel** only skips the nodes that have not started.
- A snapshot you did not want: clear it. A cleared snapshot cannot be recovered.
- Restoring from a snapshot (copying its files back, `nodetool refresh` or `sstableloader`) is done
  outside Studio in 1.0.
