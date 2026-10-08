# 1. Electron UI with a Java engine over local HTTP

Status: accepted (2026-10-08)

## Context
Studio must speak JMX (an RMI protocol), CQL to Cassandra 3.11–5.0, and SSH, on Windows,
macOS and Linux, with a rich editor, grid and charts. The same core must later run headless
as Studio Server for team and always-on features.

## Decision
- **Engine:** Java 21. The Cassandra Java driver 4.x, JMX and Apache MINA SSHD are all native
  to Java. A jlink'd runtime is bundled, so users install nothing.
- **UI:** React + TypeScript, served by the engine itself, wrapped in Electron.
- **Between them:** HTTP/JSON on 127.0.0.1 with a per-launch bearer token. The UI's origin is
  the engine, so the same build is the web UI of Studio Server later.

## Consequences
- Two processes on the desktop; Electron starts and stops the engine (stdin EOF = exit).
- Installers are ~200 MB (Electron + Java runtime). Accepted for a desktop ops tool.
- Rejected: JavaFX single process (weak editor/grid/charts); Tauri (inconsistent Linux webviews).
