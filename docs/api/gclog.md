# GC log analysis API (GCL-1..4)

GCViewer-style analysis of GC log files: load them from a node over SSH or upload a file, then
get a report with summary statistics, chart data and tuning findings for the whole log or a time
window. Read-only towards the cluster (no ActionGuard); fetching from a node runs as a job
(docs/api/jobs.md). Engine: `engine/.../gclog/*`, routes `api/GcLogRoutes.java`; UI types
`ui/src/panels/gclog/gclogTypes.ts`.

| Method | Path | Body / query | Returns |
|---|---|---|---|
| GET | `/api/clusters/{id}/gclog/files?node=ADDR` | | `Discovery` |
| POST | `/api/clusters/{id}/gclog/fetch` | `{node, paths: [..], maxMB?, javaVersion?}` | 202 `Job`; result `{analysisId, events}` |
| POST | `/api/clusters/{id}/gclog/upload?name=FILE` | raw file bytes (text, .gz or .zip) | 201 `AnalysisInfo` |
| GET | `/api/clusters/{id}/gclog/analyses` | | `AnalysisInfo[]`, newest first |
| GET | `/api/clusters/{id}/gclog/analyses/{aid}` | `fromX`, `toX` (optional, seconds on the report's x axis) | `GcReport` |
| DELETE | `/api/clusters/{id}/gclog/analyses/{aid}` | | 204 |

## Discovery (GCL-1)
`node` must be one of the cluster's node addresses. The engine reads the JVM arguments over JMX
(`java.lang:type=Runtime` InputArguments and SpecVersion; SSH_TUNNEL or DIRECT methods) to find
the log file: `-Xloggc:<file>` on Java 8, `-Xlog:gc...:file=<file>` (or `-Xlog:gc:<file>`) on 9+;
`%p`/`%t` become wildcards. It then lists, over SSH, `<file>*` plus `/var/log/cassandra/gc*` and
`/opt/cassandra/logs/gc*` (deduplicated). When JMX cannot be read, `note` says why and only the
usual paths are searched.

```json
{"node": "10.231.42.11", "javaVersion": "11", "configuredPath": "/opt/cassandra/logs/gc.log",
 "gcOptions": ["-XX:+UseConcMarkSweepGC", "-Xlog:gc=info,...:file=/opt/cassandra/logs/gc.log:..."],
 "searched": ["/opt/cassandra/logs/gc.log*", "/var/log/cassandra/gc*", "/opt/cassandra/logs/gc*"],
 "files": [{"path": "/opt/cassandra/logs/gc.log", "sizeBytes": 3564658, "modifiedMs": 1791589538000, "current": true}],
 "compressed": true, "note": null}
```

## Fetch
Paths must be absolute, plain characters, with "gc" in the file name (400 otherwise); at most 100.
Files are re-listed, read oldest first, and capped at `maxMB` (default 200, 1..1024): the newest
files win, the file that crosses the cap is read from its end (`truncated: true` in the report's
source), older ones are skipped (job log says so). Transfer is `gzip -c | base64` when the node
has both (`compressed`), else plain `cat`/`tail -c`; a cut-off compressed transfer is retried
once. The job is cancellable between files. Errors: SSH failures fail the job with the reason;
a log without GC events fails with "No GC events found".

## Upload
The request body is the file itself (`Content-Type: application/octet-stream`), detected by
content: plain text, gzip, or a zip of logs (also `.gz` inside; `__MACOSX` and dot files
skipped). Streamed, limit 1 GB uncompressed (413). 400 for an empty or unreadable file, 422
`not_a_gc_log` when nothing was recognised.

## Analyses
The last 6 parsed logs per connection are kept in memory (dropped on disconnect). A report for a
window (`fromX`/`toX`) recomputes everything for that window.

## GcReport
- `log`: `format` (java8, unified, mixed), `collector` (G1, CMS, Parallel, Serial, ZGC, ZGC
  (generational), Shenandoah), `jvmVersion`, `javaMajor`, `jvmFlags` (Java 8 header), `heapMaxK`,
  `regionSizeK`, `timeAxis` (`wall` when every event has a date: x = seconds since `startTs`;
  else `uptime`: x = JVM uptime seconds), `startX`, `endX`, `lines`, `eventCount`, `warnings`.
- `summary` (GCL-3): `pauses` (count, total, avg, min, max, p50/p95/p99 ms), `histogram`,
  `byType`, `gcTimePct`, `throughputPct`, `fullGcCount`, `fullGcCauses`, `concurrent` (phases),
  `humongousAllocations`, `humongousPeakK`, `toSpaceExhausted`, `evacuationFailures`,
  `concurrentModeFailures`, `promotionFailures`, `degenerated` (Shenandoah), `stalls` (ZGC),
  `safepoints` (count, total, max, time to safepoint, top operations when logged), `heap`
  (max, peak used, peak/avg after GC, peak old after GC, peak metaspace; KiB), `allocation` and
  `promotion` (avg/peak MB/s, total MB), `causes`.
- `series` (GCL-2): `[x, value]` per `bucketSec` (1 s .. 1 week, at most 240 buckets):
  `gcTimePct`, `allocationMBs`, `promotionMBs`, `safepointMs`.
- `events`: every collection and phase (`GcEvent`: x, gcId, uptime, ts, kind pause/concurrent,
  type, category young/mixed/full/phase/concurrent, cause, durationMs, heap/young/old/humongous/
  metaspace before/after/total in KiB, flags). At most 20 000 (`eventsTruncated`): notable
  pauses (≥ p99, flagged, full) are always kept, the rest sampled evenly; the summary uses all.
- `findings` (GCL-4), most severe first: `{id, severity critical|warning|info, title, detail,
  evidence[], hint, options[], file, atX}`. Rules: `long-pauses` (> 500 ms; critical ≥ 2 s),
  `gc-time` (≥ 5 %, critical ≥ 10 %, windows ≥ 60 s), `full-gc-metaspace`, `full-gc-explicit`,
  `full-gc`, `heap-too-small` (old gen ≥ 90 % after a full GC or after 3+ collections),
  `humongous`, `evacuation-failure`, `concurrent-mode-failure`, `promotion-failed`,
  `premature-promotion` (promoted ≥ 25 % of allocated), `metaspace-threshold`, `degenerated`,
  `allocation-stalls`, `time-to-safepoint` (≥ 100 ms), `frequent-young-gc`, `small-heap`
  (< 2 GB), `cms-deprecated`; `healthy` when none applies. `file` names the Cassandra options
  file for the Java version (jvm.options / jvm11-server.options / jvm17-server.options).

## Parser coverage
Java 8 `-XX:+PrintGCDetails` with date and/or uptime stamps, PrintTenuringDistribution,
PrintHeapAtGC and PrintGCApplicationStoppedTime noise, ParNew/CMS (phases, concurrent phases
printed inside other records, concurrent mode failure, promotion failed), Parallel, Serial, G1
(young/mixed, humongous, to-space exhausted, concurrent phases, remark/cleanup, Full GC detail).
Java 9+ unified logging with any decorations (time, utctime, uptime, uptimemillis/nanos, level,
tags, pid, tid) for G1 (regions, Evacuation Failure), CMS (Java 11), Parallel, Serial, ZGC
(cycles, pauses, allocation stalls; generational on 21) and Shenandoah (pauses, degenerated
cycles). Unknown lines are counted and skipped. Fixtures: `engine/src/test/resources/gclog/`
(test-env nodes and JDK 21 runs, trimmed).
