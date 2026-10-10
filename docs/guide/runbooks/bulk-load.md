# Runbook: bulk load

Loads a CSV or JSON-lines file from your computer into a table, like DSBulk load. Panel: **Bulk →
Load** (BLK-2).

## When to use

- To import data exported by [bulk unload](bulk-unload.md), DSBulk, cqlsh `COPY TO` or another
  system.
- To restore a table's rows from an export.

## Before you start

- **Rows are upserted**: a row with an existing primary key overwrites the columns you load.
  Columns you do not map are left unchanged.
- Counter tables cannot be loaded (counters can only be incremented).
- Take a [snapshot](snapshots.md) of the table first if you may need to go back.
- Writes load the cluster: start with the defaults (batch size 32 rows of the same partition,
  concurrency 16) and set a **Rate limit** (rows/s) on busy or PROD clusters. Default consistency
  is `LOCAL_QUORUM`.
- The connection must not be read-only.

## Steps in Studio

1. Open **Bulk**, tab **Load**. Enter the **File on this computer** (CSV, or JSON lines with one
   object per line; `.gz` is read as gzip) and the format options (header, delimiter, *Null as*,
   date and blob formats).
2. Click **Preview file**: Studio shows the file's columns, the first rows and the estimated row
   count, and suggests a mapping (file columns matched to table columns by name).
3. Choose the keyspace and table; check the **mapping** (each table column ← a file column, or not
   loaded). Every primary key column must be mapped.
4. Optional: **TTL** (fixed seconds or from a column), **Write timestamp** (fixed microseconds or
   from a column), batch size, concurrency, rate limit, **Max errors** (stop after more than N
   rejected rows; -1 = never), consistency.
5. Click **Validate (dry run)**: every row is read and converted, nothing is written. Fix the file
   or the mapping until the dry run has no rejects you do not expect.
6. Click **Load** and confirm.

## What the confirmation shows

The exact statement and the load plan, for example:

```text
INSERT INTO shop.orders (id, customer, total, created) VALUES (?, ?, ?, ?) USING TTL 86400;
-- from /home/me/Downloads/shop.orders.csv (csv, 12.4 MiB, ~200,000 rows);
-- mapping: id <- id, customer <- customer, total <- total, created <- created;
-- unlogged batches of up to 32 rows of the same partition, 16 in flight, no rate limit, consistency LOCAL_QUORUM, stop after 100 errors
```

Warnings: "About N rows will be written.", "Existing rows with the same primary key are overwritten
(INSERT is an upsert).", and "Not loaded (left unchanged): …" for unmapped columns. On PROD the
connection name must be typed.

## Verify

- **Current job** shows rows read, written and rejected, bytes read of the file size, and rows per
  second. The job ends SUCCEEDED, or FAILED when more than *Max errors* rows were rejected.
- Rejected rows are written to `<file>.rejected.csv` (or `.rejected.jsonl`) with the header, and the
  reasons (`line N: reason`) to `<file>.rejected.log`, next to the source file (or in Downloads when
  that folder is not writable). Fix them and load the rejects file.
- Spot-check rows in the **Query** panel, for example `SELECT … WHERE id = …`.

## Stop or roll back

- **Cancel** stops the load. Rows already written stay written.
- To undo a load: restore the table from the snapshot you took before (outside Studio in 1.0), or,
  if the rows were new, delete them by key. A TTL set on the load makes rows expire by themselves.
