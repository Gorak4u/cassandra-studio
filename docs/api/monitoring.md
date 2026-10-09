# Monitoring API (Phase 2 contract)

Shapes: `engine/.../metrics/MonitoringModel.java` = `ui/src/lib/monitoringTypes.ts`.
All routes need the engine token like every other `/api` route. `{id}` is the connection id.
Errors use the standard `{error, message, details}` body.

| Method | Path | Returns | Notes |
|---|---|---|---|
| POST | `/api/clusters/{id}/monitoring/start` | `AccessStatus` | Body `{ "intervalSec": 10 }` (min 2, default 10). Idempotent. Polling only runs for started clusters (NFR-PERF). |
| POST | `/api/clusters/{id}/monitoring/stop` | 204 | Also stopped on disconnect. |
| GET | `/api/clusters/{id}/monitoring/status` | `AccessStatus` | Per-node reachability and route; drives the "JMX not reachable" banner. |
| GET | `/api/clusters/{id}/monitoring/snapshot` | `ClusterSnapshot` | Latest poll. 409 `{error:"not_started"}` if monitoring is not started. |
| GET | `/api/clusters/{id}/monitoring/series?metric=&node=&fromMs=&toMs=` | `Series` | `metric` from `SERIES_METRICS`. `node` optional (address; omit = all nodes). Default window: last 15 min. History is kept in memory per cluster for 24 h (MON-3), 1 point per poll, downsampled to 1/min after 1 h. |
| GET | `/api/clusters/{id}/monitoring/ring?keyspace=` | `Ring` | Tokens and ownership from StorageService; effective ownership needs a keyspace (defaults to the first non-system one). |
| GET | `/api/clusters/{id}/monitoring/tables?keyspace=` | `TableMetrics[]` | MON-18. Omit keyspace = all non-system tables. Read on request, not every poll. |
| GET | `/api/clusters/{id}/monitoring/alerts` | `Alert[]` | Active alerts (ALR-1). |
| PUT | `/api/clusters/{id}/monitoring/thresholds` | thresholds JSON | ALR-1 per-cluster overrides; GET returns effective values. |

## Health rules (ALR-1) and defaults

| Rule id | Level | Default |
|---|---|---|
| `node.down` | RED | node state not UN |
| `node.unreachable` | YELLOW | JMX/exporter read failed (node may be fine) |
| `schema.disagreement` | YELLOW | more than one schema version among UP nodes |
| `heap.high` | YELLOW / RED | heap used > 85 % / 95 % of max |
| `gc.pressure` | YELLOW / RED | GC time > 10 % / 25 % of wall time between polls |
| `dropped.messages` | YELLOW | any dropped-message counter increased since the previous poll |
| `client.timeouts` | YELLOW | read/write timeouts or unavailables increased since the previous poll |
| `compaction.backlog` | YELLOW | pending compactions > 100 |
| `threadpool.blocked` | YELLOW | any pool with blocked > 0, or all-time-blocked increased |
| `disk.usage` | YELLOW / RED | a data dir > 80 % / 90 % full |
| `load.imbalance` | YELLOW | a node's load > 1.5 × DC average (DC with 2+ nodes) |
| `hints.backlog` | YELLOW | hints in progress > 0 for 3 consecutive polls |

Overall health = worst alert level; GREEN when there are none. Reasons = alert messages.

## Version and GC awareness (MON-2)

The metric catalog maps logical metrics to MBeans per Cassandra major version
(3.11, 4.0, 4.1, 5.0) and per collector (CMS/ParNew, G1, ZGC, Shenandoah, Parallel).
Unknown or missing MBeans give `null`, never an error, so newer versions degrade gracefully
(NFR-COMPAT). Contract tests read every catalog entry from a real node of each version.
