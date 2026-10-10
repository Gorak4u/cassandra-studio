# Runbooks

One runbook per operation wizard in Cassandra Studio 1.0. Each says when to use the operation,
what to check first, the steps in Studio, what the confirmation dialog shows, how to verify the
result, and how to stop or roll back.

Every wizard goes through the same safety check: read-only connections refuse it, the dialog shows
the exact nodetool command (or CQL, or script command) per node, and PROD connections need the
connection name typed. Every run is in the audit log. See
[Environments, PROD and read-only safety](../user-guide.md#environments-prod-and-read-only-safety).

| Runbook | Panel |
|---|---|
| [Flush](flush.md) | Operations → Maintenance |
| [Compaction (major / user-defined)](compaction.md) | Operations → Maintenance |
| [Cleanup](cleanup.md) | Operations → Maintenance |
| [Scrub](scrub.md) | Operations → Maintenance |
| [Upgrade SSTables](upgradesstables.md) | Operations → Maintenance |
| [Garbage collect](garbagecollect.md) | Operations → Maintenance |
| [Repair (full / incremental / sub-range, cancel)](repair.md) | Operations → Repair |
| [Snapshots](snapshots.md) | Operations → Snapshots |
| [Backup run-now](backup-run-now.md) | Backups |
| [Bulk unload](bulk-unload.md) | Bulk → Unload |
| [Bulk load](bulk-load.md) | Bulk → Load |
| [Drift investigation](drift-investigation.md) | Config |
| [GC log investigation](gc-log-investigation.md) | GC logs |
| [Thread-dump investigation](thread-dump-investigation.md) | Diagnostics → Threads |

## Common to all Operations wizards

- Operations need the connection's JMX method to be *SSH tunnel* or *Direct JMX*.
- Select the nodes in the node list on the left of the Operations panel first. Nodes run **one
  after another**. A failing node stops the sequence unless *Continue on error* is ticked; the job
  then fails with a per-node summary.
- Progress is finished nodes plus the running node's fraction. Log lines are prefixed with
  `[node]`. **Operations → Jobs** lists every operation since Studio started; **Details** shows the
  log and the per-node result.
- Quitting Studio does not stop an operation already running on a node; Studio only stops following
  it.
