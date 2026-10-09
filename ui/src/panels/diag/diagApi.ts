// Diagnostics API (docs/api/diag.md): thread dumps, top threads, hot and large partitions, tombstones.
// Mirrors engine/.../diag/*.java records.
import { request } from "../../lib/api";
import type { Job } from "../../lib/jobsTypes";

const enc = encodeURIComponent;

export interface Frame { className: string; method: string; file: string | null; line: number | null; nativeMethod: boolean; locked: string[] }
export interface ThreadEntry {
  id: number; name: string; state: string; daemon: boolean; priority: number | null; lock: string | null;
  lockOwnerId: number | null; lockOwnerName: string | null; inNative: boolean; suspended: boolean;
  blockedCount: number; waitedCount: number; stack: Frame[]; lockedSynchronizers: string[]; stackKey: string;
  deadlocked: boolean; ownerChain: string[];
}
export interface StackGroup { key: string; count: number; states: Record<string, number>; threadIds: number[]; threadNames: string[]; stack: Frame[] }
export interface Deadlock { threadIds: number[]; lines: string[] }
export interface DumpSummary {
  id: string; node: string; takenAtMs: number; jvm: string | null; seriesId: string | null; threadCount: number;
  byState: Record<string, number>; blockedCount: number; deadlockedCount: number;
}
export interface ThreadDump {
  id: string; node: string; takenAtMs: number; jvm: string | null; seriesId: string | null; threadCount: number;
  byState: Record<string, number>; blockedCount: number; deadlocks: Deadlock[]; threads: ThreadEntry[]; groups: StackGroup[];
}
export interface ThreadRef { id: number; name: string; state: string }
export interface Comparison {
  a: DumpSummary; b: DumpSummary; intervalMs: number; added: ThreadRef[]; removed: ThreadRef[];
  changed: { id: number; name: string; before: string; after: string }[];
  stuck: { id: number; name: string; state: string; likelyIdle: boolean; top: Frame[] }[];
  stateCounts: Record<string, [number, number]>;
}
export interface TopRow { name: string; id: number | null; threads: number; state: string; cpuPct: number; userPct: number; allocBytesPerSec: number | null; cpuTotalMs: number }
export interface TopView {
  node: string; atMs: number; intervalMs: number; firstSample: boolean; processCpuPct: number | null; processors: number | null;
  threadsCpuPct: number; threadCount: number; allocSupported: boolean; grouped: boolean; method: string; rows: TopRow[];
}

export interface Pcts { p50: number | null; p75: number | null; p95: number | null; p98: number | null; p99: number | null; min: number | null; max: number | null; count: number | null }
export interface TableHist {
  keyspace: string; table: string; node: string | null; partitionSize: Pcts; cellCount: Pcts; tombstonesPerRead: Pcts;
  sstablesPerRead: Pcts; liveCellsPerRead: Pcts; flags: string[];
}
export interface HistView { merged: TableHist[]; perNode: TableHist[]; errors: { node: string; error: string }[]; thresholds: { largePartitionBytes: number; tombstonesP99: number } }

export interface KeyCount { key: string; count: number; error: number; nodes: string[] }
export interface SamplerResult { sampler: string; cardinality: number | null; top: KeyCount[]; error: string | null }
export interface TableResult { keyspace: string; table: string; samplers: SamplerResult[] }
export interface NodeResult { node: string; api: string | null; error: string | null; tables: TableResult[] | null }
export interface HotResult { durationMs: number; capacity: number; top: number; nodes: NodeResult[]; merged: TableResult[] }

export type WarningKind = "TOMBSTONE_WARN" | "TOMBSTONE_ABORT" | "LARGE_PARTITION_WRITE" | "LARGE_PARTITION_COMPACT";
export interface LogWarning {
  node: string; time: string; level: string; kind: WarningKind; keyspace: string | null; table: string | null;
  tombstones: number | null; liveRows: number | null; partitionKey: string | null; sizeBytes: number | null; detail: string | null;
}
export interface WarningsView { warnings: LogWarning[]; nodes: { node: string; path: string; error: string | null; lines: number }[] }
export interface ScanResult { node: string; command: string; available: boolean; header: string[]; rows: { cells: string[] }[]; output: string }

