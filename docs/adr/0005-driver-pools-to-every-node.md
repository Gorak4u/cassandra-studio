# 5. Driver keeps a connection to every node in every DC

Status: accepted (2026-10-08)

## Context
The driver's default policy ignores remote-DC nodes, so "run on node X" (CQL-3) failed for a
node in another datacenter (found in the 2-DC test).

## Decision
Allow remote-DC nodes as failover targets (`dc-failover.max-nodes-per-remote-dc`) with one
connection each, but never for LOCAL_* consistency levels. Local and remote pool size is 1:
a management tool needs reach, not throughput.

## Consequence
One TCP connection per node per open cluster. At 500 nodes that is 500 connections from the
desktop; Studio Server (one shared pool) is the answer for very large estates.
