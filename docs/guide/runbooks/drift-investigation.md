# Runbook: drift investigation

Finds settings that differ between nodes, or differ from what Puppet Hiera says they should be.
Panel: **Config** (CFG-1, CFG-2).

## When to use

- One node behaves differently from the others (latency, GC, compaction, timeouts).
- After a rolling change, upgrade or node replacement, to check every node got the same settings.
- Before an upgrade, to find settings that were changed by hand.

## Before you start

- Read-only: collecting config changes nothing on the nodes and needs no confirmation.
- Needs JMX (SSH tunnel or direct) for JVM settings and runtime values; SSH for OS limits and, on
  Cassandra 3.x, for reading `cassandra.yaml`. Whatever cannot be read is listed as a notice per
  node; the rest is still compared.
- For the Hiera comparison you need a local checkout of the control repo on your computer.

## Steps in Studio

1. Open **Config** and click **Collect config** (or **Collect again** for fresh values). A job
   reads every node in parallel (90 s limit per node).
2. Open **Drift report**. Choose the **Scope**: *Cluster* (differences between any nodes) or
   *Within each DC* (differences inside a datacenter; use this when DCs are meant to differ). Keep
   *Only differences* ticked.
3. Read the rows: each shows the setting's category (yaml, jvm, os), its value on each node and the
   status. Per-node settings such as addresses and tokens are shown but never count as drift.
   Settings a node does not report (another version, or not readable) are marked missing, not drift.
4. Optional, Hiera: open **Hiera comparison**, set the **Control repo** path, click **Scan**, fill
   in the facts (customer, environment, product, cluster, datacenter, role, certname per node, OS
   facts) and **Save**. Back in **Drift report**, tick **Compare with Hiera**. The *Expected
   (Hiera)* column shows what Hiera resolves for each node and where it comes from (a data file, or
   the module default); rows that differ are marked.
5. **Settings per node** shows every setting, not only differences; search for a name or value.
6. **Export CSV** saves the report for a ticket or a change request.

## What the confirmation shows

Nothing to confirm: the panel only reads.

## Verify

- A drifted setting has a different value on some nodes. Check where it comes from: hover a node's
  column header in *Settings per node* for its sources (virtual table, file over SSH, JMX).
- If *Compare with Hiera* says it cannot run, the report shows the reason (repo not found, facts
  missing, unreadable `hiera.yaml`).
- After fixing the node (Puppet run, config change and restart), click **Collect again**: the row
  should disappear from the drift report.

## Stop or roll back

- **Cancel** on the collect job stops it; nothing changes on the nodes.
- Studio does not change configuration. Fix drift through your configuration management (Puppet /
  Hiera) or the node's files, then collect again.

## Notes

- Names are normalised to the newest Cassandra name and values to one unit (for example
  `max_hint_window_in_ms: 10800000` and `max_hint_window: 10800000ms` both read `3h`), so mixed
  3.11 / 4.x / 5.0 clusters compare correctly.
- Passwords and keystore paths read `<REDACTED>`.
- Only plain YAML Hiera levels are read; eyaml levels are skipped.
