# 4. SQLite with append-only migrations

Status: accepted (2026-10-08)

## Decision
Studio's own data (connections, history, audit, saved scripts) is in SQLite with numbered,
append-only migrations recorded in `schema_version`. A database newer than the running Studio
is refused, so a downgrade never misreads or corrupts it (NFR-DATA). Studio Server will use
PostgreSQL behind the same repository classes.
