# Project status

Updated: 2026-10-08. Requirement IDs refer to [`requirement.txt`](../requirement.txt).

## Phases

| Phase | Status | Notes |
|---|---|---|
| 0 Foundations | 🟢 done except signing | Builds, packaging, CI, security scans, release pipeline, test-env, ADRs, threat model; signing waits on certificates; job runner moves to Phase 3 |
| 1 Connections, CQL, schema | 🟢 mostly done | See below |
| 2 JMX monitoring | ⚪ next | Health, ring, GC, load, reads/writes, alerts |
| 3 Ops, GC logs, diagnostics, backup, bulk → v1.0 | ⚪ | |
| 4 Studio Server, repair, restore, alerts → v1.1 | ⚪ | |
| 5–6 Deep diagnostics, provisioning, later items → v1.2 | ⚪ | |

## Requirements done

| Area | Done | Open in this area |
|---|---|---|
| Connections | CON-1, 2, 3, 4, 5, 8, 9, 11, 12 | CON-6/7 work (settings exist), CON-10, CON-13, multi-cloud address mapping |
| CQL | CQL-1 … CQL-10, CQL-14 (warnings) | CQL-11 (CSV import), CQL-12, CQL-13, CQL-15, CQL-16 |
| Schema | SCH-1 … SCH-5 | SCH-6, SCH-7, SCH-8 |
| Security | SEC-1, SEC-2, SEC-4 | SEC-3, SEC-5 |
| Alerts | ALR-2 (server warnings shown) | ALR-1, 3, 4, 5 |
| NFR | NFR-SAFE, NFR-AUD, NFR-SEC, NFR-DATA, NFR-UX (theme, shortcuts), NFR-LIC | NFR-SIGN (needs certificates), NFR-SCALE, NFR-A11Y review, NFR-UPD |

## Verified

| Check | Result |
|---|---|
| Engine unit tests | 67 passing |
| UI unit tests | 9 passing |
| Integration tests on real Cassandra 3.11 / 4.1 / 5.0 | Plain CQL: 6 per version. TLS + PasswordAuthenticator + CassandraAuthorizer: 2 per version |
| OS keychain | CI on Windows and macOS runners |
| Packaged app | CI starts the bundled engine from each OS's installer build |
| Security | CodeQL (Java, TypeScript), Trivy (npm and Java dependencies), npm audit, SBOM |
| Browser test, 3-node 2-DC cluster (4.1) + 3.11 node | Passing locally and in CI: overview, PROD confirm, query pinned to remote-DC node, schema, audit |
| Installers | v0.1.0-alpha.1 published for Windows, macOS (arm64, x64), Linux; not yet opened on a real Windows PC or Mac |

## Waiting on the owner

1. Decisions Q-1 … Q-6 (requirement.txt §6).
2. Code-signing: Apple Developer ID and a Windows code-signing certificate.
3. A non-prod cluster from the estate for a pilot (CQL and SSH access).
