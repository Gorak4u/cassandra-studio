# Bulk unload and load API (BLK-1, BLK-2)

A DSBulk equivalent built into the engine: unload a table or a query to CSV or JSON lines,
and load CSV or JSON lines into a table. Both run as jobs (docs/api/jobs.md): the route
returns the `Job` (202) and the UI polls `/api/jobs/{jobId}`; cancel with
`POST /api/jobs/{jobId}/cancel`. Code: `engine/.../bulk`, routes `api/BulkRoutes.java`,
UI `ui/src/panels/bulk`.

Files are local to the machine the engine runs on (the desktop app: the user's computer).
A relative path is resolved under the user's Downloads folder (`XDG_DOWNLOAD_DIR`,
`~/Downloads`, else the home folder); `~` is expanded. **S3 / GCS targets are not supported
yet** (BLK-1 lists them; they are out of scope for this release), nor are BLK-3…6 (counts,
cluster-to-cluster copy, Studio Server jobs, sstableloader).

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/clusters/{id}/bulk/defaults` | | `{downloadsDir, separator}` |
| POST | `/api/clusters/{id}/bulk/unload` | Unload request | 202 `Job` (kind `bulk-unload`) |
| POST | `/api/clusters/{id}/bulk/load/preview` | Load request (keyspace/table optional) | Preview |
| POST | `/api/clusters/{id}/bulk/load` | Load request + `confirmed` / `confirmName` | 202 `Job` (kind `bulk-load`); 428 until confirmed |
| GET | `/api/clusters/{id}/bulk/jobs` | | `[{job, stats}]` recent bulk jobs, newest first |
| GET | `/api/clusters/{id}/bulk/jobs/{jobId}/stats` | | live `stats` of one job |

Errors: 400 `bad_request` with a message (unknown keyspace/table/column, bad option, file
missing, not a SELECT, counter table, primary key not mapped, ...), 409 `file_exists` when an
unload target exists and `overwrite` is not set, 403 `read_only` / 428 `confirmation_required`
from the ActionGuard for loads.

## Unload request

| Field | Default | Notes |
|---|---|---|
| `mode` | `table` | `table`: token-range parallel; `query`: one stream |
| `keyspace`, `table` | | required in table mode; `keyspace` is the query's keyspace in query mode |
| `columns` | all | table mode: a subset, in this order |
| `query` | | query mode: a SELECT (anything else is refused) |
| `path` | `<Downloads>/<ks>.<table>.csv` | `.jsonl` for JSON, `.gz` with gzip |
| `overwrite` | false | refuse an existing file unless set |
| `format` | `csv` | `csv` or `json` (JSON lines: one object per row) |
| `compression` | `none` | `none` or `gzip` |
| `header` | true | CSV header row with the column names |
| `delimiter` | `,` | one character, `\t` for tab |
| `nullString` | `""` | how a null is written; a real value equal to it is quoted |
| `timestampFormat`, `dateFormat` | ISO-8601 | Java `DateTimeFormatter` patterns |
| `timeZone` | `UTC` | for patterns without an offset |
| `blobFormat` | `hex` | `hex` (`0x…`) or `base64` |
| `consistency` | `LOCAL_ONE` | |
| `pageSize` | 5000 | 10…100000 |
| `concurrency` | 8 | token ranges read in parallel, 1…64 |
| `maxRows` | 0 (all) | stop after N rows |
| `timeoutMs` | 60000 | per page |

How it works: the ring's token ranges (driver `TokenMap`) are unwrapped and split evenly to at
least 8 per worker; each runs `SELECT cols FROM ks.t WHERE token(pk) > ? AND token(pk) <= ?`
(token-aware routing), paged by hand so a failed page is retried (3 attempts) from its paging
state without duplicating rows. Pages are formatted on the reading thread and appended to one
unordered writer (with `concurrency` 1 the file is in token order). A range that still fails is
written to `<stem>.errors.log` next to the file and the job ends FAILED with the count; rows
already written stay in the file. Without token metadata (custom partitioner) the table is read
in one stream.

Values: text raw; numbers and booleans as text; timestamps ISO-8601 (`2024-05-01T10:15:30.123Z`)
or the pattern; dates ISO; times `HH:mm:ss[.nnnnnnnnn]`; uuids, inet, durations (`1h30m`) in
their usual form; blobs `0x…`/base64; collections, UDTs, tuples and vectors as CQL literals
(driver codecs, e.g. `['a','b']`, `{street: 'x', zip: 1}`). JSON lines use JSON numbers,
booleans, arrays (list, set, tuple) and objects (map, UDT). Everything round-trips through load.

Unload is read-only and not guarded; on a PROD connection the job is audited (category `bulk`).

## Load request

| Field | Default | Notes |
|---|---|---|
| `keyspace`, `table` | | required (not for preview) |
| `path` | | an existing file |
| `format` | from the extension | `.json`, `.jsonl`, `.ndjson` (optionally `.gz`) = JSON lines, else CSV |
| `compression` | `auto` | `auto` (gzip by extension or magic bytes), `none`, `gzip` |
| `header`, `delimiter`, `nullString`, `timestampFormat`, `dateFormat`, `timeZone`, `blobFormat` | as unload | an unquoted field equal to `nullString` is null; a quoted one is the value |
| `mapping` | by name | `[{column, source}]`: table column ← file column (CSV header name, `c1…cN` without a header, or JSON key); columns not listed are not written. Default: header names matched to column names (exact, then ignoring case) |
| `ttlSeconds` / `ttlField` | none | `USING TTL n`, or TTL from a file column |
| `timestampMicros` / `timestampField` | none | `USING TIMESTAMP n`, or from a file column (epoch µs, or a timestamp) |
| `batchSize` | 32 | rows of the same partition per unlogged batch; 1 = one INSERT per row |
| `concurrency` | 16 | requests in flight, 1…256 |
| `rateLimit` | 0 (none) | rows per second |
| `maxErrors` | 100 | stop after more than N rejected rows; -1 = never |
| `dryRun` | false | validate only: read and convert every row, write nothing (not guarded) |
| `consistency` | `LOCAL_QUORUM` | |
| `timeoutMs` | 30000 | per request |

Conversion from text covers every CQL type: ints (also `1e3`, range-checked), varint, decimal,
float/double (`NaN`, `Infinity`), boolean (`true/false`, `yes/no`, `1/0`), uuid, timeuuid
(version 1 checked), timestamp (ISO-8601 with or without offset, `2024-01-02 03:04:05.000+0000`,
a date, epoch millis, or the pattern), date, time, inet (IP literals only, no DNS), blob
(`0x` hex, plain hex, base64), duration, and collections / UDTs / tuples / vectors as CQL
literals or JSON. **Counter tables are refused** (400): counters can only be incremented.
A null (empty) value is left unset (no tombstone) on protocol v4+; an empty primary key value
rejects the row.

Rows are read and converted on the job thread and written asynchronously by a sender thread
(bounded queue) with at most `concurrency` requests in flight; with `batchSize` > 1 rows are
grouped by routing key (partition) within a window and sent as UNLOGGED batches. Timeouts,
unavailable and overloaded errors are retried 3 times. Rows that fail conversion or writing are
**rejected**: the record goes to `<stem>.rejected.csv|.jsonl` (with the header; can be fixed and
re-loaded) and `line N: reason` to `<stem>.rejected.log`, next to the source (or in Downloads
when that folder is not writable). The job fails once `maxErrors` is exceeded.

Guard: a load is checked by the ActionGuard (category `bulk`) before the job starts. The preview
is the exact `INSERT … [USING TTL … AND TIMESTAMP …]`, the source (format, size, estimated
rows), the mapping and the write options; warnings say that rows are upserted and which columns
are not loaded. The job is audited (category `bulk`).

## Preview response

`{path, format, gzip, sizeBytes, estimatedRows, fileColumns, sampleRows (first 20, null as
null), tableColumns [{name, type, kind: partition_key|clustering|regular}], mapping (suggested),
error (counter table, else null)}`. `estimatedRows` is exact for files up to 1 MiB, else
extrapolated from the first MiB.

## Stats

`{kind, path, target, rowsRead, rowsWritten, rejected, bytes, totalBytes, rangesDone,
rangesFailed, rangesTotal, estimatedRows, rowsPerSecond, elapsedMs, errorFile, rejectFile,
dryRun}`. For unload `bytes` is the file size so far; for load it is the bytes read from the
source and `totalBytes` its size (the job's progress is their ratio). The finished job's
`result` holds the same fields (load adds `rejectSamples`, the first 20 reasons).

## Throughput (shared test-env, 200k-row table `(id int PRIMARY KEY, name text, v double, ts timestamp)`)

Measured by `it/BulkIntegrationTest.throughput` (load concurrency 64, unload concurrency 16),
two runs each; the 4-core host also ran the other tracks' clusters, repairs and tests, so
numbers vary run to run.

| Cluster | Load rows/s | Unload rows/s |
|---|---|---|
| acme-core (4.1, 3 nodes, RF 1 in dc_east) | 4,000 – 7,100 | 28,800 – 88,400 |
| legacy-311 (3.11, 1 node) | 11,700 – 18,500 | 67,700 – 140,700 |
| secure-50 (5.0, TLS + login, 1 node) | 11,700 – 20,400 | 56,700 – 145,400 |

The 4.1 load stays under the ~10k rows/s target on the busy host (load average ~25 during the
first run); unload is above the ~20k rows/s target everywhere.
