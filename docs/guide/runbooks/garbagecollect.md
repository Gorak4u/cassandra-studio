# Runbook: garbage collect

Rewrites SSTables to remove data that newer data or deletes shadow (`nodetool garbagecollect`).
Panel: **Operations → Maintenance**, operation *Garbage collect*.

## When to use

When a table holds much overwritten or deleted data (many tombstones, high *SSTables per read*) and
normal compaction does not reclaim it, for example with LeveledCompactionStrategy or
TimeWindowCompactionStrategy. Check **Diagnostics → Partitions → Table histograms** first.

## Before you start

- Heavy I/O: every SSTable of the table is rewritten.
- **Granularity**: ROW removes shadowed rows (default); CELL also removes shadowed cells (more work).
- Tombstones younger than `gc_grace_seconds` are kept. Make sure repairs run regularly.
- *Jobs (-j)*: SSTables in parallel; 0 = Cassandra's default.

## Steps in Studio

1. **Operations**, select the node(s), **Maintenance**.
2. Choose **Garbage collect**, the keyspace and tables, the granularity and jobs.
3. Click **Run on N nodes…**.

## What the confirmation shows

```text
nodetool -h 10.0.0.11 garbagecollect -g ROW -- shop orders
```

Warning: "Rewrites SSTables to remove deleted data (ROW granularity); heavy I/O."

## Verify

- The job ends SUCCEEDED.
- **Views → tablestats**: *Space used (live)* is lower. **Diagnostics → Table histograms**:
  tombstones per read are lower.

## Stop or roll back

- **Cancel** calls `stopCompaction(GARBAGE_COLLECT)` on the running node and skips the rest.
- The removed data was already deleted or overwritten; there is nothing to roll back.
