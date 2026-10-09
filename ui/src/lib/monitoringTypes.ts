// Phase 2 contract: mirrors engine/.../metrics/MonitoringModel.java field for field.
// Units are in the names (Bytes, Ms, Micros, PerSec, Pct 0-100). null = not available.

export type Level = "GREEN" | "YELLOW" | "RED";

export interface Health { level: Level; reasons: string[] }

export interface Alert {
  id: string; level: Level; rule: string; node?: string | null; message: string;
  value?: number | null; threshold?: number | null; sinceEpochMs: number;
}

export interface GcCollector { name: string; count: number | null; timeMs: number | null }

export interface ThreadPool {
  name: string; active: number | null; pending: number | null; blocked: number | null;
  completed: number | null; allTimeBlocked: number | null;
}

export interface Latency {
  p50Micros: number | null; p95Micros: number | null; p99Micros: number | null; maxMicros: number | null;
  ratePerSec: number | null; count: number | null;
}

export interface ClientRequests {
  read: Latency | null; write: Latency | null; rangeSlice: Latency | null; casRead: Latency | null; casWrite: Latency | null;
  readTimeouts: number | null; writeTimeouts: number | null; readUnavailables: number | null;
  writeUnavailables: number | null; readFailures: number | null; writeFailures: number | null;
}

export interface DataDir { path: string; totalBytes: number | null; freeBytes: number | null }

export interface NodeSnapshot {
  hostId: string | null; address: string; datacenter: string | null; rack: string | null;
  state: string; route: string | null; error: string | null;
  cassandraVersion: string | null; javaVersion: string | null; javaVendor: string | null; uptimeSec: number | null;
  loadBytes: number | null; tokens: number | null;
  heapUsedBytes: number | null; heapMaxBytes: number | null; offHeapBytes: number | null;
  gc: GcCollector[] | null; gcTimePct: number | null;
  cpuProcessPct: number | null; cpuSystemPct: number | null; openFds: number | null; maxFds: number | null;
  pendingCompactions: number | null; activeCompactions: number | null; completedCompactions: number | null;
  hintsInProgress: number | null; totalHints: number | null;
  threadPools: ThreadPool[] | null; dropped: Record<string, number> | null;
  clientRequests: ClientRequests | null; liveSSTables: number | null; dataDirs: DataDir[] | null;
}

export interface ClusterSnapshot {
  atEpochMs: number; pollIntervalSec: number; health: Health; nodes: NodeSnapshot[]; alerts: Alert[];
  schemaAgreement: boolean;
}

/** points: [epochMs, value] pairs per node address. */
export interface Series { metric: string; unit: string; pointsByNode: Record<string, [number, number][]> }

export interface RingNode {
  hostId: string | null; address: string; rack: string | null; state: string; loadBytes: number | null;
  tokens: string[]; ownershipPct: number | null; effectiveOwnershipPct: number | null;
}
export interface RingDc { name: string; nodes: RingNode[] }
export interface Ring { partitioner: string | null; keyspace: string | null; datacenters: RingDc[] }

export interface TableMetrics {
  keyspace: string; table: string; readCount: number | null; writeCount: number | null;
  readLatencyP99Micros: number | null; writeLatencyP99Micros: number | null;
  liveDiskSpaceBytes: number | null; totalDiskSpaceBytes: number | null; sstableCount: number | null;
  meanPartitionSizeBytes: number | null; maxPartitionSizeBytes: number | null;
  tombstonesPerReadP99: number | null; sstablesPerReadP99: number | null; bloomFilterFalseRatio: number | null;
  pendingCompactions: number | null; keyCacheHitRate: number | null;
}

export interface NodeAccess { address: string; ok: boolean; route: string | null; error: string | null; lastPollEpochMs: number }
export interface AccessStatus { method: string; polling: boolean; pollIntervalSec: number; nodes: NodeAccess[] }

/** Metric ids accepted by the series endpoint (see docs/api/monitoring.md). */
export const SERIES_METRICS = [
  "heap.used", "heap.max", "gc.time_pct", "gc.pause_ms", "load.bytes", "cpu.process_pct",
  "client.read.rate", "client.write.rate", "client.read.p99_us", "client.write.p99_us",
  "client.read.p50_us", "client.write.p50_us", "client.timeouts", "client.unavailables",
  "compaction.pending", "hints.in_progress", "dropped.total", "threadpool.pending_total", "threadpool.blocked_total",
] as const;
export type SeriesMetric = (typeof SERIES_METRICS)[number];
