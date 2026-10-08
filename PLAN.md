# Cassandra Studio — Delivery Plan

Companion to [`requirement.txt`](requirement.txt). Requirement IDs (CON-1, MON-14, …) refer to that file.

## 1. Architecture

```
┌──────────────────────────── Desktop app (one installer) ───────────────────────────┐
│                                                                                    │
│  UI  (Electron + React + TypeScript)                                               │
│   • Monaco editor (CQL)      • AG Grid (results)     • ECharts (graphs, ring)      │
│   • Connection tree, dashboards, schema browser, ops wizards                       │
│                    │  JSON-RPC + event stream over localhost WebSocket             │
│                    │  (random port + per-launch token, never exposed)              │
│  Engine  (Java 17 sidecar, bundled JRE via jlink — user installs nothing)          │
│   • CQL:     Apache Cassandra Java Driver 4.x (protocol v3–v5, node pinning)       │
│   • JMX:     javax.management RMI client, version-aware MBean catalogue            │
│   • SSH:     Apache MINA SSHD (tunnels, jump hosts, log tail)                      │
│   • Metrics: jmx_exporter HTTP scraper (fallback)                                  │
│   • Store:   SQLite (connections, history, audit, 24 h metric ring buffer)         │
│   • Secrets: OS keychain (java-keyring)                                            │
└────────────────────────────────────────────────────────────────────────────────────┘
        │ CQL 9042 (TLS)        │ SSH 22 → localhost:7199 (JMX)      │ HTTP 7071
        ▼                       ▼                                    ▼
             Cassandra 3.11 / 4.0 / 4.1 / 5.0 nodes on Java 8 / 11 / 17
```

### Why this stack
- **Java engine.** JMX is a Java protocol (RMI). The official Cassandra driver, nodetool's MBean interfaces and the best SSH library are all Java. A Java 17 client talks JMX to Java 8, 11 and 17 servers.
- **Electron UI.** Monaco (the VS Code editor) gives CQL autocomplete with little effort. ECharts and AG Grid handle large live datasets. The UI looks the same on Windows, macOS and Linux, which Tauri can't promise because it uses each OS's own webview.
- **Alternative we rejected:** a single JavaFX app. It needs one process instead of two, but its editor, grid and charting components are much weaker. That's the opposite of what a DBeaver/DevCenter replacement needs.

### Key design points
| Topic | Decision |
|---|---|
| JMX reachability | JMX is bound to localhost on the current nodes, so the **default is an SSH tunnel** to `localhost:7199`. Cassandra serves RMI registry and RMI objects on the same port, so a single forwarded port works. Direct JMX and jmx_exporter scraping are also supported (CON-6). |
| Version differences | `MetricCatalog` maps logical metrics (for example `read.latency.p99` or `gc.pause.total`) to MBean names and attributes per Cassandra major version and per GC type. One YAML file per version, covered by contract tests. |
| Polling cost | One JMX connection per node. Reads are batched with `getAttributes` on `ObjectName` patterns. Only open clusters are polled. Down nodes use back-off. |
| Query on a chosen node | Driver 4 `Statement.setNode(node)` makes the chosen node the coordinator (CQL-3). |
| Safety | Every mutating action goes through one `ActionGuard`. It checks the read-only and PROD flags, shows the CQL or nodetool equivalent, asks for confirmation and writes the audit entry. |
| Long operations | Repair, compaction and similar run as jobs with JMX notification listeners, streamed to the UI and cancellable. |

## 2. Repository layout (target)

```
cassandra-studio/
  requirement.txt   PLAN.md   README.md
  engine/           Java 17 (Gradle): cql/, jmx/, ssh/, metrics/, ops/, store/, rpc/
    src/main/resources/metrics/   cassandra-3.11.yaml  4.0.yaml  4.1.yaml  5.0.yaml
  ui/               Electron + React + TS (Vite): connections/, dashboards/, editor/,
                    schema/, ops/, security/, alerts/
  packaging/        electron-builder config, jlink script, signing
  test-env/         docker-compose: Cassandra 3.11/J8, 4.1/J11, 5.0/J17 (+ SSH, TLS)
  .github/workflows build + test + package on windows/macos/ubuntu runners
```

## 3. Phases

Rough sizing assumes 2 developers. Each phase ends with a usable, installable build.

