# Studio's own state API

Studio's local data: the remembered layout (NFR-UX), backup and restore of its settings
(NFR-DATA), audit export (NFR-AUD) and local diagnostics (NFR-OBS). Routes live in
`engine/.../api/StudioRoutes.java`; the UI client is `ui/src/lib/studioApi.ts`. Nothing here is
sent anywhere but the local engine.

| Method | Path | Body / query | Returns |
|---|---|---|---|
| GET | `/api/ui-state` | | the stored UI state, `{}` when none |
| PUT | `/api/ui-state` | a JSON object, at most 256 KB | 204 |
| POST | `/api/studio/backup` | `{}` or `{includeSecrets: true, passphrase}` (≥ 8 characters) | the backup file (JSON, `Content-Disposition: attachment`) |
| POST | `/api/studio/restore` | `{file, dryRun: true}` | `{folders, connections, scripts, settings: {added, conflicting}, hasSecrets}` |
| POST | `/api/studio/restore` | `{file, conflict: skip \| replace \| keep_both, passphrase?}` | `{folders, connections, scripts, settings, skipped, secrets, notes[]}` |
| GET | `/api/audit/export?format=csv\|json&connectionId=&q=&since=&limit=` | same filters as `GET /api/audit` | CSV (RFC 4180, header row) or a JSON array; up to 100,000 rows, newest first; `X-Row-Count` header |
| GET | `/api/diagnostics` | | `{studioVersion, dbVersion, os, java, uptimeSec, heapUsedMb, heapMaxMb, threads, processors, secretStore, savedConnections, crashLog, recentErrors[]}` |
| POST | `/api/diagnostics/ui-error` | `{message, stack?, componentStack?}` | 204 (at most 30 recorded per minute) |

## Remembered layout (NFR-UX)

Stored in the `settings` table under `ui.state` (not in browser storage: the engine port, and so
the page origin, changes every launch). Shape (`UiState` in `studioApi.ts`):

```json
{"v": 1, "open": ["<connection id>", ...], "active": "<connection id> | audit | null",
 "connected": ["<ids connected when saved>"], "workspaces": {"<id>": {"tab": "monitoring"}},
 "collapsed": ["<folder id>"], "sidebarWidth": 320}
```

The UI saves it 400 ms after any change and when the page is hidden. On start, ids of deleted
connections and folders are dropped. Restored DEV/TEST/STAGING tabs reconnect as when they were
opened; a restored **PROD** tab reconnects only when it was connected when Studio closed,
otherwise it shows "Connect" and waits (a launch never opens a production session by itself).
The UI layout is not part of settings backups.

## Settings backup and restore (NFR-DATA)

File format `cassandra-studio-settings`, version 1: `folders`, `connections` (never with
secrets), `scripts` (saved CQL scripts with content), `settings` (every key except `ui.*`, e.g.
monitoring thresholds, backup and diagnostics settings per connection) and, only when a
passphrase was given, `secrets`: `{kdf: "PBKDF2WithHmacSHA256/AES-256-GCM", iterations, salt,
data}` with every stored password of every connection encrypted with a key derived from the
passphrase (210,000 iterations).

Restore runs in one transaction (a bad file or a wrong passphrase changes nothing) and keeps
ids, so per-connection settings (`<feature>.<name>/<connection id>`) keep pointing at their
connection. For an item whose id already exists: `skip` keeps the local one, `replace`
overwrites it, `keep_both` adds the restored one under a new id with "(restored)" in its name
(its per-connection settings follow the new id; a setting that already exists is kept). Files of
a newer format version are refused. The older connections-only export
(`/api/connections/export`) is still accepted by `/api/connections/import`.

## Database versions (NFR-DATA)

`studio.db` carries numbered migrations in `schema_version`. Before migrating an existing file
the engine copies it (`VACUUM INTO`, consistent with pending WAL pages) to
`<data dir>/backups/studio-v<old version>-<UTC time>.db`, keeping the last 5. A database
written by a newer Studio is detected through an immutable read-only probe and refused with
`STUDIO_ENGINE_ERROR ... upgrade Studio instead of downgrading. The database was not changed.`
on stderr (exit code 2): no byte of the file, no journal mode, no -wal/-shm file is touched.

## Crash and diagnostics log (NFR-OBS)

`<data dir>/logs/crash.log` (rolls over at 1 MB, one previous file kept): uncaught exceptions of
every engine thread, unexpected API errors (500s, with stack trace) and UI errors (window
errors, unhandled promise rejections and React render errors caught per view, with component
stack). Passwords, tokens, secrets and `Authorization` headers are scrubbed before writing.
"Studio data → Copy diagnostics" copies versions, OS, Java, engine memory/threads and the last 20
errors (no hosts, names or secrets) to the clipboard. Nothing is uploaded.
