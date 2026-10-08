# Security

## Reporting a problem

Please report suspected vulnerabilities privately to the repository owner, not in a
public issue. Include the version, your OS, and steps to reproduce.

## How Studio protects clusters and credentials

- **Local engine only.** The engine listens on 127.0.0.1. Every API call needs a random
  per-launch token, and requests with a foreign `Host` header are refused, which blocks
  DNS-rebinding attacks from web pages.
- **Secrets.** Cassandra, JMX and SSH passwords and key passphrases are kept in the OS
  keychain (Windows Credential Manager, macOS Keychain, Linux Secret Service). If no
  keychain is available, they go into an AES-GCM encrypted file readable only by the
  user. The UI shows which store is in use. Connection exports never contain secrets.
- **Changes are guarded.** Any statement that writes data or changes schema or access is
  shown in full before it runs. PROD connections require typing the connection name.
  Read-only connections refuse changes in the engine, not just in the UI.
- **Audit.** Every change, and every blocked attempt, is written to a local audit log.
  Passwords in `CREATE/ALTER ROLE` statements are masked in history and audit.
- **Desktop shell.** The window runs with context isolation, sandboxing and no Node
  integration, and cannot navigate away from the engine's origin.
- **Supply chain.** CI audits UI dependencies and produces a CycloneDX SBOM for the
  engine. Installers are code-signed once certificates are configured
  (`MAC_CERT_P12_BASE64`, `WIN_CERT_PFX_BASE64` and related secrets).
