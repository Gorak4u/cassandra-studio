import type { GcEvent, GcReport } from "../gclogTypes";

const T0 = Date.UTC(2026, 9, 9, 8, 20);

function ev(x: number, type: string, ms: number, extra: Partial<GcEvent> = {}): GcEvent {
  const category = type === "Full" ? "full" : type.startsWith("Concurrent") ? "concurrent" : type === "Young" ? "young" : "phase";
  return { x, ts: T0 + x * 1000, kind: category === "concurrent" ? "concurrent" : "pause", type, category, durationMs: ms, ...extra };
}

/** A small CMS report with a full GC, as the engine returns it. */
export function sampleReport(over: Partial<GcReport> = {}): GcReport {
  const events: GcEvent[] = [
    ev(1, "Young", 27.2, { cause: "Allocation Failure", heapBeforeK: 81920, heapAfterK: 12765, heapTotalK: 514048, youngAfterK: 10239, oldAfterK: 2526, oldTotalK: 421888, metaAfterK: 20556 }),
    ev(2.4, "Young", 66.9, { cause: "Allocation Failure", heapBeforeK: 94685, heapAfterK: 22898, heapTotalK: 514048, youngAfterK: 10240, oldAfterK: 12658, oldTotalK: 421888, metaAfterK: 26819 }),
    ev(2.5, "Initial Mark", 10.1, { cause: "CMS Initial Mark", heapBeforeK: 29110, heapAfterK: 29110, heapTotalK: 514048 }),
    ev(2.6, "Concurrent Mark", 22),
    ev(9, "Full", 6900, { cause: "Allocation Failure", heapBeforeK: 500000, heapAfterK: 480000, heapTotalK: 514048, oldAfterK: 400000, oldTotalK: 421888, metaAfterK: 45000, flags: ["concurrent mode failure"] }),
  ];
  return {
    id: "a1",
    name: "10.0.0.1 gc.log",
    source: { kind: "ssh", node: "10.0.0.1", files: [{ path: "/var/log/cassandra/gc.log", sizeBytes: 54401, truncated: false }] },
    createdAtMs: T0,
    log: { format: "java8", collector: "CMS", jvmVersion: "1.8.0_472-b08", javaMajor: 8, heapMaxK: 524288, timeAxis: "wall", startTs: T0,
      startX: 0, endX: 20, lines: 900, bytes: 54401, eventCount: 5, warnings: [] },
    range: { fromX: 0, toX: 20, whole: true },
    summary: {
      durationSec: 20,
      pauses: { count: 4, totalMs: 7004.2, avgMs: 1751, minMs: 10.1, maxMs: 6900, p50Ms: 27.2, p95Ms: 6900, p99Ms: 6900 },
      histogram: [{ label: "10 ms–20 ms", fromMs: 10, toMs: 20, count: 1 }, { label: "20 ms–50 ms", fromMs: 20, toMs: 50, count: 1 },
        { label: "50 ms–100 ms", fromMs: 50, toMs: 100, count: 1 }, { label: "≥ 5.00 s", fromMs: 5000, count: 1 }],
      byType: [{ type: "Full", category: "full", kind: "pause", count: 1, totalMs: 6900, avgMs: 6900, maxMs: 6900 },
        { type: "Young", category: "young", kind: "pause", count: 2, totalMs: 94.1, avgMs: 47, maxMs: 66.9 }],
      gcTimePct: 35, throughputPct: 65, fullGcCount: 1, fullGcCauses: [{ name: "Allocation Failure", count: 1 }],
      concurrent: [{ type: "Concurrent Mark", category: "concurrent", kind: "concurrent", count: 1, totalMs: 22, avgMs: 22, maxMs: 22 }],
      humongousAllocations: 0, toSpaceExhausted: 0, evacuationFailures: 0, concurrentModeFailures: 1, promotionFailures: 0, degenerated: 0,
      stalls: { count: 0, totalMs: 0, maxMs: 0 },
      safepoints: { count: 3, totalMs: 7010, maxMs: 6910, ttspTotalMs: 1, ttspMaxMs: 0.5, stoppedPct: 35, reasons: [] },
      heap: { maxK: 524288, peakUsedK: 500000, peakAfterK: 480000, avgAfterK: 136000, peakOldAfterK: 400000, peakMetaK: 45000 },
      allocation: { avgMBs: 30, peakMBs: 80, totalMB: 600 }, promotion: { avgMBs: 2, peakMBs: 10, totalMB: 40 },
      causes: [{ name: "Allocation Failure", count: 3 }],
    },
    series: { bucketSec: 1, gcTimePct: [[0, 2], [1, 3], [9, 100]], allocationMBs: [[0, 10], [1, 80]], promotionMBs: [[0, 1], [1, 10]], safepointMs: [] },
    findings: [
      { id: "concurrent-mode-failure", severity: "critical", title: "CMS concurrent mode failure", detail: "The old generation filled up.",
        evidence: ["1 concurrent mode failure(s)"], hint: "Start CMS earlier.", options: ["-XX:CMSInitiatingOccupancyFraction"], file: "conf/jvm.options", atX: 9 },
      { id: "small-heap", severity: "info", title: "Small heap: 512 MB", detail: "Below 2 GB.", evidence: ["Maximum heap 512 MB"], hint: "Raise MAX_HEAP_SIZE.", options: ["-Xmx"] },
    ],
    events,
    eventsTruncated: false,
    ...over,
  };
}
