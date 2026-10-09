# Jobs API (Phase 3 contract)

Long-running tasks (repair, compaction, snapshots, backups, bulk load/unload, toppartitions
sampling ...) run as jobs: the feature route starts the job and returns the `Job`; the UI polls it
(`components/JobProgress.tsx` does this) and can cancel it.

Shapes: `engine/.../jobs/Job.java` = `ui/src/lib/jobsTypes.ts`.

| Method | Path | Returns | Notes |
|---|---|---|---|
| GET | `/api/jobs?connectionId=` | `Job[]` | Newest first; omit `connectionId` for all. The newest 200 finished jobs are kept, in memory. |
| GET | `/api/jobs/{jobId}` | `Job` | Poll about once a second until `state` is SUCCEEDED, FAILED or CANCELLED. |
| POST | `/api/jobs/{jobId}/cancel` | `Job` | 409 `not_cancellable` when the job cannot stop safely. Runs the job's cancel action (e.g. JMX forceTerminateAllRepairSessions), then interrupts it. |

## For feature code (engine)

```java
// 1. Check the action first: read-only connections refused, PROD needs the typed name (428 otherwise).
engine.guard.check(cfg, new ActionGuard.Action("ops", "Flush shop on 10.0.0.1",
        List.of("nodetool -h 10.0.0.1 flush shop"), List.of(), false, "10.0.0.1"), RouteSupport.confirmation(body));
// 2. Start the job; the job runner audits start and end under the given category.
Job job = engine.jobs.submit(new JobService.Spec(id, "flush", "Flush shop on 10.0.0.1", "10.0.0.1", "ops", true),
        ctx -> {
            ctx.onCancel(() -> ...);           // optional: how to stop it on the node
            ctx.progress(0.5, "flushing");     // 0..1 or null
            ctx.log("flushed shop.orders");
            ctx.checkCancelled();              // between steps
            return Map.of("tables", 1);        // result, any JSON-able value
        });
ctx.status(202).json(job);
```

`auditCategory` null means the job is not audited (read-only tasks such as sampling or exports).

## Shared services for Phase 3 features (`Engine`)

- `engine.opsJmx`: `JmxAccess` with no read timeout, for blocking operations (repair, compaction,
  cleanup, snapshots). `engine.jmx` (30 s read timeout) is for reads.
- `engine.shell`: `NodeShell.exec(cfg, secrets, host, command, timeout[, maxBytes])` runs a command
  on a node over SSH with the connection's SSH settings, one cached session per node. Quote
  arguments with `NodeShell.quote`.
- `engine.topology.info(id)`: nodes with address, DC, rack, version and state (driver view).
- `engine.secretsFor(id)`: the connection's stored secrets for JMX and SSH calls.
- `engine.onClose(...)`, `engine.onDisconnect(id -> ...)`: lifecycle hooks for feature services.
