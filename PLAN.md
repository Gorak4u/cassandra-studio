# Cassandra Studio — Delivery Plan

Companion to [`requirement.txt`](requirement.txt). Requirement IDs (CON-1, REP-3, GCL-2, …) refer to that file. Section 1.1 of the requirements maps every popular Cassandra tool (DevCenter, Reaper, Medusa, GCViewer, DSBulk, VisualVM, …) to the requirement group that covers it.

## 1. Architecture

```
┌──────────────── Desktop app (Windows / macOS / Linux installer) ────────────────┐
│  UI  (Electron + React + TypeScript)                                            │
│   Monaco (CQL) · AG Grid (results) · ECharts (graphs, ring, GC charts)          │
│   connections · dashboards · schema · ops · repair · backup · GC · JVM · bulk   │
│   stress · sstables · diagnostics · audit · config · migrations · scripts       │
│                  │ JSON-RPC + event stream (localhost WebSocket, per-launch token)
│  Engine  (Java 17, bundled JRE via jlink)                                       │
└──────────────────┼──────────────────────────────────────────────────────────────┘
                   │  standalone mode: engine talks to clusters directly
                   │  team mode: desktop UI talks to Studio Server instead (TLS + SSO)
┌──────────────────▼──── Studio Server (optional, container / Linux service) ─────┐
│  Same engine, headless + scheduler, alerting, RBAC/SSO, audit, metric history,  │
│  REST API · PostgreSQL store · Vault for secrets · 2+ instances for HA          │
└──────────────────┬──────────────────────────────────────────────────────────────┘
     CQL 9042 (TLS) │ SSH → localhost:7199 JMX │ HTTP 7071 jmx_exporter │ Sidecar
     Prometheus     │ Rundeck API │ Reaper API │ object storage (S3/GCS/Azure)
                   ▼
        Cassandra 3.11 / 4.0 / 4.1 / 5.0 nodes on Java 8 / 11 / 17
```

### Engine modules (Java 17)
| Module | Covers | Built on |
|---|---|---|
| `cql` | CQL, SCH, SEC, MIG | Apache Cassandra Java Driver 4.x |
| `jmx` | MON, OPS, JVM, CFG | javax.management RMI, DiagnosticCommand and Threading MBeans |
| `ssh` | tunnels, log tail, scripts, sstable tools, GC logs | Apache MINA SSHD |
| `metrics` | MON, CAP | versioned MetricCatalog, poller, jmx_exporter and Prometheus readers |
| `jobs` | long-running jobs: repair, backup, bulk, stress, rolling ops, scripts | job runner with checkpoints, cancel, retry |
| `repair` | REP | segment planner (Reaper-style), Reaper REST client |
| `backup` | BAK | providers: estate scripts, Medusa, snapshots |
| `gclog` | GCL | parsers for Java 8 and unified-logging GC formats, analysis rules |
| `bulk` | BLK | DSBulk (Apache-2.0) embedded as a library |
| `stress` | STR | cassandra-stress / NoSQLBench launchers and result parsers |
| `diag` | PRF, SST, AUD | toppartitions, histograms, sstablemetadata, fqltool |
| `estate` | PRV, RUN, CFG-2 | Hiera reader for cassandra-control-repo, Rundeck client, script catalogue |
| `guard` | NFR-SAFE, NFR-AUD | ActionGuard: read-only and PROD checks, confirmations, audit |
| `server` | SRV | scheduler, OIDC/SAML/LDAP, RBAC, REST API, alert routing |

