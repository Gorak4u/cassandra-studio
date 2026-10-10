# Runbook: bulk unload

Exports a table or a query to a CSV or JSON-lines file on your computer, like DSBulk unload.
Panel: **Bulk → Unload** (BLK-1).

## When to use

- To export data for analysis, a migration, or a copy before a risky change to a table.
- To produce a file that [bulk load](bulk-load.md) can read back (every CQL type round-trips).

## Before you start

- Unload is read-only and needs no confirmation (on a PROD connection the job is still audited).
- It reads the whole table: on a large table this adds read load. Lower **Concurrency** (token
  ranges read in parallel, default 8) and use consistency `LOCAL_ONE` (the default) on busy clusters.
- Disk space on your computer: about the table's data size, less with gzip.
- Files are written on the machine where Studio runs. S3 and GCS targets are not supported in 1.0.

## Steps in Studio

1. Open **Bulk**, tab **Unload**.
2. Choose **Table** mode (keyspace, table, optionally a subset of columns) or **Query** mode (a
   `SELECT`; anything else is refused; read in one stream).
3. Set the **File on this computer**. The default is `<Downloads>/<keyspace>.<table>.csv`
   (`.jsonl` for JSON, `.gz` with gzip). A relative path is under Downloads. Tick overwrite to
   replace an existing file.
4. Choose **Format** (CSV or JSON lines) and **Compression**. For CSV: header, delimiter (`\t` for
   tab), *Null as*. Optional: timestamp and date formats (Java patterns; empty = ISO-8601), time
   zone, blob format (hex or base64).
5. Advanced: consistency, page size, concurrency, **Max rows** (stop after N rows).
6. Click **Unload**.

## What the confirmation shows

No confirmation: unload does not change the cluster. The job starts at once.

## Verify

- **Current job** shows rows read and written, bytes, token ranges done and failed, and rows per
  second. The job ends SUCCEEDED with the file path.
- Open the file, or count lines: one line per row (plus the header for CSV).
- If some token ranges failed after 3 attempts, the job ends FAILED with the count; the failed
  ranges are listed in `<file>.errors.log` next to the file. Rows already written stay in the file.

## Stop or roll back

- **Cancel** stops the unload; the partial file stays on disk. Delete it if you do not need it.
- Nothing changes in the cluster, so there is nothing to roll back.
