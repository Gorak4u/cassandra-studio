# Config API: effective config and drift (CFG-1, CFG-2)

Read-only towards the cluster, so no ActionGuard. Collecting runs as a job (`docs/api/jobs.md`, kind
`config-collect`, not audited); the last snapshot per connection is kept in memory until disconnect.

Shapes: `engine/.../config/ConfigModel.java` = `ui/src/panels/config/configTypes.ts`. Null fields are
left out of the JSON.

| Method | Path | Returns | Notes |
|---|---|---|---|
| POST | `/api/clusters/{id}/config/collect` | `Job` (202) | Reads every node in parallel; 90 s limit per node. |
| GET | `/api/clusters/{id}/config/snapshot` | `Snapshot` | 404 `not_collected` before the first collect. |
| GET | `/api/clusters/{id}/config/drift?scope=cluster\|dc&onlyDifferences=true&hiera=true` | `DriftReport` | 400 for another scope; 404 `not_collected`. |
| GET | `/api/clusters/{id}/config/hiera` | `HieraSettings` | Defaults: repo `/home/user/cassandra-control-repo`, `product=cassandra`, on when the repo exists. |
| PUT | `/api/clusters/{id}/config/hiera` | `HieraSettings` | Stored in `settings` as `config.hiera/<id>`. 400: relative path, values with `/`, `..` or `%{`. |
| GET | `/api/clusters/{id}/config/hiera/options?repoPath=` | `HieraOptions` | Values each fact takes in the repo (for pickers); `found=false` + `error` when unreadable. |

## What is read (CFG-1)

| Category | Source |
|---|---|
| `yaml` | 4.0+: `SELECT name, value FROM system_views.settings` pinned to the node (`Statement.setNode`; virtual tables are node-local). 3.x, or when that fails: the file over SSH, path from the JVM property `cassandra.config` (default `/etc/cassandra/cassandra.yaml`), parsed by `YamlLite`, with the runtime values JMX exposes laid over it (compaction/stream throughput, timeouts, hints, tombstone thresholds ...). No SSH: those JMX values only, with a notice. |
| `jvm` | JMX: input arguments (`-Xmx`, `-XX:Flag`, `-Dprop`, agents), `sys.*` system properties (java.*, os.*, every `cassandra.*`), `vm.*` HotSpot flags via `getVMOption`, `runtime.*` VM vendor/version. The node's own address in a value reads `<self>`. |
| `os` | SSH: `/proc/<pid>/limits` of `CassandraDaemon` (else `ulimit -S/-H -a` of the login shell, marked as such), `vm.max_map_count`, `vm.swappiness`, swap devices and size, transparent huge pages (`enabled`, `defrag`), CPUs. JMX fills `process.max_open_files`, `memory.total`, `swap.total`, `cpu.count` when SSH did not. |

Whatever cannot be read becomes a `notices` entry on the node; the rest is still returned.

### Normalisation across versions

Names map to the newest name using Cassandra's own `@Replaces` table (4.1.12 and 5.0.9), e.g.
`read_request_timeout_in_ms` -> `read_request_timeout`, `enable_user_defined_functions` ->
`user_defined_functions_enabled`, `compaction_large_partition_warning_threshold_mb` ->
`partition_size_warn_threshold`. When a node lists both names (4.1 does), the newer one wins. Values:
durations, sizes and rates are rendered in the largest whole unit (`max_hint_window_in_ms: 10800000`
and `max_hint_window: 10800000ms` both read `3h`; megabit rates become bytes per second), booleans
lower case, `org.apache.cassandra.*` class names short, seeds a sorted set (`seed_provider.seeds`).
Passwords and key/trust store paths read `<REDACTED>`. `rawName`/`raw` keep what the node reported.

## Drift (CFG-2)

A `DriftRow` per setting: `values` by node address, `missingOn` (not reported: another version or
partly readable, never counted as drift), `differsInCluster`, `dcsDiffering`, and with Hiera
`expected`/`expectedSource` per node and `mismatches`. Per-node settings (`listen_address`,
`rpc_address`, `broadcast_*`, `initial_token`, interfaces, `-Djava.rmi.server.hostname` ...) are
shown with `perNode=true` and never count. `onlyDifferences` keeps rows that differ in the chosen
scope (`cluster`: between any nodes; `dc`: inside one DC) or differ from Hiera.

### Hiera comparison

`hiera.yaml`'s plain YAML levels are resolved first-match (eyaml levels are skipped) with friendly
facts: `customer`, `environment`, `product`, `cluster`, `datacenter` (blank = each node's DC), `role`,
`certname` (per node, `certnames` by address), `os.name`, `os.family`, `os.release.major`; each fills
the trusted-extension and fact variables of the hierarchy, and any other variable can be given by its
own name. Keys map to settings through `cassandra.yaml.erb` (`key: <%= @var %>unit` lines,
`data_file_directories`, the seed list, `cassandra_yaml_extra_options`) and the profile's
`$var = lookup('profile::key', {'default_value' => ...})` / `param => $var` lines, so profile
defaults count (source `module default (key)`). Values behind `<% if @var %>` are skipped when false.
`hiera.layersByNode` lists the data files that matched; `hiera.error` says why the comparison could
not run.
