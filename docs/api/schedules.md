# Schedules API (SRV-5)

Recurring jobs: repair, backup, verification, reports. Each feature registers its schedule **types**
with `engine.schedules.register(type, ScheduledTask)`; every due run becomes an ordinary job
(`docs/api/jobs.md`) with progress, cancel, log and audit entry. In the desktop app schedules run while
Studio is open; Studio Server runs them unattended, and with several server instances only the leader
fires them.

## Schedule

| Field | Meaning |
|---|---|
| `id` | Blank on create |
| `connectionId` | The cluster it runs against (null for Studio-wide types) |
| `type` | A registered type, see `GET /api/schedules/types` |
| `name` | Shown in lists and in the audit log |
| `everyMinutes` | Interval, at least 5 (1440 = daily, 10080 = weekly) |
| `atTime` | Optional `HH:mm` local time the runs are aligned to |
| `windowStart`, `windowEnd` | Optional `HH:mm` window; may wrap midnight. A run due outside it waits for the next interval |
| `enabled` | Disabled schedules keep their settings and history but never fire |
| `params` | The type's own settings (keyspace, provider, retention, ...) |
| `nextRunMs`, `lastRunMs`, `lastOutcome`, `lastJobId` | Read-only state |

A run that comes due while the previous run of the same schedule is still going is recorded as
`SKIPPED`, not queued.

## Routes

| Route | |
|---|---|
| `GET /api/schedules?connectionId=` | List |
| `GET /api/schedules/types` | Registered types |
| `GET /api/schedules/{id}` | One schedule |
| `PUT /api/schedules` | Create or replace. Body: the schedule (or `{schedule, confirmed, confirmName}`). Confirmed like the job itself: 428 until confirmed, typed connection name on PROD; read-only connections are refused |
| `DELETE /api/schedules/{id}` | Delete with its run history |
| `POST /api/schedules/{id}/run` | Run now, regardless of window (same confirmation) |
| `GET /api/schedules/{id}/runs?limit=` | Run history, newest first: `STARTED`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `SKIPPED`, `UNKNOWN` (job lost to a restart) |

## Alerts hub

`engine.alerts` (`AlertHub`) takes `StudioAlert(connectionId, connectionName, environment, source, key,
severity, title, detail, atMs)` from any source (health rules, repair coverage, backup age, failed
schedules) and passes on **changes** only, to notifiers (ALR-4 desktop notifications, ALR-5 routing).
Sources may publish their current state on every check; a GREEN clears an earlier YELLOW or RED with
the same connection, source and key.
