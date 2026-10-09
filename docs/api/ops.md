# Operations API (OPS-1..4)

nodetool through the GUI: status and statistics views read over JMX, and maintenance, repair and
snapshots run as jobs (docs/api/jobs.md). Engine: `engine/.../ops/*`, routes `api/OpsRoutes.java`;
UI: `ui/src/panels/ops/`. Shapes: `ops/OpsModel.java` = `ui/src/panels/ops/opsApi.ts`.

Operations need JMX: the connection's JMX method must be `SSH_TUNNEL` or `DIRECT` (else 409
`jmx_required`). Views use the read JMX (30 s read timeout, 60 s per view); actions use the
operations JMX (no read timeout, the calls block until Cassandra is done).

`node` / `nodes` are node addresses as the driver reports them (`ClusterInfo.nodes[].address`) or
host ids; an unknown node is 400.

## Views (OPS-1, read-only)

| Method | Path | Returns |
|---|---|---|
| GET | `/api/clusters/{id}/ops/views` | the view names |
| GET | `/api/clusters/{id}/ops/views/{view}?node=&keyspace=&table=&key=` | `View` |

`View = {view, node, command, sections: Section[], notes: string[]}`;
`Section = {title, columns, rows: (string|null)[][], keyValue}`. Cells are formatted like nodetool
prints them; null = not available on this node/version. `command` is the nodetool equivalent.
`node` omitted = the first node that is up.

| view | nodetool | params | source (MBeans) |
|---|---|---|---|
| status | status [ks] | keyspace (effective ownership) | StorageService Live/Unreachable/Joining/Leaving/MovingNodes, LoadMap, Ownership / effectiveOwnership, TokenToEndpointMap, EndpointToHostId (HostIdMap on 3.11); EndpointSnitchInfo getDatacenter/getRack |
| info | info | | StorageService, Runtime, Memory, EndpointSnitchInfo, metrics Storage/Cache/Table |
| ring | ring [ks] | keyspace | as status, one row per token (first 5000) |
| describecluster | describecluster | | StorageService ClusterName/PartitionerName, EndpointSnitchInfo SnitchName, DynamicEndpointSnitch, StorageProxy SchemaVersions(WithPort) |
| tpstats | tpstats | | metrics ThreadPools, DroppedMessage |
| tablestats | tablestats [ks[.t]] | keyspace, table (both optional; none = non-system keyspaces) | metrics Table (ColumnFamily fallback) |
| tablehistograms | tablehistograms ks t | keyspace, table (required) | SSTablesPerReadHistogram, Read/WriteLatency, EstimatedPartitionSizeHistogram, EstimatedColumnCountHistogram (bucket percentiles as Cassandra computes them) |
| proxyhistograms | proxyhistograms | | metrics ClientRequest Latency per scope |
| gossipinfo | gossipinfo | | FailureDetector AllEndpointStates(WithPort), SimpleStates |
| compactionstats | compactionstats | | CompactionManager Compactions, metrics Compaction PendingTasks(ByTableName) |
| netstats | netstats | | StreamManager CurrentStreams, StorageProxy ReadRepair*, MessagingService *MessagePending/Completed/DroppedTasks |
| getendpoints | getendpoints ks t key | keyspace, table, key (required) | StorageService getNaturalEndpoints(WithPort) |

Errors: 400 for an unknown view or missing parameters, 502 `jmx_failed` with the node's message,
504 `timeout`.

## Actions (OPS-2, OPS-3, OPS-4)

Every action is checked by ActionGuard (category `ops`): without `confirmed: true` (or, on PROD,
`confirmName` = the connection name) the answer is 428 with `details.preview` = one nodetool
command per node (per node and sub-range for repair) and `details.warnings`. Read-only connections
get 403. Once confirmed the answer is 202 with the `Job`; the nodes run one after another, progress
= finished nodes + the running node's fraction, log lines are prefixed with `[node]`, the job's
`result` is `[{node, status, ms, detail}]`, and the job runner writes the audit entry. A failing
node stops the sequence unless `continueOnError` is true; the job then fails with a summary.

| Method | Path | Body |
|---|---|---|
| POST | `/api/clusters/{id}/ops/actions/{action}` | `{nodes, keyspace, tables?, splitOutput?, jobs?, disableSnapshot?, skipCorrupted?, noValidate?, reinsertOverflowedTtl?, includeAll?, granularity?, files?, continueOnError?}` |
| POST | `/api/clusters/{id}/ops/repair` | `{nodes, keyspace, tables?, mode: full\|incremental, primaryRange?, dataCenters?, parallelism?: parallel\|sequential\|dc_parallel, ranges?: [{start, end}], jobThreads? (1-4), continueOnError?}` |
| GET | `/api/clusters/{id}/ops/snapshots?node=` | `{snapshots: Snapshot[], errors: [{node, error}]}`; no node = all nodes |
| POST | `/api/clusters/{id}/ops/snapshots` | `{nodes, tag, keyspaces?, tables? ("ks.table"), skipFlush?}` |
| POST | `/api/clusters/{id}/ops/snapshots/clear` | `{nodes, tag \| all: true, keyspaces?}` (destructive) |

`action` = `flush`, `compact` (major; `splitOutput`), `usercompact` (`files`: SSTable `-Data.db`
paths on the node), `cleanup`, `scrub` (`disableSnapshot`, `skipCorrupted` = destructive,
`noValidate`, `reinsertOverflowedTtl`), `upgradesstables` (`includeAll`), `garbagecollect`
(`granularity` ROW/CELL). `jobs` 0 = Cassandra's default.

### Versions

Operation signatures are taken from the node's MBeanInfo, newest overload first, so the same
request works on 3.11, 4.0, 4.1 and 5.0 (checked live on 3.11.19, 4.1.12 and 5.0.9):
`forceKeyspaceCleanup(int,String,String[])` / `(String,String[])`, `scrub` with 7 / 6 / 5
parameters, `upgradeSSTables(String,boolean,int,String[])` / `(String,boolean,String[])`. An
operation a node lacks fails that node with "... is not available on this Cassandra version".
cleanup/scrub/upgradesstables/garbagecollect status codes (1 aborted, 2 unable to cancel,
3 failed) fail the node with a readable reason.

### Progress and cancel

- Compaction-type operations: progress from `CompactionManager.Compactions` of the keyspace,
  polled every 2 s while the JMX call blocks. Cancel calls `CompactionManager.stopCompaction(TYPE)`
  on the running node (COMPACTION, CLEANUP, SCRUB, UPGRADE_SSTABLES, GARBAGE_COLLECT) and skips the
  remaining nodes; flush cannot be stopped, cancel only skips the remaining nodes.
- Repair: `StorageService.repairAsync(keyspace, options)` (RepairOption keys `parallelism`,
  `primaryRange`, `incremental`, `jobThreads`, `columnFamilies`, `dataCenters`, `ranges`), live
  progress from the StorageService `progress` notifications of that command (`repair:<cmd>`,
  userData `{type, progressCount, total}`; ERROR/ABORT fail it, COMPLETE ends it). On 4.0+
  `getParentRepairStatus(cmd)` is polled every 10 s too, so a lost notification cannot hang the
  job. Command 0 = nothing to repair (e.g. replication factor 1). Sub-ranges run one repair each.
  Cancel = `forceTerminateAllRepairSessions` on the running node.
- Snapshots: `takeSnapshot(tag, {skipFlush}, entities)`, `clearSnapshot(tag, keyspaces)` (tag ""
  with `all`), list from `SnapshotDetails` (attribute on every version; 4.1+ adds creation and
  expiration time).
