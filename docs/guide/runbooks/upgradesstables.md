# Runbook: upgrade SSTables

Rewrites SSTables to the node's current SSTable format (`nodetool upgradesstables`). Panel:
**Operations → Maintenance**, operation *Upgrade SSTables*.

## When to use

After a major Cassandra upgrade (for example 3.11 → 4.0, or 4.1 → 5.0), on each upgraded node and
before the next major upgrade. Also after changing compression or other table options that only
apply to newly written SSTables (with **Include all SSTables (-a)**).

## Before you start

- Every node must already run the new version: check **Overview** (a mixed-versions warning means
  the upgrade is not finished).
- Rewrites SSTables: free disk space and extra I/O. Run node by node, outside peak hours.
- Without *-a* only SSTables on an older format are rewritten; with *-a* every SSTable is.
- *Jobs (-j)*: SSTables in parallel; 0 = Cassandra's default.

## Steps in Studio

1. **Operations**, select the node(s), **Maintenance**.
2. Choose **Upgrade SSTables**, the keyspace and tables (none = all), and *Include all SSTables* if
   needed.
3. Click **Run on N nodes…**. Repeat for each keyspace.

## What the confirmation shows

```text
nodetool -h 10.0.0.11 upgradesstables -a -- shop
```

Warning: "Rewrites SSTables that are not on the current format." or "Rewrites ALL SSTables, also
those already on the current format."

## Verify

- The job ends SUCCEEDED on every node.
- On the node, the SSTable file names carry the new version prefix (for example `nb-` on 4.x).

## Stop or roll back

- **Cancel** calls `stopCompaction(UPGRADE_SSTABLES)` on the running node. Rewritten SSTables stay
  rewritten; run again to finish.
- SSTables cannot be rewritten back to an older format. Going back to an older Cassandra version
  needs a restore of a backup taken before the upgrade.
