# Studio Server API (SRV-1, SRV-6, SRV-7)

Studio Server serves **the same REST API as the desktop engine** (every `docs/api/*.md`), so the UI works
against a server unchanged except for the base URL and the token. What differs:

| | Desktop engine | Studio Server |
|---|---|---|
| Listens on | 127.0.0.1, random port | `server.host`:`server.port` (default 8443), HTTPS with `tls.keystore` |
| Token | One per launch, printed in `STUDIO_ENGINE_READY` | Named API tokens per person (`studio-server token add <user>`), `cst_...` |
| `Host` header check | Only 127.0.0.1 / localhost (DNS-rebinding guard) | None (reached by DNS names and through proxies) |
| Acting user (audit, jobs) | The OS account | The token's user, for the whole request (`audit.Actor`) |
| CORS | Off (`--dev-cors` for Vite) | `server.corsOrigins` |

Authentication is the same header: `Authorization: Bearer <token>` (or `?token=` for downloads). A missing,
unknown or revoked token gets `401 {"error":"unauthorized"}` with `WWW-Authenticate: Bearer`. Revoked
tokens stop working within 5 seconds on every instance. There are no roles (owner decision Q-1): every
user may do everything; ActionGuard confirmations (428, typed name on PROD, read-only connections) and the
audit log apply exactly as on the desktop.

`GET /api/info` returns `actor` = the token's user. Jobs keep the user who started them; schedules run as
`schedule: <name>`.

## Server-only routes

| Method | Path | Auth | Returns |
|---|---|---|---|
| GET | `/api/health` | none | `200 {status:"UP", role:"leader"\|"standby", instanceId, version}` while this instance reaches its store; `503 {status:"DOWN"...}` otherwise. For load-balancer health checks. |
| GET | `/api/health/active` | none | Same body; `200` only on the instance that currently leads (fires schedules), `503` on standbys. For active/standby load balancing. |
| GET | `/api/server/info` | token | `{mode:"server", version, user, instanceId, leader, store:"postgresql"\|"sqlite", secretStore, tls}`. The desktop engine has no such route (404): that is how a client tells them apart. |
| GET | `/api/server/status` | token | `{instanceId, leader, lease:{holder, sinceMs, expiresMs}, instances:[{id, host, version, startedMs, heartbeatMs, alive, leader}], store, auditExport:[{destination, sent, waiting, dropped, lastError}], lastError?}` |

## Audit verification (SRV-6; desktop too)

`GET /api/audit/verify` walks the whole audit log in id order and checks its hash chain:

```json
{"ok": true, "checked": 1824, "firstId": 1, "lastId": 1824, "lastHash": "9f2c...", "brokenAt": null, "reason": null}
{"ok": false, "checked": 41, "firstId": 1, "lastId": 41, "lastHash": "...", "brokenAt": 42,
 "reason": "entry was changed after it was written"}
```

Every entry gets a link in `audit_chain(id, prev_hash, hash)`: `hash = SHA-256(prev_hash + "\n" +
JSON[id, at, actor, connection_id, connection_name, environment, node, category, action, detail, outcome,
error])`, the first `prev_hash` being 64 zeros. Links are written in the same transaction as the entry,
serialised across server instances (PostgreSQL advisory lock). `brokenAt` is the first entry that was
changed, slipped in outside Studio, or follows a deleted one; `reason` says which. Entries written before
the chain existed are linked on the first start after the upgrade. Removing the newest entries together with
their links cannot be seen from the database alone: compare `lastId`/`lastHash` with the SIEM copy.

The built-in schedule type **`audit-verify`** runs the same check unattended (`PUT /api/schedules`
`{type:"audit-verify", name, everyMinutes, ...}`, no connection) and raises a RED alert (source `audit`,
key `chain`) when the chain is broken.

## SIEM export (SRV-6)

Configured on the server (`audit.syslog.*`, `audit.json.*`, docs/guide/server.md). Each entry is sent after
it is written, with its chain hash; a destination that is down gets the entries when it is back (up to
10,000 waiting per destination, oldest dropped and counted beyond that).

Syslog (RFC 5424, UDP or TCP; TCP uses octet-counting framing, RFC 6587, or `newline`):

```
<109>1 2026-10-10T12:00:00.123Z studio-1 cassandra-studio 4242 audit [studio@32473 id="7" actor="alice" category="cql" outcome="SUCCESS" connection="acme-core" environment="PROD" hash="9f2c..."] {"id":7,"at":"...","actor":"alice",...,"hash":"9f2c..."}
```

PRI = facility × 8 + severity: severity 5 (notice) for SUCCESS, 4 (warning) for FAILED and BLOCKED;
facility from `audit.syslog.facility` (default 13, log audit). 32473 is the documentation enterprise number
(RFC 5612). UDP messages longer than 8 KB are cut; use TCP for large statements.

JSON lines (file or TCP): one object per line with `id, at, actor, connectionId, connectionName,
environment, node, category, action, detail, outcome, error, hash`.

## Store tables (migration 5)

`audit_chain`, `server_tokens(id, user_name, label, token_hash, created_at, last_used_at, revoked_at)`,
`server_leader(name, holder, since_ms, expires_ms)`, `server_instances(id, host, version, started_ms,
heartbeat_ms)`, `server_secrets(name, value, updated_at)`. The desktop creates them too (empty except the
audit chain).
