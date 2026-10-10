# Runbook: compaction (major / user-defined)

Panel: **Operations → Maintenance**, operation *Major compaction* (`nodetool compact`) or
*User-defined compaction* (`nodetool compact --user-defined`).

## When to use

- **Major compaction**: to reclaim space after large deletes or TTL expiry, or to merge many small
  SSTables of a table, when you understand the effect on the compaction strategy.
- **User-defined compaction**: to compact a specific set of SSTables together, for example the
  SSTables that hold most of a table's tombstones.

## Before you start

- Major compaction rewrites every SSTable of the table(s). It needs free disk space (up to the size
  of the data being compacted) and adds heavy disk I/O and CPU.
- Without split output, SizeTieredCompactionStrategy ends with one large SSTable per table that
  may not be compacted again for a long time. Prefer **Split output (-s)** with STCS.
- Check **Monitoring → Nodes** (disk usage, pending compactions) and **Views → compactionstats**.
- For user-defined compaction, get the `-Data.db` paths from the node (for example
  `ls /var/lib/cassandra/data/<ks>/<table>-<id>/`). The paths are on the node, not on your computer.

## Steps in Studio

1. **Operations**, select the node(s), **Maintenance**.
2. Choose **Major compaction**, pick the keyspace and tables (none = all), and tick **Split output
   (-s)** if wanted. Or choose **User-defined compaction** and paste the SSTable data files, one per
   line.
3. Click **Run on N nodes…**.

## What the confirmation shows

```text
nodetool -h 10.0.0.11 compact -s -- shop orders
nodetool -h 10.0.0.11 compact --user-defined '/var/lib/cassandra/data/shop/orders-1b2c/nb-12-big-Data.db'
```

Warnings: the disk-space and I/O note, the single-SSTable note when split output is off, "Compacts
exactly the listed SSTables together" for user-defined compaction, and the one-node-after-another
note.

## Verify

- The job's progress follows the compaction on the node (read from `CompactionManager` every 2 s).
- **Views → tablestats**: *SSTable count* and *Space used (live)* change as expected.
- **Views → compactionstats**: no compaction of that table is still running.

## Stop or roll back

- **Cancel** calls `stopCompaction(COMPACTION)` on the running node and skips the remaining nodes.
  This stops **every** running compaction of type COMPACTION on that node, including background
  ones; Cassandra starts background compactions again by itself.
- A finished compaction cannot be undone, but the data is unchanged: only the files are merged.
