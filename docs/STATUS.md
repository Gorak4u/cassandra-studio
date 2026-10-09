# Project status

Updated: 2026-10-09. Requirement IDs refer to [`requirement.txt`](../requirement.txt).

## Phases

| Phase | Status | Notes |
|---|---|---|
| 0 Foundations | 🟢 done except signing | Builds, packaging, CI, security scans, release pipeline, test-env, ADRs, threat model; signing waits on certificates; job runner moves to Phase 3 |
| 1 Connections, CQL, schema | 🟢 done | Remaining [S]/[C] items are scheduled in later phases |
| 2 JMX monitoring | 🟢 done | Working end to end on live 3.11 / 4.1 / 5.0: node access (SSH tunnel, bastion jump, direct JMX, jmx_exporter with automatic fallback), health + 12 alert rules, nodes, disk usage over SSH, charts with 24 h history, ring per DC, table metrics, thresholds |
| 3 Ops, GC logs, diagnostics, backup, bulk → v1.0 | ⚪ | |
| 4 Studio Server, repair, restore, alerts → v1.1 | ⚪ | |
| 5–6 Deep diagnostics, provisioning, later items → v1.2 | ⚪ | |

## Requirements done

| Area | Done | Open in this area |
|---|---|---|
| Connections | CON-1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12 | CON-10, CON-13, multi-cloud address mapping |
| CQL | CQL-1 … CQL-10 (incl. saved script library), CQL-14 (warnings) | CQL-11 (CSV import), CQL-12, CQL-13, CQL-15, CQL-16 |
| Schema | SCH-1 … SCH-5 | SCH-6, SCH-7, SCH-8 |
| Security | SEC-1, SEC-2, SEC-3 (system_auth replication warning), SEC-4 | SEC-5 |
| Monitoring | MON-1 … MON-18; CON-7 exporter fallback | Disk usage on DIRECT-JMX clusters (no SSH) |
| Alerts | ALR-1 (12 health rules, per-cluster thresholds), ALR-2 (server warnings shown) | ALR-3, 4, 5 |
| NFR | NFR-SAFE, NFR-AUD, NFR-SEC, NFR-DATA, NFR-UX (theme, shortcuts), NFR-LIC | NFR-SIGN (needs certificates), NFR-SCALE, NFR-A11Y review, NFR-UPD |

## Verified

| Check | Result |
|---|---|
| Engine unit tests | 144 passing |
| UI unit tests | 50 passing |
| Integration tests on real Cassandra 3.11 / 4.1 / 5.0 | Plain CQL: 6 per version. TLS + PasswordAuthenticator + CassandraAuthorizer: 2 per version. All passing on all three |
| OS keychain | CI on Windows and macOS runners |
| Packaged app | CI starts the bundled engine from each OS's installer build |
| Security | CodeQL (Java, TypeScript), Trivy (npm and Java dependencies), npm audit, SBOM. Trivy found 8 HIGH/CRITICAL CVEs in Netty and Jetty: fixed (Netty 4.1.139, Javalin 7 on Jetty 12); now 0 |
| Code review | Independent review of engine, UI and desktop: 10 findings (data correctness, concurrency, desktop navigation hardening, performance), all fixed with regression tests |
| Browser test (test-env: 2-DC 4.1, 3.11, 5.0 TLS + login) | Passing: overview, PROD confirm, remote-DC pinning, grid edit verified in Cassandra, create-table form, roles on multi-DC, create/grant/drop role over TLS + login, 3.11, monitoring health / nodes / ring over SSH-tunnelled JMX |
| JMX access on real nodes | 5 integration tests in CI: 3 tunnelled 4.1 nodes read concurrently (each returns its own host id), all nodes through a bastion, password auth, direct JMX to 3.11, exact error messages. Manually: every snapshot field filled on 3.11 / Java 8 (direct), 4.1 / Java 11 (SSH), 5.0 / Java 17 G1 (SSH via bastion) |
| Accessibility | axe-core WCAG 2.1 A/AA on every main screen: no violations (fails CI on serious/critical) |
| Desktop window | Electron launched under a display: UI renders, startup to usable UI ~2 s (target 5 s), engine stops on close |
| Installers | v0.1.0-alpha.2 published (https://github.com/Gorak4u/cassandra-studio/releases/tag/v0.1.0-alpha.2): Windows exe, macOS arm64 + x64 dmg, Linux AppImage/deb/rpm, each with .sha256; packaged-engine smoke passed on every OS in the release run; not yet opened on a real Windows PC or Mac |

## Waiting on the owner

1. ~~Decisions Q-1 … Q-6~~ All decided 2026-10-09 (requirement.txt §6): no access control; file
   import/export now, shared catalogue in v1.1; Apache Cassandra only; internal licence; desktop-only
   v1.0; repair/backup via pluggable providers (Reaper, Medusa or custom node scripts, per cluster).
2. Code-signing: Apple Developer ID and a Windows code-signing certificate.
3. A non-prod cluster from the estate for a pilot (CQL and SSH access).
