# Threat model (v0.1)

Scope: the desktop app (Electron shell, local engine, UI) and its connections to clusters.
Method: STRIDE over the data flows. Review with each release that adds an attack surface
(JMX/SSH in Phase 2, Studio Server in Phase 4).

## Assets
Cluster credentials (CQL, JMX, SSH); the clusters' data and schema; the audit log; the user's machine.

## Data flows and trust boundaries
1. UI ↔ engine: HTTP on 127.0.0.1 (boundary: other local processes and web pages in browsers).
2. Engine ↔ clusters: CQL over TCP/TLS (boundary: the network).
3. Engine ↔ secret store: OS keychain or encrypted file (boundary: other local users/processes).
4. Electron ↔ UI content (boundary: renderer compromise).

| Threat | Where | Mitigation | Status |
|---|---|---|---|
| A web page in the user's browser calls the engine (CSRF / DNS rebinding) | 1 | Random per-launch token required on every call; `Host` must be 127.0.0.1/localhost; no CORS except opt-in dev mode | Done, tested |
| Another local process calls the engine | 1 | Token is only passed to Electron (stdout) and the page fragment; constant-time comparison | Done. Residual: same-user malware can read process memory. Accepted. |
| Credentials leak via export, logs, history, audit | 2, 3 | Secrets never in config/API responses; `PASSWORD '...'` masked before history/audit | Done, tested (incl. real CREATE ROLE) |
| Credentials stolen from disk | 3 | OS keychain; fallback file AES-GCM with owner-only key | Done; keychain tested on Windows/macOS in CI |
| Man-in-the-middle on CQL | 2 | TLS with JKS/PKCS12/PEM truststores, mTLS, optional hostname verification | Done, tested on 3.11/4.1/5.0 |
| Accidental or malicious destructive change | engine | ActionGuard: read-only, confirm, typed name on PROD; audit of blocked attempts | Done, tested |
| CQL/DDL injection through forms | engine | Identifiers quoted by the driver; types/options reject `;` and comments; grid values parsed by the column codec | Done, tested |
| Renderer compromise reaches the OS | 4 | contextIsolation, sandbox, no nodeIntegration, navigation locked to the engine's exact origin (parsed, not prefix-matched), only http(s) handed to the OS browser, no webviews, CSP | Done (hardened after code review) |
| Tampered installer | supply chain | Checksums published; code signing when certificates exist; SBOM; dependency scanning; CodeQL | Signing pending certificates |
| Known-vulnerable dependency | supply chain | Trivy on every push fails the build on HIGH/CRITICAL with a fix; first scan found 8 (Netty, end-of-life Jetty 11), all fixed | Done |
| Denial of service on a cluster from Studio | 2 | Page size limits, 100k row cap per statement, full-scan warnings | Done. Rate limits come with bulk tools (Phase 3). |

## Open items
- Code signing certificates (owner).
- Phase 2: SSH host-key verification UX, JMX credentials, tunnel lifetime.
- Phase 4: Studio Server authN/Z, multi-user audit integrity, network exposure.
