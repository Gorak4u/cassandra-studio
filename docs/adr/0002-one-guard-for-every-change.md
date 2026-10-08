# 2. One server-side guard for every change

Status: accepted (2026-10-08)

## Decision
Every action that changes a cluster (CQL write, DDL, DCL, grid edit, and later nodetool
operations, scripts and jobs) passes `ActionGuard` in the engine:
read-only connection → 403; unconfirmed → 428 with the exact statements and warnings;
PROD → the confirmation must be the connection name typed. All outcomes, including blocked
attempts, are audited.

## Why server-side
The UI cannot be trusted to enforce safety (another client, a bug, or the future REST API
could skip it). The UI only renders the 428 details and retries with the confirmation.