### Key design points
| Topic | Decision |
|---|---|
| JMX reachability | Your nodes bind JMX to localhost, so an **SSH tunnel to localhost:7199** is the default. Direct JMX, jmx_exporter scraping and Cassandra Sidecar are also supported (CON-6). In team mode, only the Studio Server needs network access to the nodes. |
| Version differences | Each Cassandra major version and each GC type gets its own MetricCatalog YAML. GC log parsers handle both the Java 8 and Java 9+ log formats. Version-specific features (audit log, FQL, virtual tables, profileload) are switched on per node version. |
| Reuse what the estate has | Backups use the estate's S3 scripts. Node lifecycle goes through Rundeck jobs and estate scripts. Drift is checked against the control repo's Hiera data. An existing Reaper or Medusa deployment is integrated, not duplicated. |
| Safety | Every mutating action, script or job goes through one ActionGuard, which checks RBAC, the read-only and PROD flags, blackout windows and confirmations, and writes the audit entry. Stress testing and restores are blocked on PROD by default. |
| Long-running work | Repair, backup, bulk, stress, rolling operations and scripts all use one job framework. Jobs are checkpointed so a restart resumes them instead of starting over. On Studio Server, any instance in the HA pair can pick up a job. |
| Desktop vs server | Both run one engine codebase. Features that need to run unattended (REP-3, BAK-6, ALR-5, SRV-4) only work with a server. Everything else also works standalone. |

## 2. Repository layout (target)

```
cassandra-studio/
  requirement.txt   PLAN.md   README.md   SECURITY.md   docs/
  engine/        Java 17 (Gradle multi-module, one module per row in the table above)
    metrics/     cassandra-3.11.yaml  4.0.yaml  4.1.yaml  5.0.yaml
    gclog/       test fixtures: real CMS / G1 / ZGC / Shenandoah logs, Java 8 and 17
  server/        Studio Server packaging: Dockerfile, Helm chart, systemd unit
  ui/            Electron + React + TS (Vite)
  packaging/     electron-builder, jlink, signing, SBOM
  test-env/      docker-compose: Cassandra 3.11/J8, 4.1/J11, 5.0/J17, plus SSH, TLS,
                 MinIO (S3), Rundeck, Reaper, Prometheus
  .github/workflows   build, test, security scan, package for Windows / macOS / Ubuntu
```

## 3. Phases

The team is 4 engineers: 2 on the engine and server, 1 on the UI, and 1 on QA and release. Every phase ends with an installable, signed build.

### Phase 0: Foundations (3 weeks)
- Monorepo and builds. Electron shell that starts the engine. RPC with an auth token. Job framework and ActionGuard skeleton.
- `test-env` with all three clusters, MinIO, Rundeck and Prometheus.
- CI with SAST, dependency/CVE scan, SBOM and signing (NFR-SSDLC, NFR-SIGN). Request the code-signing certificates now.

### Phase 1: Connections, CQL and schema (5 weeks)
- CON-1…9: connections, keychain, TLS and auth, discovery, SSH tunnels.
- CQL-1…10: editor with autocomplete, run on a chosen node, tracing, grid, grid editing, export, history.
- SCH-1…5: schema browser, DDL forms. SEC-1 and SEC-2: roles and permissions. Audit log.

### Phase 2: Monitoring and alerts (5 weeks)
- CON-6 and CON-7: all JMX access methods. MetricCatalog for all four versions and all GC types.
- MON-1…4 and MON-10…18: health, cluster, node, token, **ring**, load, GC, reads/writes, keyspace and table dashboards.
- ALR-1 and ALR-2: health rules and the warnings panel.

### Phase 3: Operations and diagnostics (6 weeks) → **v1.0**
- OPS-1…4: nodetool views, flush, compact, cleanup, manual repair, snapshots.
- GCL-1…3: GC log analysis (**GCViewer-equivalent**).
- JVM-1 and JVM-2: thread dumps and top threads.
- PRF-1 and PRF-2: hot partitions, large partitions, tombstones.
- CFG-1 and CFG-2: effective config and drift.
- BAK-1…3: backup catalogue and run-now through the estate scripts.
- BLK-1 and BLK-2: bulk unload and load (**DSBulk-equivalent**).
- Release hardening: accessibility, docs, offline installers, proxy support. Release when acceptance criteria 1–11 pass.

### Phase 4: Studio Server (7 weeks) → **v1.1**
- SRV-1…7: server mode, SSO/RBAC, shared catalogue, metric history, scheduler, central audit, HA.
- REP-1…6: segmented and scheduled repair, gc_grace coverage, Reaper integration (**Reaper-equivalent**).
- BAK-4…6: verification, guided restore, schedules (**Medusa-equivalent workflow**).
- ALR-4 and ALR-5: notifications and alert routing.
- STR-1…4: stress testing. MON-23: Prometheus as a data source.
- Release when the v1.1 acceptance criteria pass.

