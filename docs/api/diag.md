# Diagnostics API (JVM-1, JVM-2, PRF-1, PRF-2)

Thread dumps and top threads over JMX, hot partitions, table histograms, tombstone and
large-partition warnings. Everything is read-only towards the cluster, so no route needs the
ActionGuard. Long tasks return a `Job` (202, see [jobs.md](jobs.md)); poll it until done, its
`result` holds the data. Shapes: `engine/.../diag/*.java` = `ui/src/panels/diag/diagApi.ts`.

`node` is a node address as `GET /api/clusters/{id}/info` lists it; an unknown node is a 400.
Thread and partition reads need JMX (`SSH_TUNNEL` or `DIRECT`, 409 `jmx_required` otherwise);
logs and the tombstone scan need SSH. An unreachable node is a 502 `node_unreachable` (single-node
calls) or an entry in `errors` / `nodes[].error` (all-node calls).

Base path: `/api/clusters/{id}/diag`.

## Threads

| Method | Path | Body / query | Returns |
|---|---|---|---|
| POST | `/threads/dumps` | `{node}` | `ThreadDump` (stored) |
| GET | `/threads/dumps` | | `Summary[]`, newest first |
| GET | `/threads/dumps/{dumpId}` | | `ThreadDump` |
| GET | `/threads/dumps/{dumpId}/text` | | `text/plain`, jstack format (attachment) |
| DELETE | `/threads/dumps/{dumpId}` | | 204 |
| POST | `/threads/series` | `{node, count 2..20 = 3, intervalSec 1..300 = 5}` | `Job` (202); result `{seriesId, dumpIds, node}` |
| GET | `/threads/compare?a=&b=` | two dump ids of the same node | `Comparison` |
| GET | `/threads/top?node=&limit=30&group=false` | | `TopView` |

- A dump is `Threading.dumpAllThreads(lockedMonitors=true, lockedSynchronizers=true)` plus
  `findDeadlockedThreads()`, read with `ThreadInfo.from`, so JDK 8 (3.11) to 17 (5.0) nodes all work.
  `ThreadDump` has `byState`, `deadlocks[]` (the cycle, as "A waits for X held by B" lines),
  `threads[]` (deadlocked and blocked first; each with `stack`, `lock`, `lockOwnerId`, `ownerChain`:
  the owners of the lock it waits for, transitively, and `stackKey`) and `groups[]` (threads with an
  identical stack, largest group first). The engine keeps the newest 40 dumps per connection in memory,
  dropped on disconnect.
- `Comparison` (earlier dump first): `added`, `removed`, `changed` (state), `stuck` (RUNNABLE or
  BLOCKED with the same stack in both; `likelyIdle` when parked in native code such as epoll/accept),
  `stateCounts` (`[first, second]`).
- `TopView` (like sjk ttop): CPU and user time per thread between this call and the previous one for
  the same node (the first call samples twice, 1 s apart). `cpuPct`/`userPct` are % of one core;
  `allocBytesPerSec` from `getThreadAllocatedBytes` (null when the JVM does not report it);
  `processCpuPct` is of all cores (`ProcessCpuTime`). A sample is 5 JMX round trips whatever the
  thread count: the array forms `getThreadCpuTime/UserTime/AllocatedBytes(long[])` and
  `getThreadInfo(long[], 0)`; `method` is `per-thread` when a JVM lacks them (capped at 400 threads).
  `group=true` merges pools by name with numeric suffixes stripped (`ReadStage-12` → `ReadStage`).
  A baseline older than 2 minutes is discarded.

## Partitions

| Method | Path | Body / query | Returns |
|---|---|---|---|
| POST | `/partitions/hot` | `{tables: ["ks.t", ...] (1..50), durationSec 1..600 = 10, capacity 10..1024 = 256, top = 10, nodes: [] = all}` | `Job` (202, cancellable); result `HotResult` |
| GET | `/partitions/histograms?node=&keyspace=&includeSystem=false` | | `{merged, perNode, errors, thresholds}` |
| GET | `/partitions/warnings?node=&limit=500` | | `{warnings, nodes}` |
| POST | `/partitions/tombstone-scan` | `{node, keyspace?, table?}` | `Job` (202); result `ScanResult` |
| GET / PUT | `/settings` | `{logPath, largePartitionMb, tombstonesP99, tombstoneScanScript}` | effective settings |

- Hot partitions = nodetool toppartitions over JMX on each node at once:
  3.11 `ColumnFamilyStore.beginLocalSampling(String, int)` / `finishLocalSampling(String, int)` →
  `{cardinality, partitions}`, samplers READS and WRITES; 4.0+ `beginLocalSampling(String, int, int
  durationMillis)` / `finishLocalSampling` → list, samplers READS, WRITES and WRITE_SIZE (the largest
  partition updates seen, bytes). Samplers are always finished, also on cancel. `HotResult.nodes[]`
  per node (with the `api` used, or `error`), `merged[]` all nodes: counts summed (WRITE_SIZE: max),
  `nodes` lists where each key was seen.
- Histograms per table (as nodetool tablehistograms): `partitionSize` and `cellCount` from the
  SSTables' EstimatedHistogram buckets (p50..p99, min, max; max from `MaxPartitionSize`),
  `tombstonesPerRead`, `sstablesPerRead`, `liveCellsPerRead` from the read histograms. `merged` has the
  worst value per table over the nodes and `flags`: `LARGE_PARTITION` (max > `largePartitionMb`,
  default 100 MiB), `TOMBSTONES` (p99 > `tombstonesP99`, default 1000). Flagged tables come first.
- Warnings: `grep -E` on `logPath` (default `/var/log/cassandra/system.log`) on every node over SSH,
  the newest `limit` lines: `TOMBSTONE_WARN` ("Read N live rows and M tombstone cells for query ..."),
  `TOMBSTONE_ABORT` ("Scanned over N tombstones ..."), `LARGE_PARTITION_WRITE` / `_COMPACT`
  ("Writing/Compacting large partition ks/t:key (size)"), with node, time, keyspace, table, counts,
  key and size; 3.11, 4.x and 5.0 wordings. `nodes[]` says per node how many were found or why the
  log could not be read. Rotated (zipped) logs are not read.
- Tombstone scan: runs the estate's `tombstone-scan.sh` (cassandra-control-repo, `cass-ops
  tombstone-scan`, deployed to `/usr/local/bin`) over SSH: overview (`-k` filter) or per-SSTable deep
  dive (`-k` and `-t`). The job fails with a clear message when the script is not installed.
- Settings are stored per connection in the settings table (`diag.settings/<connectionId>`).
