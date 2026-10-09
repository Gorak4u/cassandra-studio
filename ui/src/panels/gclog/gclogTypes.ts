// Mirrors engine/.../gclog/GcReport.java, GcEvent.java, GcLogFiles.Discovery and
// GcLogService.AnalysisInfo (docs/api/gclog.md). Memory values are KiB, times ms, x seconds.

export interface RemoteFile { path: string; sizeBytes: number; modifiedMs: number; current: boolean }

export interface Discovery {
  node: string;
  javaVersion?: string;
  gcOptions: string[];
  configuredPath?: string;
  searched: string[];
  files: RemoteFile[];
  compressed: boolean;
  note?: string;
}

export interface AnalysisInfo {
  id: string;
  name: string;
  sourceKind: "ssh" | "upload";
  node?: string;
  createdAtMs: number;
  collector?: string;
  format: string;
  events: number;
  bytes: number;
  warnings: string[];
}

export type Category = "young" | "mixed" | "full" | "phase" | "concurrent";

export interface GcEvent {
  x: number;
  gcId?: number;
  uptime?: number;
  ts?: number;
  kind: "pause" | "concurrent";
  type: string;
  category: Category;
  cause?: string;
  durationMs: number;
  heapBeforeK?: number; heapAfterK?: number; heapTotalK?: number;
  youngBeforeK?: number; youngAfterK?: number; youngTotalK?: number;
  oldBeforeK?: number; oldAfterK?: number; oldTotalK?: number;
  humongousBeforeK?: number; humongousAfterK?: number;
  metaBeforeK?: number; metaAfterK?: number; metaTotalK?: number;
  flags?: string[];
}

export interface Stats { count: number; totalMs: number; avgMs: number; minMs: number; maxMs: number; p50Ms: number; p95Ms: number; p99Ms: number }
export interface TypeStats { type: string; category?: string; kind: string; count: number; totalMs: number; avgMs: number; maxMs: number }
export interface Bucket { label: string; fromMs: number; toMs?: number; count: number }
export interface Count { name: string; count: number }
export interface Rate { avgMBs?: number; peakMBs?: number; totalMB?: number }

export interface Summary {
  durationSec: number;
  pauses: Stats;
  histogram: Bucket[];
  byType: TypeStats[];
  gcTimePct: number;
  throughputPct: number;
  fullGcCount: number;
  fullGcCauses: Count[];
  concurrent: TypeStats[];
  humongousAllocations: number;
  humongousPeakK?: number;
  toSpaceExhausted: number;
  evacuationFailures: number;
  concurrentModeFailures: number;
  promotionFailures: number;
  degenerated: number;
  stalls: { count: number; totalMs: number; maxMs: number };
  safepoints: { count: number; totalMs: number; maxMs: number; ttspTotalMs: number; ttspMaxMs: number; stoppedPct: number; reasons: TypeStats[] };
  heap: { maxK?: number; peakUsedK?: number; peakAfterK?: number; avgAfterK?: number; peakOldAfterK?: number; peakMetaK?: number };
  allocation: Rate;
  promotion: Rate;
  causes: Count[];
}

export type Severity = "critical" | "warning" | "info";

export interface Finding {
  id: string;
  severity: Severity;
  title: string;
  detail: string;
  evidence: string[];
  hint: string;
  options: string[];
  file?: string;
  atX?: number;
}

export interface LogInfo {
  format: string;
  collector?: string;
  jvmVersion?: string;
  javaMajor?: number;
  jvmFlags?: string;
  heapMaxK?: number;
  regionSizeK?: number;
  timeAxis: "wall" | "uptime";
  startTs?: number;
  startX: number;
  endX: number;
  lines: number;
  bytes: number;
  eventCount: number;
  warnings: string[];
}

export interface GcReport {
  id: string;
  name: string;
  source: { kind: "ssh" | "upload"; node?: string; files: { path: string; sizeBytes: number; truncated: boolean }[] };
  createdAtMs: number;
  log: LogInfo;
  range: { fromX: number; toX: number; whole: boolean };
  summary: Summary;
  series: { bucketSec: number; gcTimePct: [number, number][]; allocationMBs: [number, number][]; promotionMBs: [number, number][]; safepointMs: [number, number][] };
  findings: Finding[];
  events: GcEvent[];
  eventsTruncated: boolean;
}
