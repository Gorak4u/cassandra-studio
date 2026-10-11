# Studio Server guide

Studio Server is the Studio engine running headless, close to your clusters, for a team and for work that
must run unattended (schedules, alerts). The desktop app keeps working on its own; a server is optional.
Its REST API is the desktop's (docs/api/server.md lists the differences).

- One command, `studio-server`, in the same distribution as the desktop engine.
- HTTPS with your certificate; named API tokens, one or more per person; no roles (everyone may do
  everything, with the usual confirmations and the audit log).
- Store: SQLite for one server, PostgreSQL for two or more (high availability).
- Connection passwords encrypted with a key you keep outside the database, or in HashiCorp Vault.
- Tamper-evident audit log, exported to your SIEM (syslog or JSON lines).

## 1. Install

### Container

```sh
cd engine && ./gradlew installDist && cd ..
docker build -f server/Dockerfile -t cassandra-studio-server .
docker run -d --name studio -p 8443:8443 \
  -v studio-data:/var/lib/cassandra-studio \
  -v /etc/cassandra-studio:/etc/cassandra-studio:ro \
  cassandra-studio-server
```

The image (eclipse-temurin 21 JRE) runs as user `studio` (uid 10001), keeps its data in
`/var/lib/cassandra-studio`, reads `/etc/cassandra-studio/server.conf` when present and `STUDIO_*`
environment variables, and has a `HEALTHCHECK` (`studio-server health`). Files you mount (keystore, keys,
password files) must be readable by uid 10001.

### Linux service (systemd)

1. Install Java 21 and unpack `engine/build/distributions/cassandra-studio-engine-*.tar` to
   `/opt/cassandra-studio` (so `/opt/cassandra-studio/bin/studio-server` exists).
2. `useradd --system --home-dir /var/lib/cassandra-studio --shell /usr/sbin/nologin studio`
3. Put the settings in `/etc/cassandra-studio/server.conf` (start from `server/server.conf.example`),
   readable by `studio` only when it holds passwords.
4. `cp server/studio-server.service /etc/systemd/system/ && systemctl daemon-reload && systemctl enable --now studio-server`

The unit runs with a read-only system, private /tmp and no new privileges; the data directory is the only
writable path. `journalctl -u studio-server` shows the log; the line `STUDIO_SERVER_READY {...}` means it is
listening.

## 2. Settings

`server.conf` is a Java properties file. Every key can be overridden with an environment variable:
`STUDIO_` + key in upper case with dots as underscores (`store.password` → `STUDIO_STORE_PASSWORD`).
Keys ending in `File` read a secret from a file (Docker/Kubernetes secrets). Unknown keys stop the server
with their names, so typos do not go unnoticed. Relative paths are relative to the config file.

| Key | Default | |
|---|---|---|
| `server.host` | 127.0.0.1 | Bind address. Anything but loopback needs TLS (below) |
| `server.port` | 8443 | |
| `server.insecureHttp` | false | Allow plain HTTP off loopback: only behind a proxy that terminates TLS (`--insecure-http` on the command line does the same) |
| `server.dataDir` | /var/lib/cassandra-studio | SQLite store, generated secrets key, crash log |
| `server.uiDir` | — | Serve the built web UI (`ui/dist`) from here |
| `server.corsOrigins` | — | Browser origins allowed to call the API, comma separated (`*` = any; tokens, not cookies, are the credential) |
| `server.instanceId` | host:port | Name of this instance in status pages and the scheduler lease |
| `tls.keystore`, `tls.keystorePassword(File)`, `tls.keystoreType` | —, —, PKCS12 | Server certificate and key |
| `store.url` | jdbc:sqlite:&lt;dataDir&gt;/studio.db | `jdbc:postgresql://host:5432/db?sslmode=verify-full` for HA |
| `store.user`, `store.password(File)` | — | PostgreSQL login |
| `secrets.provider` | local | `local` or `vault` |
| `secrets.keyFile` / `secrets.key` | — | Key for the local secret store (base64 of 32 bytes) |
| `vault.address`, `vault.token(File)`, `vault.mount`, `vault.path`, `vault.namespace`, `vault.caFile` | —, —, secret, cassandra-studio, —, — | HashiCorp Vault KV v2 |
| `audit.syslog.host`, `.port`, `.protocol`, `.framing`, `.facility`, `.appName` | —, 514, udp, octet-counting, 13, cassandra-studio | Syslog export |
| `audit.json.file` / `audit.json.host`, `audit.json.port` | —, 5170 | JSON-lines export |
| `ha.leaseSeconds` | 20 | Scheduler lease; a crashed leader is replaced within about this long |

## 3. TLS

Give the server a PKCS12 keystore with the certificate (and chain) and its key:

```sh
openssl pkcs12 -export -in server.crt -inkey server.key -certfile chain.pem \
  -out /etc/cassandra-studio/server.p12 -name server
```

```properties
server.host = 0.0.0.0
tls.keystore = /etc/cassandra-studio/server.p12
tls.keystorePasswordFile = /etc/cassandra-studio/keystore.pass
```

TLS 1.2 and 1.3 only. The server refuses to listen on a non-loopback address without TLS unless you say
`--insecure-http`, which is meant for a TLS-terminating reverse proxy or load balancer in front of it.
Users' desktops must trust the certificate's CA (the usual corporate CA, or the CA bundle setting in Studio).

## 4. API tokens

Each team member gets their own token; the token is who they are in the audit log.

