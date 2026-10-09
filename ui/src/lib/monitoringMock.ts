// Demo data for the monitoring contract (monitoringTypes.ts), used while the engine has no
// monitoring routes yet or when VITE_MONITORING_MOCK=1. Deterministic: values are a function
// of node, metric and time, so charts and tables agree and tests are stable.
import { ApiError } from "./api";
import type { MonitoringClient, Thresholds } from "./monitoringApi";
import type {
  AccessStatus, Alert, ClusterSnapshot, GcCollector, Latency, NodeSnapshot, Ring, RingDc, Series, SeriesMetric,
  TableMetrics, ThreadPool,
} from "./monitoringTypes";

const GiB = 1024 ** 3;
const MiB = 1024 ** 2;

interface MockNode { address: string; dc: string; rack: string; hostId: string; loadGiB: number; heapBias: number; gcBias: number; error?: string }

export const MOCK_NODES: MockNode[] = [
  { address: "10.0.1.11", dc: "dc-east", rack: "rack1", hostId: "5c1e0a52-41d7-4b2f-9a0e-1a2b3c4d5e01", loadGiB: 118, heapBias: 0, gcBias: 0 },
  { address: "10.0.1.12", dc: "dc-east", rack: "rack2", hostId: "5c1e0a52-41d7-4b2f-9a0e-1a2b3c4d5e02", loadGiB: 124, heapBias: 0.42, gcBias: 5 },
  { address: "10.0.1.13", dc: "dc-east", rack: "rack3", hostId: "5c1e0a52-41d7-4b2f-9a0e-1a2b3c4d5e03", loadGiB: 262, heapBias: 0.1, gcBias: 1 },
  { address: "10.0.2.11", dc: "dc-west", rack: "rack1", hostId: "7d2f1b63-52e8-4c3a-8b1f-2b3c4d5e6f01", loadGiB: 121, heapBias: 0.05, gcBias: 0 },
  { address: "10.0.2.12", dc: "dc-west", rack: "rack2", hostId: "7d2f1b63-52e8-4c3a-8b1f-2b3c4d5e6f02", loadGiB: 116, heapBias: 0, gcBias: 0 },
  {
    address: "10.0.2.13", dc: "dc-west", rack: "rack3", hostId: "7d2f1b63-52e8-4c3a-8b1f-2b3c4d5e6f03", loadGiB: 119, heapBias: 0, gcBias: 0,
    error: "JMX connection refused through ssh tunnel to 10.0.2.13:7199 (connect timed out after 5000 ms)",
  },
];

/** The unreachable node stopped answering this long ago (its history ends there). */
const ERROR_SINCE_MS = 20 * 60_000;