### Phase 5: Deep diagnostics and lifecycle (8 weeks) → **v1.2**
- GCL-4…6, JVM-3…5: GC tuning hints, GC/latency correlation, heap histograms, JFR.
- SST-1…3, PRF-3…5: SSTable inspection, slow queries, query advisor, compaction analysis.
- AUD-1…3, CFG-3…5: audit log, full query log and replay, upgrade readiness, rolling-upgrade tracker.
- PRV-1…3, RUN-1…3: Hiera view, node lifecycle wizards through Rundeck, estate script runner.
- MIG-1…3, CAP-1 and CAP-2, BLK-3…5: schema migrations, capacity forecasts, counts and cross-cluster copy.
- CQL-11…14, SCH-6 and SCH-7, MON-5, MON-6, MON-19…25, OPS-5…7: remaining DBeaver/DevCenter parity and nodetool coverage.

### Phase 6: Later
Everything marked [C], including the web UI (SRV-9), REST CLI, async-profiler flame graphs, K8ssandra, restore drills, migration generation, capacity reports and DBeaver/DevCenter import.

### Timeline
| Release | Content | Weeks from start |
|---|---|---|
| v1.0 | Desktop: DevCenter/DBeaver parity, all dashboards, operations, GC logs, backups, bulk | ~19 |
| v1.1 | Studio Server: Reaper, Medusa-style restore, alerts, SSO, stress | ~26 |
| v1.2 | Full diagnostics, provisioning integration, migrations, capacity | ~34 |

The estimate assumes 4 engineers. With 2 engineers, multiply by about 1.8.

## 4. Testing strategy
- **Unit tests:** metric mapping, CQL generation, rules engine, ActionGuard, repair segment planner, GC log analysis rules.
- **Contract tests:** every MetricCatalog entry is resolved against a real node of each version, and every GC parser runs against real log fixtures from each JVM and collector. A new Cassandra or JDK release that renames something fails CI.
- **Integration tests:** connect → query → monitor → repair → backup → restore → bulk → stress on all `test-env` clusters, in CI.
- **Fault injection:** kill a node during a repair, a backup or a rolling operation. The job must pause or fail safely, and must resume after an engine or server restart.
- **Scale tests:** a simulated 500-node cluster (mock JMX endpoints) and 100 clusters per server, checked against the NFR-SCALE budgets.
- **UI end-to-end:** Playwright driving the Electron app, covering every acceptance criterion.
- **Release:** installer smoke tests on clean Windows, macOS and Ubuntu VMs, and an upgrade test from the previous release (NFR-DATA).

## 5. Risks
| Risk | Mitigation |
|---|---|
| JMX not reachable | SSH tunnel by default, plus Sidecar or jmx_exporter fallback. In team mode only the server needs network access. Clear diagnostics in "Test connection". |
| RMI URL parsing errors on newer JDKs | Start the engine with `-Dcom.sun.jndi.rmiURLParsing=legacy`, the same fix the control repo applies to nodetool. |
| MBean names or GC log formats change | Versioned catalogues and parsers, contract tests, and graceful degradation when something is unknown. |
| Repair or restore harms a production cluster | Pre-checks, rate limits, blackout windows, dry-run plans, two-step confirmation and RBAC. Integrate with Reaper instead of running repairs twice. |
| Running tools on nodes (sstable tools, profilers, stress) | Read-only tools by default. Expert mode is opt-in. Copied binaries are always cleaned up. Every run is audited. |
| Scope is large | Strict release slicing (v1.0 → v1.1 → v1.2). [C] items wait. Each phase ships on its own. |
| Polling load on large clusters | Batched reads, per-cluster interval, poll only open clusters, limit on concurrent connections. With a server, one shared poller serves all users. |
| Code-signing delays | Start the Apple Developer ID and Windows certificate requests in Phase 0. |

## 6. Decisions needed from the owner
See requirement.txt §6. The most important is **Q-5**, whether Studio Server is in v1.0, because it decides when scheduled repair, backups and alerts arrive. Q-6 (an existing Reaper or Medusa) decides whether REP and BAK integrate with that or are built in.