### Phase 0: Foundations (2 weeks)
- Monorepo, Gradle and Vite builds, Electron shell that starts the Java sidecar, RPC layer with auth token.
- jlink'd JRE bundled. CI produces unsigned installers for all three operating systems.
- `test-env/` docker-compose with three clusters (3.11/J8, 4.1/J11, 5.0/J17), one with TLS and auth, one with JMX reachable only over SSH.
- **Exit criterion:** an empty app installs and starts on Windows, macOS and Ubuntu.

### Phase 1: Connections and the CQL core (4 weeks) [MVP]
- CON-1…9: connection tree, environment tags, TLS and auth, keychain, import/export, node discovery.
- CQL-1…8, CQL-10: editor with schema autocomplete, run on a chosen node, consistency settings, result grid, tracing, history, saved scripts, export.
- SCH-1…3, SCH-5: schema browser and DDL view.
- ActionGuard and audit log (NFR-SAFE, NFR-AUD).

### Phase 2: JMX and monitoring (5 weeks) [MVP]
- CON-6 and CON-7: direct JMX, SSH tunnel with jump host, jmx_exporter fallback.
- MetricCatalog for 3.11, 4.0, 4.1 and 5.0, plus GC types CMS, G1 and ZGC (MON-2), with contract tests against `test-env`.
- Poller, SQLite ring buffer and time-series API (MON-1, MON-3, MON-4).
- Dashboards MON-10…18: health, cluster, node, tokens, **ring**, load, GC, reads and writes, keyspace and table.
- ALR-1 and ALR-2: health rules engine and warnings panel.

### Phase 3: Operations and security (4 weeks) [MVP complete]
- OPS-1…4: nodetool views, flush, compact, cleanup, repair with live progress, snapshots.
- SEC-1 and SEC-2: roles, permissions, passwords.
- CQL-9 (grid editing) and SCH-4 (DDL forms).
- Signed and notarised installers. **v1.0 release** against the acceptance criteria in requirement.txt §7.

### Phase 4: DBeaver and DevCenter parity, v1.x (4–6 weeks)
- CQL-11…14: CSV/JSON import, bind variables, snippets, risky-query warnings.
- SCH-6 and SCH-7: schema compare and data-model checks.
- MON-5, MON-6, MON-19…21: custom dashboards, compare views, tpstats, compaction and streaming, top tables.
- OPS-5…7: rolling operations and expert actions. ALR-3 and ALR-4: log viewer and notifications.
- CON-10…12: import from the control repo's Hiera data, multi-cluster tabs, read-only flag.

### Phase 5: Later
Items marked [C]: visual query builder, result diff, schema diagram, Slack/e-mail alerts, DBeaver/DevCenter import, i18n.

**Timeline:** about 15 weeks to v1.0 (MVP), about 21 weeks to v1.x parity.

## 4. Testing strategy
- **Unit:** metric mapping, CQL generation (DDL, grid edits), rules engine, ActionGuard.
- **Contract:** every entry in every `metrics/*.yaml` is resolved against a real node of that version in `test-env`. This test fails as soon as a new Cassandra release renames an MBean.
- **Integration:** the full connect → query → monitor → repair flow on all three test clusters, in CI.
- **UI end-to-end:** Playwright driving the Electron app, covering the acceptance criteria.
- **Manual:** installer smoke test on a clean Windows, macOS and Ubuntu VM for each release.

## 5. Risks
| Risk | Mitigation |
|---|---|
| JMX not reachable (localhost bind, firewalls) | SSH tunnel by default. jmx_exporter fallback. Clear diagnostics in "Test connection". |
| RMI URL parsing errors on newer JDKs | Start the engine with `-Dcom.sun.jndi.rmiURLParsing=legacy`, the same fix the control repo applies to nodetool. |
| MBean names change between versions | Versioned catalogue plus contract tests. Unknown metrics are hidden instead of breaking the dashboard. |
| Polling load on large clusters | Batched reads, per-cluster interval, poll only open clusters, and a limit on concurrent JMX connections. |
| Destructive mistakes on PROD | ActionGuard, PROD banner, typing the cluster name to confirm, read-only flag, audit log. |
| macOS and Windows code signing | Get an Apple Developer ID and a Windows code-signing certificate during Phase 0. |

## 6. Decisions needed from the owner
See requirement.txt §6: Q-1 (who may run operations), Q-2 (shared connection list), Q-3 (DSE or Scylla support), Q-4 (licence).