```sh
studio-server token add alice --label "alice laptop"   # prints cst_... once; give it to alice
studio-server token list                               # id, user, label, created, last used, status
studio-server token revoke 3f9a1c2e                    # one token, by id
studio-server token revoke alice                       # every active token of alice
```

Only a SHA-256 hash of each token is stored. Revocation reaches every instance within 5 seconds. Token
changes are audited (actor `cli: <OS user>`). Run the commands on any server host with the same settings
(`--config` or `STUDIO_CONFIG`); in a container: `docker exec studio studio-server token add alice`.

In Studio (desktop) the user connects to the server with its address and their token. Scripts use
`Authorization: Bearer cst_...` against the same API.

## 5. Connection secrets

Connection passwords and passphrases never go into the configuration or the database in clear text.

- **local** (default): AES-256-GCM in the store's `server_secrets` table, each value bound to its name.
  The key comes from `secrets.keyFile` or `STUDIO_SECRETS_KEY`, never from the database. Create one with
  `studio-server secret-key > /etc/cassandra-studio/secret.key` (mode 600). Without one, a single server
  creates `<dataDir>/secret.key` on first start: back it up. All instances of an HA pair need the **same**
  key; an instance with a different key refuses to start ("secrets key does not match").
- **vault**: HashiCorp Vault KV version 2 with token authentication. Each secret is
  `<vault.mount>/data/<vault.path>/conn/<connection id>/<name>` with a field `value`. The token needs
  create, read, update and delete on `<mount>/data/<path>/*` and delete on `<mount>/metadata/<path>/*`;
  Studio checks it at start (`auth/token/lookup-self`). Renew the token outside Studio (or use a periodic token).

Losing the local key means re-entering every connection password; keep it with your other server secrets.

## 6. PostgreSQL and high availability

For two or more instances, point them all at one PostgreSQL database (12 or later):

```sql
CREATE ROLE studio LOGIN PASSWORD '...';
CREATE DATABASE studio OWNER studio;
```

```properties
store.url = jdbc:postgresql://db.example.com:5432/studio?sslmode=verify-full
store.user = studio
store.passwordFile = /etc/cassandra-studio/db.pass
```

Studio creates its tables on first start (instances starting together wait for each other). Every
instance serves the whole API with the same catalogue, schedules, tokens, secrets and audit log.

**Schedules.** One instance leads and fires schedules: it holds a lease row that it renews every quarter of
`ha.leaseSeconds`. When it stops cleanly it gives the lease up and another instance takes over at once; when
it crashes or loses the database, another takes over once the lease runs out (20 s by default) and fires
whatever is due straight away. A leader that cannot renew stops firing before its lease could pass to
another, and every due run is claimed in the database, so a run is never fired twice. Measured in the
integration test: the standby fired a due schedule 10.5 s after the leader was killed (lease 10 s).

**Jobs** (a repair or backup that is running) live in the instance that started them. If that instance
dies, the job stops; its schedule run is shown as `UNKNOWN` and the schedule fires again at its next time.

**Load balancer.** Health check on `GET /api/health` (any instance that reaches the database). Because a
running job's progress is only known to the instance that runs it, either route all users to the leader
(health check on `GET /api/health/active`, active/standby) or use sticky sessions.

`GET /api/server/status` (with a token) shows the lease, every instance with its heartbeat, and the audit
export queues.

A single server can stay on SQLite; it has no failover.

## 7. Audit log and SIEM

Every action of every user goes to the audit log with the user's name. Entries are hash-chained:
`GET /api/audit/verify` (or `studio-server audit verify`, exit code 1 when broken) reports the first entry
that was changed, inserted or deleted behind Studio's back. Schedule the built-in `audit-verify` type to
check it daily and raise an alert.

Send the log to your SIEM as well; the copy outside the database is what makes tampering by someone with
database access visible:

```properties
audit.syslog.host = siem.example.com
audit.syslog.port = 6514
audit.syslog.protocol = tcp
audit.json.file = /var/log/cassandra-studio/audit.jsonl
```

Formats in docs/api/server.md. Each instance sends the entries it writes; a SIEM that is down receives the
entries when it is back (`/api/server/status` shows waiting and dropped counts).

## 8. Backup

- **SQLite**: stop the server, or copy with `sqlite3 studio.db ".backup studio-copy.db"` while running.
  Studio also copies the database to `<dataDir>/backups` before each upgrade (last 5 kept).
- **PostgreSQL**: back the database up as you back up any other: `pg_dump -Fc studio > studio.dump`,
  continuous archiving, or your platform's snapshots. Studio does not copy a PostgreSQL store itself.
- Always back up the secrets key file (local secret store) and the keystore with it.

## 9. Upgrade

1. Back up (above). On PostgreSQL this is your job: there is no automatic copy.
2. Stop all instances, install the new version, start one; it migrates the store; then start the others.
   (Starting them together is safe too: one migrates while the others wait.)
3. A store written by a newer Studio is refused, not changed ("written by a newer Studio; upgrade
   Studio instead of downgrading"). To go back, restore the backup taken before the upgrade.

## 10. Troubleshooting

| Message | Meaning |
|---|---|
| `Refusing to listen on 0.0.0.0 without TLS` | Set `tls.keystore`, or `--insecure-http` behind a TLS proxy |
| `Unknown setting(s) in ...` | A typo in server.conf |
| `The secrets key does not match` | This instance has another key than the one the store was written with |
| `Vault refused the token` | Token expired or lacks the policy above |
| `Leader election: store not reachable` (log) | The instance lost the database; it stops firing schedules until it is back |
| 401 `Missing, unknown or revoked API token` | Wrong or revoked token; `token list` shows its status |
