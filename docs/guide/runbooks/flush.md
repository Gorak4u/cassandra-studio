# Runbook: flush

Writes memtables to SSTables on disk (`nodetool flush`). Panel: **Operations → Maintenance**,
operation *Flush*.

## When to use

- Before copying SSTables or taking file-level backups by other means (a snapshot flushes by itself).
- Before a node restart or upgrade, to shorten commit log replay.
- To see what a table really occupies on disk.

## Before you start

- Low risk. It adds a burst of disk writes and, later, compaction work.
- Flushing many tables on a busy node at once can briefly raise disk I/O.

## Steps in Studio

1. Open the cluster, go to **Operations** and select the node(s) in the node list.
2. Open **Maintenance** and choose **Flush**.
3. Pick the **keyspace**; tick tables, or leave none ticked for all tables of the keyspace.
4. With several nodes, decide on *Continue on error*.
5. Click **Run on N nodes…**.

## What the confirmation shows

One command per node, for example:

```text
nodetool -h 10.0.0.11 flush -- shop orders
```

With several nodes a warning says they run one after another and stop at the first failure.

## Verify

- The job ends SUCCEEDED; each node's result is in the job log.
- **Views → tablestats** for the keyspace: *Memtable data size* and *Memtable cell count* drop
  close to zero and *Memtable switch count* goes up.

## Stop or roll back

A flush cannot be stopped on the node. **Cancel** only skips the nodes that have not started yet.
Nothing needs to be rolled back: a flush does not change data.
