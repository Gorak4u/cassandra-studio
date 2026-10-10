# Cassandra Studio

A desktop app to connect to, query, browse and operate any number of Apache
Cassandra clusters (3.11, 4.0, 4.1, 5.0 on Java 8, 11 or 17) from one place.

## Documentation

- [User guide](docs/guide/user-guide.md): connections, safety, CQL editor, schema, roles, monitoring,
  operations, diagnostics, GC logs, config and drift, backups, bulk, audit log, shortcuts
- [Install and admin guide](docs/guide/install.md): installers, data folders, upgrades, keychain,
  offline installs, node prerequisites (SSH, JMX, sudo), troubleshooting
- [Runbooks](docs/guide/runbooks/README.md): one per operation wizard (flush, compaction, cleanup, scrub,
  upgradesstables, garbagecollect, repair, snapshots, backups, bulk unload/load, drift, GC logs, thread dumps)
- [Release notes 1.0.0](docs/release-notes/v1.0.0.md)
- [`requirement.txt`](requirement.txt) (requirement IDs such as CQL-3), [`PLAN.md`](PLAN.md)
  (architecture and phases), [`docs/STATUS.md`](docs/STATUS.md) (what is built and tested),
  [`docs/api/`](docs/api) (engine API per feature)

The guides are also built into the app: the **?** button on each panel opens the matching section,
offline.

## Features (v1.0)

- **Connections**: folders (customer > environment > cluster), search, drag-and-drop,
  DEV / TEST / STAGING / PROD tags, read-only flag, import/export without secrets.
  Secrets in the OS keychain (an encrypted file when no keychain exists).
  TLS (JKS, PKCS12, PEM) and PasswordAuthenticator; every node in every DC reachable.
  JMX through an SSH tunnel (also via a bastion), direct JMX, or jmx_exporter with automatic fallback.
- **Query editor**: tabs, CQL highlighting, schema-aware completion, run statement / script,
  chosen coordinator node, consistency, paging, tracing, server warnings, CSV/JSON export,
  editing rows in the grid, history and a saved script library.
- **Schema and roles**: schema browser with DDL and create/drop forms; roles, effective
  permissions, grant/revoke.
- **Monitoring**: live JMX dashboards per node (health, nodes, charts with 24 h history, ring,
  tables), 12 health rules with per-cluster thresholds.
- **Operations**: 12 nodetool views; flush, compaction, cleanup, scrub, upgradesstables,
  garbagecollect; repair (full / incremental / sub-range) with live progress and cancel; snapshots.
- **Diagnostics**: thread dumps, deadlocks and dump comparison, live top threads, hot partitions,
  table histograms, tombstone and large-partition warnings from the logs.
- **GC logs**: load from a node over SSH or upload; GCViewer-style summary, charts and 19 tuning
  findings for Java 8 and unified logs (CMS, G1, Parallel, Serial, ZGC, Shenandoah).
- **Config**: effective cassandra.yaml, JVM and OS settings per node; drift report per cluster or
  DC, and against Puppet Hiera.
- **Backups**: estate backup scripts, Medusa or snapshots per cluster; catalogue and run-now.
- **Bulk**: unload tables or queries to CSV/JSON, load CSV/JSON with mapping, TTL, rate limit,
  dry run and reject files.
- **Safety**: every change shows the exact CQL or nodetool command first; PROD needs the cluster
  name typed; read-only connections refuse changes; everything is in the local audit log.
- Works with Cassandra 3.11, 4.0, 4.1 and 5.0 on Java 8, 11 or 17. Light and dark theme,
  keyboard accessible, no telemetry.

Known limits are listed in the [release notes](docs/release-notes/v1.0.0.md#known-limits).

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
Details, data locations, upgrades and offline installs: [install guide](docs/guide/install.md).

### What the machine needs to reach

| For | Port | Notes |
|---|---|---|
| Queries, schema, roles | CQL 9042 on the nodes | TLS and credentials as the cluster requires |
| Monitoring, operations, diagnostics, GC logs, config, backups | SSH 22 on the nodes (or a bastion) | JMX on the estate's nodes listens on localhost only, so Studio tunnels over SSH; direct JMX and jmx_exporter also work |

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

## Test clusters

`test-env/` starts the clusters the tests use: a 2-DC Cassandra 4.1 cluster (127.0.0.1:19042),
Cassandra 3.11 (29042, JMX without auth on 27199) and, with `--profile secure`, Cassandra 5.0 with
TLS and login (39042, user `cassandra` / `cassandra`, truststore `test-env/certs/node.pem`).
`--profile jmx` adds an sshd next to each 4.1 node (127.0.0.1:2201-2203) and a bastion (2200) for
monitoring over SSH tunnels: user `studio`, key `test-env/ssh/id_test`, port 2222 from inside the network.

```bash
cd test-env && ./make-certs.sh && PROFILES="secure jmx" ./wait-ready.sh
# after a machine restart (starts Docker if needed, then the clusters): scripts/dev-up.sh
```

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
test-env/  docker-compose test clusters (multi-DC, 3.11, TLS + login)
docs/      status, design decisions (adr/), threat model
.github/   CI, CodeQL and release workflows
```
