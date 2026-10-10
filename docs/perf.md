# Performance and scale measurements

NFR-PERF (start < 5 s, 50+ clusters, 100+ nodes, negligible load on Cassandra), NFR-SCALE (500
nodes in one cluster, 100 saved clusters, < 200 ms interactions, < 2 s to open a dashboard) and
NFR-RELI (a lost or hung node never freezes the UI). Measured 2026-10-10 on the shared 4-core
test host (load average 2–11 while measuring), Java 21, headless Chromium.

How to repeat:

| What | Command |
|---|---|
| Engine, 500 nodes | `cd engine && ./gradlew --offline test --tests '*MonitoringScaleTest'` (runs with the unit tests; prints `PERF` lines) |
| Hung / unreachable nodes | `./gradlew --offline test --tests '*NodeHangTest'` |
| UI, 500 nodes and 100 connections | `cd ui && npx vite build && node tests/perf.mjs` (mocked API; fails over budget) |
| Startup and remembered layout | `node tests/restore-live.mjs <engine bin> <ui/dist> <port> <scratch data dir>` |
| Bulk throughput (test-env) | `./gradlew --offline test -Pintegration --tests '*BulkThroughputIntegrationTest'` |

## Engine: a 500-node cluster (`perf/MonitoringScaleTest`)

500 fake nodes in 5 DCs, each its own in-process MBeanServer with the full Cassandra 4.1 metric
set, 20 ms simulated JMX connect latency per node, MonitoringService at the default 10 s interval.

| Measure | Result | Budget |
|---|---|---|
| Poll duration (6 polls) | avg 192–203 ms, max 204–243 ms; first poll 212–390 ms | within the 10 s interval |
| CPU | 268–310 ms per poll = 2.7–3.1 % of one core | |
| Threads | peak 15 (node reads on virtual threads) | no thread per node |
| JMX | 500 sessions per poll (one per node), at most 64 reads at once | NFR-PERF |
| Heap after GC | 127–132 MB, including the 500 fake MBean servers | desktop runs with -Xmx1g |
| 24 h history, 500 nodes × 19 metrics | 17.1 M points in ~123 MB (was ~380 MB: minute points are now floats in fixed per-minute slots, raw buffers grow by half) | |
| 24 h series, one metric, all nodes | 56–97 ms, 870,000 points, 16.8 MB JSON | |
| same with `maxPoints=400` (LTTB) | 72 ms, 200,000 points, 3.9 MB JSON; the charts ask for 30,000 / nodes = 60 per node at 500 | |

## Hung and unreachable nodes (NFR-RELI)

Simulated without stopping test-env containers: a port that accepts TCP and never answers (a
hung process or black-holed host), a refused port, and fake nodes that hang on connect.

| Scenario | Before | After |
|---|---|---|
| 100 of 500 nodes hang (fakes, 9 s each): healthy nodes read in later polls | **0 of 400** (hung reads held all 64 read slots) | 400 of 400; every poll ends at its 8 s node timeout (8.02–8.08 s) |
| Ring view with hung nodes | 15 s per hung node tried, one after another | 3–47 ms (nodes read fine by the last poll first; 15 s budget, at most 3 nodes) |
| Real JMX client to a hung port: poll / tables / ring | — | 8.0 s / 5.0 s / 5.0 s |
| Cluster whose CQL port hangs: connect, test, info, schema, ops view, snapshots (sent together) | requests queued one behind another on one global connect lock (snapshot did not answer in 90 s) | all 12.8–14.5 s (one shared attempt); monitoring snapshot 12.9 s; status 5–8 ms |
| Refused CQL port, same routes | | 2.8–4.8 s |
| Rest of the API meanwhile (`GET /api/connections`) | blocked behind the lock for other clusters' connects | 14–64 ms |

Fixes: a read slot is given back when the poll gives up on a hung read, nodes that failed last
time are read after the healthy ones (fair semaphore); ring and per-table views have one overall
budget (15 s / 20 s); `SessionManager` connects outside its lock, with one shared attempt per
connection; the first `snapshot` waits at most 25 s (then 503) and the poll loop pauses at least
1 s between polls so a slow connect cannot starve requests.

## UI: 500 nodes and 100 saved connections (`ui/tests/perf.mjs`)

Built UI in Chromium against a mocked engine: 100 connections in 30 folders, a 500-node cluster
(5 DCs) with 24 h of history. Times run from the click until the DOM shows the result and the page
is idle again (chart drawing included).

| Step | Before | After | Budget |
|---|---|---|---|
| Open the app (tree with 100 connections) | 277 ms | 220–318 ms | 2 s |
| Tree: collapse a folder / filter | 113 / 50 ms | 79–134 / 26–67 ms | 200 ms |
| Open the 500-node cluster | 72 ms | 51–181 ms | 2 s |
| Monitoring health view | 268 ms | 195–600 ms | 2 s |
| Nodes table, 500 rows | 961 ms | 152–520 ms | 2 s |
| Nodes table: sort | **533 ms** | 101–167 ms | 200 ms |
| Charts, 15 min | **4,750 ms** | 1,317–1,530 ms | 2 s |
| Charts, switch to 24 h | **2,703 ms** (36.7 MB of series) | 1,355–1,541 ms (18.4 MB) | 2 s |
| Charts: hide one node | **13,528 ms** | 87 ms | 200 ms |
| Ring (5 DCs) | **19,272 ms** | 501–625 ms | 2 s |
| Operations: node picker with 500 nodes | 228 ms | 154–183 ms | 2 s |
| Node picker: select all / toggle one | 70 / 64 ms | 52–100 / 51–84 ms | 200 ms |
| Back to the Monitoring tab | 93 ms | 93–175 ms | 200 ms |

Fixes: charts of more than 24 nodes draw per-datacenter max (solid) and mean (dotted) on a
240-slot grid instead of 1,000 lines each (hide nodes to get individual lines back); series are
fetched with `maxPoints`; off-screen charts are not redrawn until scrolled into view; chart cards
are memoised and node-chip toggles defer the redraw; tables with more than 150 rows (nodes,
ownership) render only the rows in view.

## Startup (NFR-PERF < 5 s)

| Measure | Result |
|---|---|
| Engine process start to `STUDIO_ENGINE_READY` (installed distribution) | 1.0–1.4 s |
| Engine start to UI showing restored tabs (restart with an existing database) | 1.6 s |
| Electron window to usable UI (earlier measurement, docs/STATUS.md) | ~2 s |

## Bulk load and unload (acme-core 4.1, 200k rows, keyspace `t3h_perf`)

| Load (default concurrency) | Load (concurrency 64) | Unload |
|---|---|---|
| 7,964 rows/s with the old default of 16 in flight | 20,722–22,295 rows/s | 52,946–144,214 rows/s |

Load was latency-bound by its default of 16 requests in flight, not by the engine; the default is
now 64: 15,198 rows/s on the first (cold) run. Earlier numbers in docs/api/bulk.md.
