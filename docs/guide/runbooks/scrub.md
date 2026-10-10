# Runbook: scrub

Rebuilds SSTables, checking and fixing corruption (`nodetool scrub`). Panel: **Operations →
Maintenance**, operation *Scrub*.

## When to use

When a node logs `CorruptSSTableException`, or reads of one table fail on one node. Often the
better fix is to replace the bad SSTable from the replicas (remove it and repair); scrub is for
when that is not possible.

## Before you start

- Scrub rewrites all SSTables of the chosen tables. By default **Cassandra takes a snapshot first**,
  which uses disk space until you clear it.
- Options:
  - **No snapshot (-ns)**: skips that snapshot. Only when you have another copy.
  - **Skip corrupted (-s)**: rows in corrupt partitions are **dropped** (data loss). The action is
    then marked destructive and the dialog's **Run** button is red.
  - **No validate (-n)** and **Reinsert overflowed TTL (-r)**: as in nodetool.
  - *Jobs (-j)*: SSTables in parallel; 0 = Cassandra's default.
- Run it only on the affected node and table, and repair the table afterwards so the node gets back
  any rows it dropped.

## Steps in Studio

1. **Operations**, select the affected node, **Maintenance**.
2. Choose **Scrub**, the keyspace, the table(s) and the options.
3. Click **Run on 1 node…**.

## What the confirmation shows

```text
nodetool -h 10.0.0.11 scrub -s -- shop orders
```

Warnings: "Scrub rewrites all SSTables; a snapshot is taken first." (or "… and takes NO snapshot
first."), and with skip corrupted "Skip corrupted: rows in corrupt partitions are DROPPED (data loss)."

## Verify

- The job ends SUCCEEDED; the node's result is in the log.
- The node's log has no more corruption errors for the table. Run a test read in the **Query**
  panel with the toolbar's **Node** set to that node.
- Then run a [repair](repair.md) of the table.

## Stop or roll back

- **Cancel** calls `stopCompaction(SCRUB)` on the running node.
- To roll back, restore the snapshot Cassandra took before scrubbing (its tag starts with
  `pre-scrub-`; it is listed under **Operations → Snapshots**) with your usual restore procedure.
  Studio 1.0 has no restore wizard. Clear the snapshot when you no longer need it.
