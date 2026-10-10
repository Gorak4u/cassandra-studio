# Project status

Updated: 2026-10-10. Requirement IDs refer to [`requirement.txt`](../requirement.txt).

## Phases

| Phase | Status | Notes |
|---|---|---|
| 0 Foundations | 🟢 done except signing | Builds, packaging, CI, security scans, release pipeline, test-env, ADRs, threat model; signing waits on certificates; job runner moves to Phase 3 |
| 1 Connections, CQL, schema | 🟢 done | Remaining [S]/[C] items are scheduled in later phases |
| 2 JMX monitoring | 🟢 done | Working end to end on live 3.11 / 4.1 / 5.0: node access (SSH tunnel, bastion jump, direct JMX, jmx_exporter with automatic fallback), health + 12 alert rules, nodes, disk usage over SSH, charts with 24 h history, ring per DC, table metrics, thresholds |
| 3 Ops, GC logs, diagnostics, backup, bulk → v1.0 | 🟡 features and release hardening done; release candidate next | All [M] items of OPS-1…4, GCL-1…4, JVM-1/2, PRF-1/2, CFG-1/2, BAK-1…3, BLK-1/2 built and live-tested on 3.11 / 4.1 / 5.0; acceptance criteria 6, 8, 9, 10, 11 pass. Hardening done: user guide, install guide, 14 runbooks, release notes, in-app help; offline build, proxy and CA bundle, update check; 500-node scale, remembered layout, settings backup/restore, audit export, local crash log, hung-node fixes ([perf](perf.md)). Release candidate v1.0.0-rc.1 published. Next: owner checks on a real Windows PC and Mac, pilot cluster, signing, then v1.0.0 |
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
| Operations | OPS-1 (12 nodetool views), OPS-2 (flush, compactions, cleanup, scrub, upgradesstables, garbagecollect), OPS-3 (repair with live progress and cancel), OPS-4 (snapshots) | OPS-5…7 |
| Diagnostics | JVM-1 (thread dumps, deadlocks, compare), JVM-2 (top threads), PRF-1 (hot partitions), PRF-2 (histograms, log warnings, estate tombstone-scan) | JVM-3…5, PRF-3…5 |
| GC logs | GCL-1…3, GCL-4 (19 finding rules) — Java 8 and unified logging; CMS, ParNew, G1, Parallel, Serial, ZGC (incl. generational), Shenandoah | GCL-5…7 |
| Config | CFG-1 (yaml / system_views.settings, JVM, OS), CFG-2 (drift per cluster/DC and against Puppet Hiera) | CFG-3…5 |
| Backups | BAK-1 (estate scripts, Medusa, snapshots; auto-detect), BAK-2 (catalogue), BAK-3 (run now) | BAK-4…6 (verify, restore, schedules); Medusa untested live |
| Bulk | BLK-1 (unload CSV/JSON, token-range parallel), BLK-2 (load with mapping, TTL/timestamp, rate limit, rejects) | BLK-3…5, S3/GCS targets |
| NFR | NFR-SAFE, NFR-AUD (incl. CSV/JSON export), NFR-SEC, NFR-DATA (downgrade refusal, pre-migration copy, settings backup/restore), NFR-UX (theme, shortcuts, remembered tabs and layout), NFR-LIC, NFR-PERF, NFR-SCALE (500 nodes), NFR-OBS (local crash log, Copy diagnostics), NFR-RELI (hung nodes don't block the UI), NFR-NET (offline, proxy, CA bundle), NFR-UPD (update check, no auto-download), NFR-DOCS | NFR-SIGN (needs certificates), NFR-A11Y manual screen-reader review |

## Verified

| Check | Result |
|---|---|
| Engine unit tests | 308 passing |
| UI unit tests | 119 passing |
| Integration tests on real Cassandra 3.11 / 4.1 / 5.0 | Plain CQL: 6 per version. TLS + PasswordAuthenticator + CassandraAuthorizer: 2 per version. All passing on all three |
| OS keychain | CI on Windows and macOS runners |
| Packaged app | CI starts the bundled engine from each OS's installer build |
| Security | CodeQL (Java, TypeScript), Trivy (npm and Java dependencies), npm audit, SBOM. Trivy found 8 HIGH/CRITICAL CVEs in Netty and Jetty: fixed (Netty 4.1.139, Javalin 7 on Jetty 12); now 0 |
| Code review | Independent review of engine, UI and desktop: 10 findings (data correctness, concurrency, desktop navigation hardening, performance), all fixed with regression tests |
| Browser test (test-env: 2-DC 4.1, 3.11, 5.0 TLS + login) | Passing: overview, PROD confirm, remote-DC pinning, grid edit verified in Cassandra, create-table form, roles on multi-DC, create/grant/drop role over TLS + login, 3.11, monitoring health / nodes / ring over SSH-tunnelled JMX |
| JMX access on real nodes | 5 integration tests in CI: 3 tunnelled 4.1 nodes read concurrently (each returns its own host id), all nodes through a bastion, password auth, direct JMX to 3.11, exact error messages. Manually: every snapshot field filled on 3.11 / Java 8 (direct), 4.1 / Java 11 (SSH), 5.0 / Java 17 G1 (SSH via bastion) |
| Phase 3 live tests | 29 integration tests against the test-env, in CI: JMX access 5, operations 2, diagnostics 3, GC logs 3, config 3, backups 4, bulk 9. Acceptance: 6 flush + repair with progress and audit (4.1); 8 Java 8 CMS (3.11) and Java 17 G1 (5.0 via bastion) GC logs loaded over SSH with pauses, charts and findings; 9 thread dump + top threads on all three versions; 10 backup through the estate scripts listed in the catalogue; 11 drift report shows one node's changed setting |
| Bulk throughput (acme-core 4.1) | Load 15.2k rows/s cold, 20.7k–22.3k warm with the new default of 64 requests in flight (was 8k at 16); unload 53k–144k rows/s. Goal 10k: met |
| Scale ([perf](perf.md)) | 500 synthetic nodes polled every 10 s: ~200 ms per poll, ~3% of one core, 24 h history ~123 MB. 100 of 500 nodes hung: the other 400 still read, every UI request answers in 3–15 s. UI with 500 nodes and 100 connections: ring 0.5 s, charts 1.3–1.4 s, nodes sort 149 ms. Startup to usable UI with restored tabs 1.6 s |
| Accessibility | axe-core WCAG 2.1 A/AA on every main screen: no violations (fails CI on serious/critical) |
| Desktop window | Electron launched under a display: UI renders, startup to usable UI ~2 s (target 5 s), engine stops on close |
| Installers | v1.0.0-rc.1 published (https://github.com/Gorak4u/cassandra-studio/releases/tag/v1.0.0-rc.1): Windows exe, macOS arm64 + x64 dmg, Linux AppImage/deb/rpm, each with .sha256. Before publishing, the release installs and starts each Linux package on clean Ubuntu 22.04, Ubuntu 24.04 and Rocky Linux 9 (usable UI in 2–5 s, right version, clean uninstall) and smoke-tests the bundled engine on every OS. Not yet opened on a real Windows PC or Mac; not code-signed |

## Waiting on the owner

1. ~~Decisions Q-1 … Q-6~~ All decided 2026-10-09 (requirement.txt §6): no access control; file
   import/export now, shared catalogue in v1.1; Apache Cassandra only; internal licence; desktop-only
   v1.0; repair/backup via pluggable providers (Reaper, Medusa or custom node scripts, per cluster).
2. Code-signing: Apple Developer ID and a Windows code-signing certificate.
3. A non-prod cluster from the estate for a pilot (CQL and SSH access).