export interface DiagSettings { logPath: string; largePartitionMb: number; tombstonesP99: number; tombstoneScanScript: string }

export interface HotRequest { tables: string[]; durationSec: number; capacity?: number; top?: number; nodes?: string[] }

export function diagApi(id: string) {
  const base = `/api/clusters/${enc(id)}/diag`;
  return {
    takeDump: (node: string) => request<ThreadDump>("POST", `${base}/threads/dumps`, { node }),
    dumps: () => request<DumpSummary[]>("GET", `${base}/threads/dumps`),
    dump: (dumpId: string) => request<ThreadDump>("GET", `${base}/threads/dumps/${enc(dumpId)}`),
    deleteDump: (dumpId: string) => request<void>("DELETE", `${base}/threads/dumps/${enc(dumpId)}`),
    dumpText: (dumpId: string) => fetchText(`${base}/threads/dumps/${enc(dumpId)}/text`),
    series: (node: string, count: number, intervalSec: number) => request<Job>("POST", `${base}/threads/series`, { node, count, intervalSec }),
    compare: (a: string, b: string) => request<Comparison>("GET", `${base}/threads/compare?a=${enc(a)}&b=${enc(b)}`),
    top: (node: string, limit: number, group: boolean) =>
      request<TopView>("GET", `${base}/threads/top?node=${enc(node)}&limit=${limit}&group=${group}`),
    histograms: (node: string | null, keyspace: string | null) =>
      request<HistView>("GET", `${base}/partitions/histograms?${node ? `node=${enc(node)}&` : ""}${keyspace ? `keyspace=${enc(keyspace)}` : ""}`),
    hot: (req: HotRequest) => request<Job>("POST", `${base}/partitions/hot`, req),
    warnings: (node: string | null, limit = 500) =>
      request<WarningsView>("GET", `${base}/partitions/warnings?limit=${limit}${node ? `&node=${enc(node)}` : ""}`),
    tombstoneScan: (node: string, keyspace: string | null, table: string | null) =>
      request<Job>("POST", `${base}/partitions/tombstone-scan`, { node, keyspace, table }),
    settings: () => request<DiagSettings>("GET", `${base}/settings`),
    saveSettings: (s: Partial<DiagSettings>) => request<DiagSettings>("PUT", `${base}/settings`, s),
  };
}

/** GET returning text (the jstack export); same auth as request(). */
async function fetchText(path: string): Promise<string> {
  const token = sessionStorage.getItem("studio.token");
  const res = await fetch(((import.meta.env.VITE_ENGINE_URL as string | undefined) ?? "") + path, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  const text = await res.text();
  if (!res.ok) {
    let msg = text || res.statusText;
    try { msg = (JSON.parse(text) as { message?: string }).message ?? msg; } catch { /* not JSON */ }
    throw new Error(msg);
  }
  return text;
}

export type DiagClient = ReturnType<typeof diagApi>;

/** Stack frame as jstack prints it. */
export function frameText(f: Frame): string {
  const where = f.nativeMethod ? "Native Method" : f.file == null ? "Unknown Source" : f.line != null && f.line >= 0 ? `${f.file}:${f.line}` : f.file;
  return `${f.className}.${f.method}(${where})`;
}

/** Threads matching a free-text filter (name, state or any frame). */
export function filterThreads(threads: ThreadEntry[], q: string, state: string): ThreadEntry[] {
  const needle = q.trim().toLowerCase();
  return threads.filter((t) =>
    (!state || t.state === state) &&
    (!needle || t.name.toLowerCase().includes(needle) || t.stack.some((f) => frameText(f).toLowerCase().includes(needle))));
}

/** "ks.table" list from a schema tree's keyspaces, user keyspaces only. */
export function isSystemKeyspace(ks: string): boolean {
  return ks === "system" || ks.startsWith("system_") || ks.startsWith("dse_");
}
