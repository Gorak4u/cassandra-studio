# Runbook: repair (full / incremental / sub-range, cancel)

Makes replicas consistent by comparing Merkle trees and streaming differences (`nodetool repair`).
Panel: **Operations → Repair**.

## When to use

- Regularly, so every range is repaired at least once within `gc_grace_seconds` (default 10 days);
  otherwise deleted data can come back.
- After a node was down longer than the hint window (`max_hint_window`, default 3 h).
- After [scrub](scrub.md) with *skip corrupted*, or after replacing data on a node.
- **Sub-range** repair: to repair one token range, for example to retry a range that failed.

Studio 1.0 runs repairs on demand only; scheduled and segmented repair (Reaper-style) is planned
for v1.1.

## Before you start

- Repair loads the cluster: Merkle tree building (CPU, disk) and streaming (network, disk). Run it
  outside peak hours and watch **Monitoring** (pending compactions, dropped messages, latency).
- All replicas of the ranges must be up: check **Overview**. A down replica fails the repair.
- **Full or incremental**: full repair is the usual choice, and the safe one on Cassandra 3.x.
  Incremental repair marks repaired SSTables; switching back to full later needs care.
- **Primary range only (-pr)**: repair each range once, on its primary owner. Use it when you
  repair **every** node of the cluster (or DC). Without -pr on several nodes, each range is repaired
  once per replica. -pr is not used together with a sub-range.
- **Parallelism**: Parallel (default), Sequential (-seq, one replica at a time, lighter) or DC
  parallel (-dcpar).
- **Datacenters (-dc)**: limit to some DCs (none ticked = all). **Job threads (-j)**: 1–4 tables at
  once.
- A replication factor of 1 has nothing to repair; the node reports that and the job succeeds.

## Steps in Studio

1. **Operations**, select the node(s) in the node list. To repair the whole cluster with -pr,
   select every node (the DC headings have select-all).
2. Open **Repair**. Pick the keyspace and tables (none = all tables).
3. Choose **Full** or **Incremental**, *Primary range only*, parallelism, job threads, datacenters.
4. For a sub-range, fill in **Start token (-st)** and **End token (-et)** (whole numbers; get them
   from **Views → ring** or **Monitoring → Ring**).
5. With several nodes, decide on *Continue on error*.
6. Click **Repair on N nodes…**.

## What the confirmation shows

One command per node (and per sub-range):

```text
nodetool -h 10.0.0.11 repair -full -pr -- shop
nodetool -h 10.0.0.11 repair -full -seq -dc dc_east -st -9223372036854775808 -et 0 -- shop orders
```

Warnings, as they apply: incremental repair on 3.x can over-stream; incremental repair marks
SSTables; full repair without -pr on several nodes repeats ranges; repair streams data and builds
Merkle trees; nodes run one after another.

## Verify

- Progress comes live from the node's repair notifications (and, on 4.0+, a status check every
  10 s, so a lost notification cannot hang the job). The job log shows each session's messages
  prefixed with `[node]`.
- The job ends SUCCEEDED with a result per node. On failure, the log shows the node's error, for
  example a down replica or a streaming failure.
- **Views → tablestats**: *Percent repaired* for incremental repair.

## Stop or roll back

- **Cancel** calls `forceTerminateAllRepairSessions` on the node that is running and skips the
  remaining nodes. This terminates **all** repair sessions on that node, also ones not started from
  Studio (for example by Reaper or a cron job).
- Repair writes only data that already exists on another replica, so there is nothing to roll back.
  Run the repair again to finish the ranges that were not repaired.
