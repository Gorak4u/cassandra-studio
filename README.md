# Cassandra Studio

A desktop app to connect to, query, browse and operate any number of Apache
Cassandra clusters (3.11, 4.0, 4.1, 5.0 on Java 8, 11 or 17) from one place.

- [`requirement.txt`](requirement.txt): what the product must do (requirement IDs such as CQL-3)
- [`PLAN.md`](PLAN.md): architecture, phases and release plan
- [`docs/STATUS.md`](docs/STATUS.md): what is built and tested today

## What works today (v0.1)

- **Connections**: folders (customer > environment > cluster), search, drag-and-drop,
  DEV / TEST / STAGING / PROD tags, read-only flag, import/export without secrets.
  Passwords are kept in the OS keychain (an encrypted file when no keychain exists).
- **CQL**: TLS (JKS, PKCS12, PEM) and PasswordAuthenticator; local DC detected or set.
  Multi-DC clusters: every node in every DC is reachable.
- **Query editor**: multiple tabs, CQL highlighting, schema-aware autocomplete,
  run statement under cursor (Ctrl/Cmd+Enter) or the whole script (Ctrl/Cmd+Shift+Enter).
  Choose the coordinator node, consistency, page size and tracing. `USE`, `CONSISTENCY`,
  `TRACING` and `DESCRIBE` work on every Cassandra version.
- **Results**: sortable, filterable grid, next page / fetch all, CSV and JSON export,
  query trace, server warnings, editing rows in the grid (generates CQL, asks first).
- **Schema browser**: keyspaces, tables, views, indexes (2i, SASI, SAI), types, functions;
  replication, columns and key roles, table options, DDL; create/drop forms.
- **Users & roles**: roles, effective permissions, create/alter/drop, grant/revoke.
- **Safety**: every change shows the exact CQL first. PROD needs the cluster name typed.
  Read-only connections refuse changes. Everything is in the audit log; passwords are masked.

Coming next: JMX monitoring dashboards (health, ring, GC, load, reads/writes, alerts),
then nodetool operations, GC log analysis, backups and more. See [PLAN.md](PLAN.md).

## Install

Download the installer for your system from
[Releases](https://github.com/Gorak4u/cassandra-studio/releases):

| System | File | Notes |
|---|---|---|
| Windows 10/11 | `CassandraStudio-<v>-win-x64.exe` | Unsigned until a code-signing certificate is set up: in SmartScreen choose *More info → Run anyway*. |
| macOS Apple Silicon | `CassandraStudio-<v>-mac-arm64.dmg` | Unsigned until an Apple Developer ID is set up: after copying to Applications, run `xattr -cr "/Applications/Cassandra Studio.app"` once. |
| macOS Intel | `CassandraStudio-<v>-mac-x64.dmg` | As above. |
| Ubuntu / Debian | `CassandraStudio-<v>-linux-amd64.deb` | `sudo apt install ./CassandraStudio-*.deb` |
| RHEL / Rocky | `CassandraStudio-<v>-linux-x86_64.rpm` | `sudo dnf install ./CassandraStudio-*.rpm` |
| Any Linux | `CassandraStudio-<v>-linux-x86_64.AppImage` | `chmod +x` and run. |

Each file has a `.sha256` checksum next to it. Java is bundled; nothing else is needed.

### What the machine needs to reach

| For | Port | Notes |
|---|---|---|
| Queries, schema, roles | CQL 9042 on the nodes | TLS and credentials as the cluster requires |
| Monitoring and operations (next release) | SSH 22 on the nodes | JMX on the estate's nodes listens on localhost only, so Studio tunnels over SSH |

The driver connects to the addresses the nodes advertise (`broadcast_rpc_address`).
If those are private cloud IPs your machine cannot reach, run Studio from inside that
network (VPN or bastion) for now; address mapping is planned.

## Run from source

Needs Java 21 and Node 22.

```bash
(cd ui && npm ci && npm run build)
(cd engine && ./gradlew shadowJar)
java -jar engine/build/libs/cassandra-studio-engine-all.jar --ui-dir ui/dist --port 8080 --token mytoken
# open http://127.0.0.1:8080/#token=mytoken
```

The engine only listens on 127.0.0.1 and needs the token on every request.

UI development with hot reload: start the engine with `--dev-cors --port 8080 --token dev`,
then in `ui/` run `VITE_ENGINE_URL=http://127.0.0.1:8080 npm run dev` and open
`http://localhost:5173/#token=dev`.

## Build installers

```bash
JAVA_HOME=/path/to/jdk-21 scripts/package.sh --linux AppImage deb   # or --win nsis, --mac dmg
```

Output goes to `desktop/dist/`. CI builds all platforms: push a tag such as `v0.1.0`
and the Release workflow publishes the installers.

## Tests

```bash
(cd engine && ./gradlew test)                                     # unit tests
(cd engine && ./gradlew test -Pintegration -PcassandraVersions=3.11,4.1,5.0 --tests '*IntegrationTest')   # needs Docker
(cd ui && npm test)                                               # UI unit tests
node ui/tests/smoke.mjs http://127.0.0.1:8080 <token> /tmp/shots   # browser test against a running engine
```

## Layout

```
engine/    Java 21 engine: HTTP API, CQL driver, schema, guard, audit (Gradle)
ui/        React + TypeScript UI (Vite), served by the engine
desktop/   Electron shell that starts the bundled engine
scripts/   packaging
.github/   CI and release workflows
```
