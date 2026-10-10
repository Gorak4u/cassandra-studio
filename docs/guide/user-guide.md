# Cassandra Studio user guide

This guide covers Cassandra Studio 1.0, the desktop app. It describes what the app does today.
Anything that is not built yet is listed under [Limits in v1.0](#limits-in-v10).

Related guides:

- [Install and admin guide](install.md): installers, data folders, upgrades, node prerequisites, troubleshooting
- [Runbooks](runbooks/README.md): step-by-step guides for every operation wizard
- [Release notes 1.0.0](../release-notes/v1.0.0.md)

In the app, the **?** button in a panel's header opens the part of this guide for that panel.
The guide is part of the app, so it works without network access.

## Getting started

1. Start Cassandra Studio. The window opens with an empty connection tree on the left.
2. Click **+ Connection**. Enter a name and the contact points (`host` or `host:port`, comma
   separated) on the **CQL** tab. Add a username and password if the cluster uses
   PasswordAuthenticator, and TLS settings if it uses client encryption.
3. Click **Test**. A good test shows the cluster name, Cassandra version, node count and protocol.
4. Click **Save**, then double-click the connection (or click its ▶ button) to open it.

An open connection is a tab across the top. Each one has its own panels: Overview, Monitoring,
Query, Schema, Users & roles, Operations, Diagnostics, GC logs, Config, Backups, Bulk and History.
You can open several clusters at once (CON-11). Closing a tab (×) disconnects that cluster.

Studio talks to Cassandra over CQL (port 9042 by default) for queries, schema and roles. Monitoring,
operations, diagnostics, GC logs, config and backups also need JMX, SSH or both: see
[JMX and SSH access](#jmx-and-ssh-access).

## Connections and folders

The tree on the left keeps every saved connection (CON-1, CON-2).

- **Folders.** Click **+ Folder** for a top-level folder, or use the 📁+ button on a folder for a
  sub-folder. A common layout is customer > environment > cluster. Rename (✎) and delete (🗑) are on
  each folder's row. Deleting a folder deletes its sub-folders; connections inside move to the top level.
- **Moving.** Drag a connection onto a folder, or onto the empty part of the tree for the top level.
- **Search.** The search box matches connection names, tags, contact points and environment names
  (type `PROD` to see every production cluster). Folders that contain a match stay visible.
- **Connection buttons.** ▶ open, ✎ edit, ⧉ clone (a copy named "… (copy)", with its stored secrets), 🗑 delete (also
  deletes the connection's stored secrets). A green dot means the connection is open and connected;
  🔒 marks a read-only connection.

### Connection settings

The connection dialog has five tabs.

| Tab | Settings |
|---|---|
| General | Name, folder, environment (DEV, TEST, STAGING, PROD), tags, read-only flag, notes |
| CQL | Contact points, local datacenter (blank = detected), protocol version (auto, v3, v4, v5), username and password, default consistency, request timeout, page size |
| TLS | Truststore (JKS, PKCS12 or a PEM CA bundle), truststore type and password, hostname verification, optional client keystore for mutual TLS |
| JMX / metrics | How to reach JMX on the nodes, JMX port, JMX over SSL, JMX username and password, jmx_exporter port |
| SSH | User, port, authentication (SSH agent, private key file with optional passphrase, or password), jump host (bastion) and jump user, host key checking, known_hosts path |

Studio discovers every node of the cluster from the contact points, in every datacenter (CON-5).
The local datacenter only decides which nodes are preferred as coordinators; nodes in other
datacenters stay reachable, for example to run a query on a chosen node.

Click **Test** before saving: it connects with the settings in the dialog, including passwords you
have typed but not saved yet.

### JMX and SSH access

Monitoring, operations, diagnostics and parts of config, GC logs and backups read JMX on each node
(CON-6). Pick how Studio reaches it on the **JMX / metrics** tab:

| Method | Use when | What works |
|---|---|---|
| SSH tunnel to localhost:7199 | JMX listens on the node's localhost only (`LOCAL_JMX=yes`, the Cassandra default and the estate's setup) | Everything |
| Direct JMX/RMI | JMX is reachable over the network (`LOCAL_JMX=no`) | Everything that needs JMX. Disk usage and other SSH-based features also need the SSH tab filled in |
| jmx_exporter HTTP endpoint | Only a Prometheus jmx_exporter is reachable (port 7071 by default) | Monitoring only: no operations, diagnostics or GC log discovery |
| Apache Cassandra Sidecar | — | Listed, but not supported in v1.0: choose another method |
| None | CQL only | Query, schema, roles, bulk |

With **SSH tunnel**, Studio opens one SSH session per node (through the jump host if one is set)
and forwards JMX through it. The same SSH session is used for commands on the node: disk usage,
reading `cassandra.yaml` and OS limits, GC log files, `system.log` warnings, the tombstone scan
script and backup scripts.

If a node's JMX fails and the node runs jmx_exporter, Monitoring falls back to the exporter for
that node automatically (CON-7) and says so in the node's route. JMX is used again as soon as it
answers.

What the nodes need (JMX settings, SSH user, sudo for the estate scripts) is in the
[install guide](install.md#node-prerequisites-ssh-and-jmx).

### Import and export

**Export connections** (top bar) saves every folder and connection to
`cassandra-studio-connections.json`. **Passwords and other secrets are never in the file.**
**Import** reads such a file and adds its folders and connections; enter the passwords per
connection afterwards (CON-9).

## Secrets

Passwords, the JMX password, SSH passwords and key passphrases, and truststore and keystore
passwords are kept in the operating system's keychain (CON-8, NFR-SEC):

- Windows: Credential Manager
- macOS: Keychain
- Linux: the Secret Service (GNOME Keyring, KWallet with its Secret Service bridge, and similar)

If no keychain works (for example a Linux machine without a keyring daemon), Studio uses an
AES-GCM encrypted file in its data folder instead. That is weaker: anyone who can read both
`secret.key` and `secrets.json` can read the secrets. The footer of the connection tree shows which
store is in use ("Secrets: …").

In the connection dialog a stored secret shows "(stored in keychain — leave blank to keep)". Leave
the field blank to keep it, type a new value to replace it, or click **Clear** to remove it when you
save. Secrets are never shown again, never written to the database or to export files, and are
masked in the audit log.

Studio sends no telemetry. Nothing leaves your machine except connections to your clusters
(CQL, JMX, SSH, jmx_exporter).

## Environments, PROD and read-only safety

Every connection has an environment: DEV, TEST, STAGING or PROD (CON-3). It is shown as a coloured
badge on the connection, its tab and in the audit log.

Every change goes through one safety check (NFR-SAFE) in the engine, whichever panel starts it:
CQL writes and DDL, grid edits, role changes, nodetool operations, snapshots, backups and bulk loads.

1. **Read-only connections refuse it.** Tick *Read-only* in the connection's General tab (CON-12).
   The connection shows a 🔒 banner, and every write, DDL statement and operation is refused, also
   when typed by hand in the editor. The refusal is recorded in the audit log as BLOCKED.
2. **You see exactly what will run.** Before anything runs, a dialog shows the exact CQL statements
   or the nodetool-equivalent command for each node, plus warnings (for example "TRUNCATE deletes
   ALL data in the table on every node", or that a repair streams data). Nothing runs until you click
   **Run**.
3. **PROD needs the connection name typed.** On a PROD connection a red banner stays at the top of
   the workspace, and the confirmation dialog asks you to type the connection's name before **Run**
   is enabled. A wrong name is refused and audited.

Destructive actions (DROP, TRUNCATE, scrub with *skip corrupted*, clearing snapshots) are shown
with a red **Run** button.

The check runs in the engine, so the UI cannot skip it. There is no per-user access control in
v1.0: everyone who can open Studio may run every operation; the confirmations and the audit log
are there to prevent accidents.

## Overview

The Overview panel shows what the driver knows about the cluster (MON-10, MON-11): health
(GREEN, YELLOW when nodes disagree on the schema, RED when a node is down), nodes up, datacenters,
schema agreement, Cassandra versions, partitioner and protocol version, and a table of every node
with state, address, DC, rack, version, tokens, host ID and schema version.

It warns when the cluster runs mixed Cassandra versions (an upgrade in progress?) and when nodes
disagree on the schema. Click **⟳ Refresh** to read it again; the node list also refreshes every
15 seconds while the window is visible.

## CQL editor

The Query panel is a multi-tab CQL editor (CQL-1 … CQL-10).

- **Tabs.** **+** opens a new tab. Each tab keeps its own text and results.
- **Highlighting and completion.** CQL keywords are highlighted; completion offers keyspaces,
  tables and columns from the cluster's schema, for the keyspace in the toolbar.
- **Run.** **▶ Run** (Ctrl+Enter, Cmd+Enter on macOS) runs the selection, or the statement under
  the cursor when nothing is selected. **▶▶ Run script** (Ctrl+Shift+Enter) runs the whole tab,
  statement by statement. With *Stop on error* ticked a script stops at the first failure.
- **Toolbar.** Keyspace, coordinator **Node** (Auto = load balanced, or one node of any DC; CQL-3),
  consistency level (**CL**), page size and **Tracing**.
- **Shell commands.** `USE`, `CONSISTENCY`, `TRACING` and `DESCRIBE` work as in cqlsh, on every
  Cassandra version.
- **Files.** **Open .cql** and **Save .cql** read and write files on your computer.

Statements that change data or schema go through the confirmation described in
[Environments, PROD and read-only safety](#environments-prod-and-read-only-safety). Reads that may
hurt the cluster show a warning: `ALLOW FILTERING`, a `SELECT` without `WHERE` (full table scan)
and unlogged batches across partitions.

### Results, paging and export

Each statement of a run gets its own result tab with:

- **Result**: a sortable, filterable grid. When more rows exist, **Next page** fetches one more
  page and **Fetch all** up to 10,000 more rows, with the same keyspace, consistency, node and
  page size as the original run.
- **Messages**: errors, client warnings and server warnings (for example tombstone or batch size
  warnings; ALR-2).
- **Trace** (when tracing was on): every trace event with elapsed time, source node and thread.
- **CSV** and **JSON** export of the rows fetched so far.

The status line shows the statement kind, row count, duration and the coordinator that served it.

### Editing data in the grid

When a result comes from one non-system table and the connection is not read-only, **✎ Edit data**
turns on editing (CQL-9). The query must select the full primary key.

- Edit cells (primary key columns are not editable), **+ Row** to add a row, select rows and
  **Delete selected** to delete them.
- **Apply N change(s)** generates one `INSERT`, `UPDATE` or `DELETE` per row and shows them in the
  confirmation dialog before anything is written. **Cancel** throws the edits away.

### History and saved scripts

- **History panel**: every statement run on this connection, searchable, with time, keyspace, node,
  rows, duration and error (CQL-8). **Open** puts a statement back into a new editor tab; **Clear**
  deletes this connection's history.
- **Script library**: **Save to library** stores the current tab under a name and folder (for
  example `ops/daily`); **Scripts…** lists, opens and deletes saved scripts. Scripts are shared by
  all connections.

## Schema

The Schema panel is a tree of keyspaces with their tables, views, types, indexes (secondary, SASI,
SAI), functions and aggregates (SCH-1). Tick **System** to show system keyspaces; **⟳** reloads the
schema from the cluster.

- **Keyspace details** (SCH-2): replication class and factor per DC, durable writes, number of
  tables, and the keyspace's DDL. A warning appears for SimpleStrategy on a non-system keyspace.
- **Table details** (SCH-3): columns with type and role (partition key, clustering with order,
  static, regular), indexes, table options and DDL. A warning appears when `gc_grace_seconds` is 0.
- **Forms** (SCH-4): **+ Keyspace**, **+ Table** (with columns, keys and clustering order), drop
  keyspace, drop table, truncate, drop index. Each form ends in **Review CQL…**: the generated
  statement is shown in the confirmation dialog before it runs.
- **DDL** (SCH-5): **Copy** or **Open in editor** for any keyspace or table.
- Double-click a table, or click **Query**, to open `SELECT * … LIMIT 100` in the editor.

## Users and roles

The Users & roles panel lists every role with login and superuser flags (SEC-1). Select a role to
see its memberships and its **effective permissions**, including those inherited through roles
(SEC-4).

- **+ Role**: create a role with password, login and superuser options.
- **Change password / login**, **Grant…** (a permission on all keyspaces, a keyspace, a table, all
  roles, a role, all functions or all MBeans; or membership of another role), **revoke** on a
  permission row, and **Drop** (SEC-2).
- Every change shows the CQL first; passwords are masked in the confirmation and the audit log.
- Warnings appear when `system_auth` is replicated too thinly for the cluster (SEC-3), and when
  permissions cannot be listed (for example with AllowAllAuthorizer).

## Monitoring

The Monitoring panel shows live JMX metrics for every node (MON-1 … MON-18). Opening it starts
polling this cluster; polling continues while the cluster is open, until you click **⏸ Pause** or
close the cluster's tab. Only open clusters are polled (NFR-PERF). Choose the interval (5, 10, 30
or 60 s) next to the pause button; **⟳** polls now.

Views:

- **Health**: overall level (GREEN, YELLOW, RED), the active alerts with their reasons, and a
  summary per node. Click a node for its details.
- **Nodes**: one sortable row per node with DC, rack, health, state, version, Java, uptime, load,
  tokens, heap, GC %, CPU %, pending compactions, hints, dropped messages, read/write rates and p99
  latencies. Click a row for the node drawer: host ID, DC and rack, route (how JMX is reached),
  Cassandra and Java versions, uptime, heap and off-heap, load and tokens, CPU, open file
  descriptors, compactions, hints, SSTables, GC time, thread pools, garbage collectors, data
  directories (disk used and free), client request latency and dropped messages by verb.
- **Charts**: heap used vs max, GC time, GC pause, CPU, client reads and writes, read and write
  latency (p99 and p50), timeouts and unavailables, pending compactions, hints in progress,
  dropped messages, and thread pools pending/blocked. Choose 15 min, 1 h, 6 h or 24 h; click a
  node's chip to hide or show it. Each chart exports as **PNG** or **CSV** (MON-4).
- **Ring**: the token ring per datacenter with each node's ownership (effective ownership for a
  chosen keyspace; MON-13, MON-14).
- **Tables**: per-table reads, writes, p99 latencies, live and total disk, SSTables, mean and max
  partition size, tombstones and SSTables per read, bloom filter false positives, pending
  compactions and key cache hit rate (MON-18). Read on request, not on every poll.
- **Thresholds**: the health rule thresholds for this cluster (below).

History is kept in memory for 24 hours (one point per poll, one per minute after the first hour)
and is lost when Studio quits. Disk usage comes from `df` over SSH, so it needs the SSH tunnel
method or SSH settings.

If JMX fails on some nodes, a banner names them and the reason, and their metrics show as n/a. A
node that cannot be read is shown as unreachable and retried with back-off; it never blocks the
other nodes or the UI (NFR-RELI).

### Health rules and thresholds

Twelve rules are evaluated on every poll (ALR-1). Overall health is the worst active alert.

| Rule | Level | Fires when (default) | Threshold key |
|---|---|---|---|
| `node.down` | RED | a node's state is not UN | — |
| `node.unreachable` | YELLOW | Studio cannot read the node's JMX or exporter (the node may be fine) | — |
| `schema.disagreement` | YELLOW | more than one schema version among up nodes | — |
| `heap.high` | YELLOW / RED | heap used above 85 % / 95 % of max | `heap.high.yellowPct`, `heap.high.redPct` |
| `gc.pressure` | YELLOW / RED | GC time above 10 % / 25 % of wall time between polls | `gc.pressure.yellowPct`, `gc.pressure.redPct` |
| `dropped.messages` | YELLOW | any dropped-message counter increased since the previous poll | — |
| `client.timeouts` | YELLOW | read/write timeouts or unavailables increased since the previous poll | — |
| `compaction.backlog` | YELLOW | more than 100 pending compactions | `compaction.backlog.pending` |
| `threadpool.blocked` | YELLOW | a thread pool has blocked tasks, or its all-time-blocked count increased | — |
| `disk.usage` | YELLOW / RED | a data directory above 80 % / 90 % full | `disk.usage.yellowPct`, `disk.usage.redPct` |
| `load.imbalance` | YELLOW | a node's load above 1.5 × its DC's average (DCs with 2+ nodes) | `load.imbalance.factor` |
| `hints.backlog` | YELLOW | hints in progress for 3 consecutive polls | `hints.backlog.polls` |

To change a threshold for one cluster, open **Monitoring → Thresholds**, edit the values and click
**Save**. The page shows the effective values; empty a field to go back to the default. Changes
apply from the next poll. A node in an unknown state (`?N`, nobody knows its state) is shown in
amber as unknown, not as down.

## Operations

The Operations panel runs nodetool through the GUI over JMX (OPS-1 … OPS-4). It needs the SSH
tunnel or direct JMX method; with any other method a banner says so.

Pick nodes in the node list on the left (one or more, grouped by DC), then a tab:

- **Views** (OPS-1): read-only nodetool views from the first selected node: status, info, ring,
  describecluster, tpstats, tablestats, tablehistograms, proxyhistograms, gossipinfo,
  compactionstats, netstats and getendpoints. Each view shows the equivalent nodetool command.
- **Maintenance** (OPS-2): flush, major compaction, user-defined compaction, cleanup, scrub,
  upgradesstables and garbagecollect.
- **Repair** (OPS-3): full or incremental, primary range, parallelism, datacenters, job threads,
  and an optional token sub-range, with live progress and cancel.
- **Snapshots** (OPS-4): take, list and clear snapshots.
- **Jobs**: the operations started for this cluster since Studio started, with state and progress;
  **Details** shows a job's log.

Every action shows the exact nodetool command per node in the confirmation dialog. Nodes run one
after another; a failure stops the sequence unless you tick *Continue on error*. Operation
signatures are read from each node, so the same form works on Cassandra 3.11, 4.0, 4.1 and 5.0; an
operation a node lacks fails that node with a clear message.

Each wizard has a runbook: [flush](runbooks/flush.md), [compaction](runbooks/compaction.md),
[cleanup](runbooks/cleanup.md), [scrub](runbooks/scrub.md),
[upgradesstables](runbooks/upgradesstables.md), [garbagecollect](runbooks/garbagecollect.md),
[repair](runbooks/repair.md) and [snapshots](runbooks/snapshots.md).

## Diagnostics

The Diagnostics panel has two tabs (JVM-1, JVM-2, PRF-1, PRF-2). Everything here is read-only
towards the cluster.

**Threads** (needs JMX; pick the node at the top):

- **Thread dumps**: **Take thread dump**, or **Take series** (2–20 dumps, 1–300 s apart). A dump
  shows threads by state, deadlocks (as "A waits for X held by B" chains), blocked threads with
  the owners of the lock they wait for, and threads grouped by identical stack. Filter by name,
  frame or state; **Export .txt** saves it in jstack format. **Compare** two dumps of the same node
  to see new, gone and changed threads and threads stuck on the same stack. Studio keeps the
  newest 40 dumps per cluster in memory.
- **Top threads (live)**: CPU and user time, allocation rate and total CPU per thread, like
  `sjk ttop`, refreshed live; *Group thread pools* merges pools such as `ReadStage-12`.

**Partitions**:

- **Table histograms**: partition size, cell count, tombstones, SSTables and live cells per read
  for every table, per node or merged (worst value). Tables with partitions above the large
  partition limit (default 100 MiB) or p99 tombstones per read above the limit (default 1000) are
  flagged and listed first.
- **Hot partitions**: samples chosen tables on every node at once for 1–600 s, like
  `nodetool toppartitions`, and lists the top keys for reads and writes (and write sizes on 4.0+).
- **Warnings from system.log**: reads the log over SSH on every node and lists tombstone warnings
  and aborts and large partition warnings, with table, key and size. Rotated (zipped) logs are not
  read.
- **Tombstone scan (estate script)**: runs the estate's `tombstone-scan.sh` on a node over SSH;
  fails with a clear message when the script is not installed.
- **Settings**: the `system.log` path, large partition limit, tombstone limit and the path of the
  tombstone scan script, per cluster.

See the [thread dump runbook](runbooks/thread-dump-investigation.md).

## GC logs

The GC logs panel analyses JVM garbage collector logs, like GCViewer (GCL-1 … GCL-4).

1. **Load.** Pick a node and click **Find GC logs**: Studio reads the node's JVM arguments over
   JMX to find the configured log file and lists it and the usual locations over SSH. Select files
   (current and rotated) and a size cap (default 200 MB; the newest files win), then **Load**. Or
   upload a file from your computer: plain text, `.gz`, or a `.zip` of rotated files (up to 1 GB
   uncompressed).
2. **Summary & findings**: pause statistics (count, total, average, max, p50/p95/p99), pause
   histogram, GC time and throughput, full GCs and their causes, heap and metaspace peaks,
   allocation and promotion rates, safepoints, and **findings** with tuning hints, most severe
   first (19 rules: long pauses, high GC time, full GCs from metaspace or `System.gc()`, heap too
   small, humongous allocations, evacuation and concurrent mode failures, premature promotion,
   allocation stalls, slow time-to-safepoint, CMS deprecation and others). Each finding names the
   options file for the node's Java version (`jvm.options`, `jvm11-server.options`,
   `jvm17-server.options`).
3. **Charts**: pauses over time, heap before/after, GC time %, allocation and promotion rates.
   Select a time window and click **Analyse …** to recompute everything for that window;
   **Whole log** goes back.
4. **Events**: every collection and phase. **Export JSON** saves the report, **Export CSV** the
   events.

Supported: Java 8 logs (`-XX:+PrintGCDetails`) and Java 9+ unified logging; CMS/ParNew, G1,
Parallel, Serial, ZGC (also generational), Shenandoah. The last 6 analyses per cluster are kept in
memory until you disconnect. See the [GC log runbook](runbooks/gc-log-investigation.md).

## Config and drift

The Config panel shows each node's effective configuration and where nodes differ (CFG-1, CFG-2).
Click **Collect config** to read every node (a job, 90 s limit per node):

- `cassandra.yaml` settings: on 4.0+ from `system_views.settings`; on 3.x (or when that fails)
  the file over SSH, with the runtime values JMX exposes laid over it.
- JVM: arguments, system properties, HotSpot flags and VM version, over JMX.
- OS: open-file and process limits of the Cassandra process, `vm.max_map_count`,
  `vm.swappiness`, swap, transparent huge pages and CPUs, over SSH.

Names are normalised to the newest Cassandra name (for example `read_request_timeout_in_ms` and
`read_request_timeout` are one setting) and values to one unit, so 3.11 and 5.0 nodes compare
correctly. Passwords and keystore paths read `<REDACTED>`. Whatever a node could not provide is
listed in a notice; the rest is still shown.

Views:

- **Settings per node**: every setting as a row and every node as a column; search, filter by
  category, *Only differences*, **Export CSV**.
- **Drift report**: settings that differ across the cluster, or *Within each DC*. Per-node
  settings (addresses, tokens, interfaces) are shown but never count as drift. **Export CSV**.
- **Hiera comparison**: compare against the Puppet control repo (below).

### Hiera comparison

If you have a local checkout of the Puppet control repo (cassandra-control-repo), Studio can
compare each node's values with what Hiera says they should be. Open **Hiera comparison**, set the
**Control repo** path and click **Scan**, then fill in the facts the hierarchy uses (customer,
environment, product, cluster, datacenter, role, certname per node, OS facts; the pickers offer the
values found in the repo) and click **Save**. Then tick **Compare with Hiera** in the drift report:
an *Expected (Hiera)* column appears and rows that differ from Hiera are marked.

Only plain YAML levels of `hiera.yaml` are read (eyaml levels are skipped), first match wins, and
module defaults from the Cassandra profile count. If the comparison cannot run, the report says why.
See the [drift runbook](runbooks/drift-investigation.md).

## Backups

The Backups panel lists backups and runs them through a **provider chosen per cluster** (BAK-1 …
BAK-3):

| Provider | How it runs | Types |
|---|---|---|
| Estate scripts | The estate's backup scripts on each node over SSH (`full-backup-to-s3.sh`, `incremental-backup-to-s3.sh`, with `/etc/backup/config.json`); storage backends s3, gcs, azure and local | full, incremental |
| Medusa | The `medusa` CLI on the nodes over SSH (`backup-node`, same backup name on every node) | full, differential |
| Snapshots | `nodetool snapshot` over JMX, kept on the nodes | snapshot |

1. **Provider.** Click **Detect on nodes**: Studio looks for the estate scripts and their config,
   Medusa and `sudo -n` over SSH, and JMX for snapshots, and recommends a provider. Adjust the
   script directory, config file, Medusa command and config, **Run as** (root via `sudo -n`, the
   estate default, or the SSH user) and the per-node time limit, then **Save**.
2. **Catalogue** (BAK-2): every backup with node, type, time, size, schema version, status
   (COMPLETE, INCOMPLETE, UNKNOWN), location, retention and object lock, filtered by node, type
   and status. "unknown" means the provider does not tell. With the snapshot provider, **Clear…**
   deletes a snapshot.
3. **Run a backup now** (BAK-3): whole cluster, one DC or one node; type; nodes at a time;
   optional throttle, name or tag and keyspaces. See the
   [backup runbook](runbooks/backup-run-now.md).

Restore, verification and schedules are not in v1.0.

## Bulk unload and load

The Bulk panel is a DSBulk equivalent built into Studio (BLK-1, BLK-2). Files are read and
written **on your computer**; a relative path is under your Downloads folder.

- **Unload** a whole table (token ranges read in parallel) or a `SELECT` query to CSV or JSON
  lines, optionally gzip-compressed, with column choice, delimiter, null string, date/time formats,
  time zone, blob format, consistency, page size, concurrency and a row limit. Unload is read-only.
- **Load** CSV or JSON lines (also `.gz`) into a table: **Preview file** shows the file's columns,
  sample rows and a suggested mapping to table columns; set TTL and write timestamp (fixed or from
  a column), batch size, concurrency, rate limit, max errors and consistency. **Validate (dry run)**
  converts every row without writing; **Load** writes after the confirmation. Rows that fail go to
  `<file>.rejected.csv` (or `.jsonl`) with reasons in `<file>.rejected.log`.
- **Current job** shows live rows, rate, bytes and errors; **Recent bulk jobs** lists earlier runs.

Counter tables cannot be loaded. S3/GCS targets are not supported in v1.0. Runbooks:
[unload](runbooks/bulk-unload.md), [load](runbooks/bulk-load.md).

## Jobs

Anything that takes more than a moment runs as a **job**: maintenance operations, repairs,
snapshots, backups, config collection, GC log loading, thread dump series, hot partition sampling,
tombstone scans and bulk unloads and loads. A job shows its state (RUNNING, SUCCEEDED, FAILED,
CANCELLED), a progress bar, the current message and a log you can expand. **Cancel** appears when
the job can be stopped safely.

- Operations jobs are listed in **Operations → Jobs**, bulk jobs under **Bulk → Recent bulk jobs**.
- Jobs and their logs are kept in memory (the newest 200 finished jobs) and are gone after Studio
  restarts. The audit log keeps the record of every change.
- Quitting Studio does not stop work that is already running on a node (a compaction, a repair, a
  backup script): Studio only stops following it. Check the node with **Operations → Views →
  compactionstats** or `nodetool` afterwards.

## Audit log

**Audit log** in the top bar opens the local audit log of every change across all connections
(NFR-AUD): when, who (your OS user name), connection and environment, node, category and action, the
exact statement or command, and the outcome (SUCCESS, FAILED or BLOCKED, with the error). Refused attempts
(read-only connection, wrong PROD name) are recorded as BLOCKED. Passwords are masked.

Search with the box; **Export** saves the entries shown as JSON. The audit log lives in Studio's
local database (see the [install guide](install.md#data-and-config-locations)); there is no central
audit server in v1.0.

## Keyboard shortcuts

| Keys | Where | Action |
|---|---|---|
| Ctrl+Enter (Cmd+Enter on macOS) | CQL editor | Run the selection, or the statement under the cursor |
| Ctrl+Shift+Enter (Cmd+Shift+Enter) | CQL editor | Run the whole script |
| Ctrl+Space | CQL editor | Show completions |
| Escape | Dialogs, node drawer, help | Close |
| Left / Right arrow, Home, End | Monitoring, Operations, Diagnostics, GC log and Bulk tab lists | Move between views |
| Tab / Shift+Tab | Everywhere | Move between controls; every control is reachable by keyboard |

The editor also has Monaco's usual editing keys (for example Ctrl+F to find, Ctrl+/ to comment
lines).

## Themes and accessibility

The ☾ / ☀ button in the top bar switches between the light and dark theme (NFR-UX). Studio starts
in your operating system's preference and remembers your choice. Charts, the editor and the grids
follow the theme.

Studio is built to WCAG 2.1 AA: every control has a label for screen readers, everything works by
keyboard, and state is never shown by colour alone (levels are also written out, chart lines differ
in dash style). Every main screen is checked with axe in CI.

## Limits in v1.0

- No Studio Server: no shared catalogue, central audit, scheduled repairs or backups, alert
  notifications or single sign-on (planned for v1.1).
- No access control between users (decision Q-1); the confirmations and the audit log remain.
- Backups: no restore, verification or schedules; Medusa is supported but has not been tested
  against a live Medusa install.
- Bulk: local files only (no S3/GCS), no row counts or cluster-to-cluster copy.
- Monitoring history is in memory (24 h) and lost on restart. Disk usage needs SSH.
- The Apache Cassandra Sidecar access method is not implemented.
- Jobs, thread dumps and GC log analyses are kept in memory only.
- Node addresses must be reachable from your machine: there is no address mapping for private
  cloud IPs yet (use a VPN or run Studio inside that network).
- Installers are not code-signed until certificates are in place; see the
  [install guide](install.md#installers).
