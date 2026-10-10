# Runbook: GC log investigation

Analyses a node's garbage collector logs to explain pauses, heap pressure and full GCs. Panel:
**GC logs** (GCL-1 … GCL-4).

## When to use

- **Monitoring** shows `gc.pressure` or long GC pauses, latency spikes or a node marked down for
  a few seconds.
- A node's heap stays high, or the node ran out of memory.
- Before and after a GC tuning change.

## Before you start

- Read-only: loading logs changes nothing on the node.
- Loading from a node needs JMX (to find the configured log file) and SSH with read access to the
  log files. Without JMX, Studio searches the usual locations (`/var/log/cassandra/gc*`,
  `/opt/cassandra/logs/gc*`) and says so.
- GC logging must be enabled on the node (`-Xloggc:` on Java 8, `-Xlog:gc…` on Java 9+; Cassandra
  enables it by default).
- Or upload a log you already have: plain text, `.gz`, or a `.zip` of rotated files (up to 1 GB
  uncompressed).

## Steps in Studio

1. Open **GC logs**. Pick the node and click **Find GC logs**. Studio shows the Java version, the
   configured log path, the GC options and the files found (current and rotated, with sizes).
2. Select the files (a default selection fits the size cap), set the **Size cap (MB)** (default 200;
   the newest files win, the file that crosses the cap is read from its end) and click **Load**. A
   job copies the files (compressed when the node has gzip and base64).
3. **Summary & findings**: start with the findings, most severe first. Each has evidence, a hint
   and the JVM options to look at, and names the options file for the node's Java version
   (`jvm.options`, `jvm11-server.options`, `jvm17-server.options`). Then check pause statistics
   (p99, max), GC time %, full GCs and their causes, heap after GC and allocation and promotion rates.
4. **Charts**: find when pauses or heap growth happened. Zoom the charts to a time window and
   click **Analyse …** to recompute summary and findings for that window only (for example around an
   incident); **Whole log** goes back.
5. **Events**: the individual collections and phases, with cause, duration and heap before/after.
6. **Export JSON** (the report) or **Export CSV** (the events) for a ticket.

## Typical findings and what to do

| Finding | Usually means |
|---|---|
| Long pauses (> 500 ms; critical ≥ 2 s) | Heap too small or too large for the collector, humongous objects (G1), or a full GC |
| GC time ≥ 5 % (critical ≥ 10 %) | Allocation rate too high for the heap; check large partitions and tombstones (Diagnostics) |
| Full GC (metaspace / explicit / other) | Raise metaspace, disable explicit GC, or look for heap exhaustion |
| Heap too small (old gen ≥ 90 % after full GC) | Live data does not fit: raise `-Xmx` or reduce caches and memtables |
| Humongous allocations, evacuation failure, to-space exhausted (G1) | Large partitions or batches; raise region size or reserve |
| Concurrent mode failure, promotion failed (CMS) | Old gen fills before CMS finishes; start CMS earlier or move to G1 |
| Allocation stalls (ZGC), degenerated cycles (Shenandoah) | Heap too small for the allocation rate |
| Time to safepoint ≥ 100 ms | Long-running loops without safepoints, or the machine is swapping |
| CMS deprecated | Plan a move to G1 (CMS is removed in Java 14+) |

## Verify

- After a tuning change and a node restart, load the new log and compare: pauses, GC time and the
  findings should improve. Watch **Monitoring → Charts → GC time** for a day.

## Stop or roll back

- **Cancel** on the load job stops between files. Nothing changes on the node.
- The last 6 analyses per cluster are kept until you disconnect; **×** removes one.