/** Stable pseudo-random number in [0, 1) for a key. */
export function hash01(key: string): number {
  let h = 2166136261;
  for (let i = 0; i < key.length; i++) {
    h ^= key.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  h ^= h >>> 13;
  h = Math.imul(h, 0x5bd1e995);
  h ^= h >>> 15;
  return (h >>> 0) / 4294967296;
}

function noise(key: string, t: number, stepMs = 10_000): number {
  const i = Math.floor(t / stepMs);
  const f = t / stepMs - i;
  const a = hash01(key + ":" + i);
  const b = hash01(key + ":" + (i + 1));
  return a + (b - a) * f; // smooth-ish noise in [0, 1)
}

function nodeIndex(address: string): number {
  return MOCK_NODES.findIndex((n) => n.address === address);
}

/** Daily wave so a 24 h range shows some shape. */
function wave(t: number, phase: number): number {
  return 0.5 + 0.5 * Math.sin((t / 86_400_000) * 2 * Math.PI + phase);
}

/** The value of a series metric for one node at time t (unit as in the metric name). */
export function mockValue(n: MockNode, metric: SeriesMetric, t: number): number {
  const k = n.address + metric;
  const r = noise(k, t);
  const d = wave(t, nodeIndex(n.address));
  switch (metric) {
    case "heap.max": return 8 * GiB;
    case "heap.used": {
      const saw = ((t / 1000) % 180) / 180; // fills up, then a GC
      return (2.2 + n.heapBias * 10 + (2.6 - n.heapBias * 4) * saw + 0.4 * r) * GiB;
    }
    case "gc.time_pct": return Math.max(0, 0.8 + n.gcBias + 2.4 * r + 1.5 * d);
    case "gc.pause_ms": return 18 + 60 * r + n.gcBias * 25 + (r > 0.96 ? 400 : 0);
    case "load.bytes": return n.loadGiB * GiB * (1 + 0.002 * (t / 3_600_000 % 24));
    case "cpu.process_pct": return 12 + 25 * d + 10 * r + n.gcBias * 2;
    case "client.read.rate": return 700 + 900 * d + 150 * r;
    case "client.write.rate": return 1400 + 1600 * d + 300 * r;
    case "client.read.p99_us": return 3200 + 6000 * d * r + (n.gcBias > 2 ? 4000 : 0);
    case "client.write.p99_us": return 1100 + 1800 * r;
    case "client.read.p50_us": return 420 + 260 * r;
    case "client.write.p50_us": return 180 + 90 * r;
    case "client.timeouts": return r > 0.93 ? Math.round(1 + 4 * r) : 0;
    case "client.unavailables": return r > 0.985 ? 1 : 0;
    case "compaction.pending": return n.loadGiB > 200 ? Math.round(120 + 40 * r) : Math.round(6 * r);
    case "hints.in_progress": return r > 0.9 ? 1 : 0;
    case "dropped.total": return r > 0.95 ? Math.round(3 + 20 * r) : 0;
    case "threadpool.pending_total": return Math.round(8 * r * d);
    case "threadpool.blocked_total": return r > 0.97 ? 1 : 0;
  }
}

function latency(n: MockNode, op: "read" | "write", t: number): Latency {
  const p50 = mockValue(n, op === "read" ? "client.read.p50_us" : "client.write.p50_us", t);
  const p99 = mockValue(n, op === "read" ? "client.read.p99_us" : "client.write.p99_us", t);
  return {
    p50Micros: Math.round(p50), p95Micros: Math.round((p50 + p99) / 2), p99Micros: Math.round(p99), maxMicros: Math.round(p99 * 3.4),
    ratePerSec: Math.round(mockValue(n, op === "read" ? "client.read.rate" : "client.write.rate", t)),
    count: Math.round(t / 1000) * (op === "read" ? 9 : 17),
  };
}

const POOLS = ["ReadStage", "MutationStage", "CounterMutationStage", "ViewMutationStage", "CompactionExecutor",
  "MemtableFlushWriter", "Native-Transport-Requests", "GossipStage", "HintsDispatcher", "ValidationExecutor"];

function threadPools(n: MockNode, t: number): ThreadPool[] {
  return POOLS.map((name, i) => {
    const r = noise(n.address + name, t);
    const pending = name === "CompactionExecutor" && n.loadGiB > 200 ? 14 : i < 2 ? Math.round(6 * r * r) : 0;
    const blocked = name === "Native-Transport-Requests" && n.gcBias > 2 ? 2 : 0;
    return {
      name, active: i < 3 ? Math.round(4 * r) : 0, pending, blocked,
      completed: Math.round(t / 1000) * (20 - i), allTimeBlocked: name === "Native-Transport-Requests" ? 37 + blocked : 0,
    };
  });
}

function nodeSnapshot(n: MockNode, t: number): NodeSnapshot {
  const base = {
    hostId: n.hostId, address: n.address, datacenter: n.dc, rack: n.rack, state: "UN",
    route: `ssh tunnel via bastion.example.internal -> ${n.address}:7199`, tokens: 16,
  };
  if (n.error) {
    return {
      ...base, error: n.error, cassandraVersion: "4.1.7", javaVersion: null, javaVendor: null, uptimeSec: null, loadBytes: null,
      heapUsedBytes: null, heapMaxBytes: null, offHeapBytes: null, gc: null, gcTimePct: null, cpuProcessPct: null,
      cpuSystemPct: null, openFds: null, maxFds: null, pendingCompactions: null, activeCompactions: null,
      completedCompactions: null, hintsInProgress: null, totalHints: null, threadPools: null, dropped: null,
      clientRequests: null, liveSSTables: null, dataDirs: null,
    };
  }
  const v = (m: SeriesMetric) => mockValue(n, m, t);
  const gcPause = v("gc.pause_ms");
  const gc: GcCollector[] = [
    { name: "G1 Young Generation", count: 18_000 + Math.round(t / 60_000) % 5000, timeMs: 410_000 + Math.round(gcPause) },
    { name: "G1 Old Generation", count: 2, timeMs: 1_830 },
  ];
  const dropped: Record<string, number> = { MUTATION: 0, READ: 0, HINT: 0, READ_REPAIR: 0, RANGE_SLICE: 0, COUNTER_MUTATION: 0 };
  const d = Math.round(v("dropped.total"));
  if (d > 0) { dropped.MUTATION = d; dropped.READ = Math.round(d / 3); }
  const dataTotal = 1024 * GiB;
  const usedPct = n.loadGiB > 200 ? 0.83 : 0.31 + hash01(n.address) * 0.1;
  return {
    ...base, error: null, cassandraVersion: "4.1.7", javaVersion: "11.0.24", javaVendor: "Eclipse Adoptium",
    uptimeSec: 86_400 * (12 + nodeIndex(n.address)) + Math.round((t / 1000) % 86_400),
    loadBytes: Math.round(v("load.bytes")),
    heapUsedBytes: Math.round(v("heap.used")), heapMaxBytes: Math.round(v("heap.max")), offHeapBytes: Math.round(0.6 * GiB + 0.1 * GiB * hash01(n.address)),
    gc, gcTimePct: round2(v("gc.time_pct")), cpuProcessPct: round2(v("cpu.process_pct")), cpuSystemPct: round2(v("cpu.process_pct") + 9),
    openFds: 2400 + Math.round(400 * hash01(n.address)), maxFds: 100_000,
    pendingCompactions: Math.round(v("compaction.pending")), activeCompactions: n.loadGiB > 200 ? 2 : 0, completedCompactions: 48_211,
    hintsInProgress: Math.round(v("hints.in_progress")), totalHints: 212,
    threadPools: threadPools(n, t), dropped,
    clientRequests: {
      read: latency(n, "read", t), write: latency(n, "write", t),
      rangeSlice: { p50Micros: 2100, p95Micros: 8400, p99Micros: 15_800, maxMicros: 61_000, ratePerSec: 3, count: 91_002 },
      casRead: null, casWrite: { p50Micros: 4100, p95Micros: 9800, p99Micros: 22_000, maxMicros: 48_000, ratePerSec: 0.4, count: 1_204 },
      readTimeouts: 14, writeTimeouts: 3, readUnavailables: 0, writeUnavailables: 1, readFailures: 0, writeFailures: 0,
    },
    liveSSTables: n.loadGiB > 200 ? 1_480 : 410 + Math.round(60 * hash01(n.address)),
    dataDirs: [
      { path: "/var/lib/cassandra/data", totalBytes: dataTotal, freeBytes: Math.round(dataTotal * (1 - usedPct)) },
      { path: "/var/lib/cassandra/commitlog", totalBytes: 128 * GiB, freeBytes: 101 * GiB },
    ],
  };
}

function round2(x: number): number {
  return Math.round(x * 100) / 100;
}

function alerts(nodes: NodeSnapshot[], t: number): Alert[] {
  const out: Alert[] = [];
  for (const n of nodes) {
    if (n.error) {
      out.push({ id: "node.unreachable:" + n.address, level: "YELLOW", rule: "node.unreachable", node: n.address,
        message: `JMX read failed on ${n.address} (node may be fine)`, value: null, threshold: null, sinceEpochMs: t - ERROR_SINCE_MS });
    }
    if (n.heapUsedBytes != null && n.heapMaxBytes) {
      const pct = (100 * n.heapUsedBytes) / n.heapMaxBytes;
      if (pct > 85) out.push({ id: "heap.high:" + n.address, level: pct > 95 ? "RED" : "YELLOW", rule: "heap.high", node: n.address,
        message: `Heap ${pct.toFixed(0)} % of max on ${n.address}`, value: round2(pct), threshold: pct > 95 ? 95 : 85, sinceEpochMs: t - 95_000 });
    }
    if ((n.pendingCompactions ?? 0) > 100) {
      out.push({ id: "compaction.backlog:" + n.address, level: "YELLOW", rule: "compaction.backlog", node: n.address,
        message: `${n.pendingCompactions} pending compactions on ${n.address}`, value: n.pendingCompactions, threshold: 100, sinceEpochMs: t - 3_600_000 });
    }
  }
  const east = nodes.filter((n) => n.datacenter === "dc-east" && n.loadBytes != null);
  const avg = east.reduce((s, n) => s + (n.loadBytes ?? 0), 0) / east.length;
  for (const n of east) {
    if ((n.loadBytes ?? 0) > 1.5 * avg) {
      out.push({ id: "load.imbalance:" + n.address, level: "YELLOW", rule: "load.imbalance", node: n.address,
        message: `Load on ${n.address} is ${((n.loadBytes ?? 0) / avg).toFixed(2)}× the dc-east average`,
        value: round2((n.loadBytes ?? 0) / avg), threshold: 1.5, sinceEpochMs: t - 6 * 3_600_000 });
    }
  }
  return out;
}

// ---- ring --------------------------------------------------------------------

const TWO63 = 2n ** 63n;

/** 16 Murmur3 tokens per node, stable across calls. */
export function mockTokens(address: string): string[] {
  const out: string[] = [];
  for (let i = 0; i < 16; i++) {
    const hi = BigInt(Math.floor(hash01(address + "#hi" + i) * 2 ** 32));
    const lo = BigInt(Math.floor(hash01(address + "#lo" + i) * 2 ** 32));
    out.push(String(((hi << 32n) | lo) - TWO63));
  }
  return out;
}

const KEYSPACE_RF: Record<string, Record<string, number>> = {
  shop: { "dc-east": 3, "dc-west": 3 },
  analytics: { "dc-east": 2 },
  audit: { "dc-east": 1, "dc-west": 1 },
};

function ownership(tokensByNode: Map<string, string[]>): Map<string, number> {
  const all = [...tokensByNode].flatMap(([a, ts]) => ts.map((t) => ({ a, t: BigInt(t) }))).sort((x, y) => (x.t < y.t ? -1 : x.t > y.t ? 1 : 0));
  const share = new Map<string, number>();
  const span = 2 ** 64;
  all.forEach((e, i) => {
    const prev = i === 0 ? all[all.length - 1].t - 2n * TWO63 : all[i - 1].t;
    share.set(e.a, (share.get(e.a) ?? 0) + Number(e.t - prev) / span);
  });
  return share;
}

/** Effective ownership in one DC: each range is replicated to the next rf distinct nodes clockwise. */
function effective(tokensByNode: Map<string, string[]>, rf: number): Map<string, number> {
  const all = [...tokensByNode].flatMap(([a, ts]) => ts.map((t) => ({ a, t: BigInt(t) }))).sort((x, y) => (x.t < y.t ? -1 : x.t > y.t ? 1 : 0));
  const out = new Map<string, number>([...tokensByNode.keys()].map((a) => [a, 0]));
  const want = Math.min(rf, tokensByNode.size);
  all.forEach((e, i) => {
    const prev = i === 0 ? all[all.length - 1].t - 2n * TWO63 : all[i - 1].t;
    const size = Number(e.t - prev) / 2 ** 64;
    const replicas = new Set<string>();
    for (let j = 0; replicas.size < want; j++) replicas.add(all[(i + j) % all.length].a);
    for (const a of replicas) out.set(a, out.get(a)! + size);
  });
  return out;
}

function ring(keyspace: string | null | undefined): Ring {
  const ks = keyspace && KEYSPACE_RF[keyspace] ? keyspace : "shop";
  const tokens = new Map(MOCK_NODES.map((n) => [n.address, mockTokens(n.address)]));
  const global = ownership(tokens);
  const dcs = [...new Set(MOCK_NODES.map((n) => n.dc))];
  const datacenters: RingDc[] = dcs.map((dc) => {
    const members = MOCK_NODES.filter((n) => n.dc === dc);
    const eff = effective(new Map(members.map((n) => [n.address, tokens.get(n.address)!])), KEYSPACE_RF[ks][dc] ?? 0);
    return {
      name: dc,
      nodes: members.map((n) => ({
        hostId: n.hostId, address: n.address, rack: n.rack, state: "UN", loadBytes: n.error ? null : Math.round(n.loadGiB * GiB),
        tokens: tokens.get(n.address)!, ownershipPct: round2(100 * (global.get(n.address) ?? 0)),
        effectiveOwnershipPct: round2(100 * (eff.get(n.address) ?? 0)),
      })),
    };
  });
  return { partitioner: "org.apache.cassandra.dht.Murmur3Partitioner", keyspace: ks, datacenters };
}

// ---- tables ------------------------------------------------------------------

function tables(keyspace: string | null | undefined): TableMetrics[] {
  const rows: [string, string, Partial<TableMetrics>][] = [
    ["shop", "orders", { readCount: 48_200_113, writeCount: 92_001_442, liveDiskSpaceBytes: 210 * GiB, maxPartitionSizeBytes: 12 * MiB }],
    ["shop", "carts", { readCount: 12_440_090, writeCount: 30_118_002, tombstonesPerReadP99: 1_840, sstablesPerReadP99: 9, liveDiskSpaceBytes: 18 * GiB }],
    ["shop", "products", { readCount: 220_118_500, writeCount: 410_220, keyCacheHitRate: 0.97, liveDiskSpaceBytes: 4 * GiB }],
    ["shop", "users", { readCount: 18_220_100, writeCount: 1_220_004, liveDiskSpaceBytes: 9 * GiB }],
    ["analytics", "events", { readCount: 1_002_300, writeCount: 510_220_330, maxPartitionSizeBytes: 182 * MiB, pendingCompactions: 96, liveDiskSpaceBytes: 640 * GiB }],
    ["analytics", "page_views", { readCount: 220_330, writeCount: 98_100_200, bloomFilterFalseRatio: 0.031, liveDiskSpaceBytes: 120 * GiB }],
    ["audit", "log", { readCount: 1_204, writeCount: 6_120_998, keyCacheHitRate: null, readLatencyP99Micros: null, liveDiskSpaceBytes: 31 * GiB }],
  ];
  return rows
    .filter(([ks]) => !keyspace || ks === keyspace)
    .map(([ks, table, o]) => {
      const r = hash01(ks + table);
      const live = o.liveDiskSpaceBytes ?? 10 * GiB;
      return {
        keyspace: ks, table, readCount: null, writeCount: null,
        readLatencyP99Micros: Math.round(1800 + 9000 * r), writeLatencyP99Micros: Math.round(600 + 1400 * r),
        liveDiskSpaceBytes: live, totalDiskSpaceBytes: Math.round(live * 1.12), sstableCount: Math.round(12 + 200 * r),
        meanPartitionSizeBytes: Math.round(2048 + 90_000 * r), maxPartitionSizeBytes: Math.round(MiB * (1 + 40 * r)),
        tombstonesPerReadP99: Math.round(10 * r), sstablesPerReadP99: Math.round(1 + 3 * r), bloomFilterFalseRatio: round2(0.002 * r * 100) / 100,
        pendingCompactions: Math.round(4 * r), keyCacheHitRate: round2(0.82 + 0.15 * r),
        ...o,
      };
    });
}

export const DEFAULT_THRESHOLDS: Thresholds = {
  "heap.high.yellowPct": 85, "heap.high.redPct": 95,
  "gc.pressure.yellowPct": 10, "gc.pressure.redPct": 25,
  "compaction.backlog.pending": 100,
  "disk.usage.yellowPct": 80, "disk.usage.redPct": 90,
  "load.imbalance.factor": 1.5,
  "hints.backlog.polls": 3,
};

// ---- client ------------------------------------------------------------------

export function mockSnapshot(t: number, pollIntervalSec = 10): ClusterSnapshot {
  const nodes = MOCK_NODES.map((n) => nodeSnapshot(n, t));
  const a = alerts(nodes, t);
  const level = a.some((x) => x.level === "RED") ? "RED" : a.length ? "YELLOW" : "GREEN";
  return { atEpochMs: t, pollIntervalSec, health: { level, reasons: a.map((x) => x.message) }, nodes, alerts: a, schemaAgreement: true };
}

export function mockSeries(metric: SeriesMetric, fromMs: number, toMs: number, intervalSec = 10, node?: string | null): Series {
  const stepMs = toMs - fromMs > 3_600_000 ? 60_000 : intervalSec * 1000;
  const pointsByNode: Record<string, [number, number][]> = {};
  for (const n of MOCK_NODES) {
    if (node && n.address !== node) continue;
    const end = n.error ? Math.min(toMs, Date.now() - ERROR_SINCE_MS) : toMs;
    const pts: [number, number][] = [];
    for (let t = Math.ceil(fromMs / stepMs) * stepMs; t <= end; t += stepMs) pts.push([t, round2(mockValue(n, metric, t))]);
    pointsByNode[n.address] = pts;
  }
  const unit = metric.endsWith("_pct") ? "pct" : metric.endsWith("_us") ? "micros" : metric.endsWith("_ms") ? "ms"
    : metric.startsWith("heap") || metric === "load.bytes" ? "bytes" : metric.endsWith(".rate") ? "perSec" : "count";
  return { metric, unit, pointsByNode };
}

export function createMockMonitoring(opts: { now?: () => number; latencyMs?: number } = {}): MonitoringClient {
  const now = opts.now ?? Date.now;
  let started = false;
  let interval = 10;
  let overrides: Thresholds = {};
  const later = <T,>(v: () => T): Promise<T> =>
    new Promise((resolve, reject) => setTimeout(() => { try { resolve(v()); } catch (e) { reject(e); } }, opts.latencyMs ?? 120));
  const status = (): AccessStatus => ({
    method: "SSH_TUNNEL", polling: started, pollIntervalSec: interval,
    nodes: MOCK_NODES.map((n) => ({
      address: n.address, ok: !n.error, route: `ssh tunnel via bastion.example.internal -> ${n.address}:7199`, error: n.error ?? null,
      lastPollEpochMs: n.error ? now() - ERROR_SINCE_MS : now(),
    })),
  });
  return {
    start: (intervalSec) => later(() => { started = true; interval = Math.max(2, intervalSec); return status(); }),
    stop: () => later(() => { started = false; }),
    status: () => later(status),
    snapshot: (intervalSec) => later(() => {
      if (!started) { started = true; interval = intervalSec ?? interval; }
      return mockSnapshot(now(), interval);
    }),
    series: (metric, q = {}) => later(() => {
      const toMs = q.toMs ?? now();
      return mockSeries(metric, q.fromMs ?? toMs - 15 * 60_000, toMs, interval, q.node);
    }),
    ring: (keyspace) => later(() => ring(keyspace)),
    tables: (keyspace) => later(() => tables(keyspace)),
    alerts: () => later(() => mockSnapshot(now(), interval).alerts),
    thresholds: () => later(() => ({ ...DEFAULT_THRESHOLDS, ...overrides })),
    saveThresholds: (t) => later(() => {
      const next = { ...overrides };
      for (const [k, v] of Object.entries(t)) {
        if (v === null) delete next[k]; // null clears the override
        else if (typeof v !== "number" || !Number.isFinite(v)) throw new ApiError(400, "bad_request", `Threshold ${k} must be a number`);
        else next[k] = v;
      }
      overrides = next;
      return { ...DEFAULT_THRESHOLDS, ...overrides };
    }),
  };
}
