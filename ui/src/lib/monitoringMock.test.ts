import { describe, expect, it } from "vitest";
import { createMockMonitoring, DEFAULT_THRESHOLDS, mockSeries, mockSnapshot, mockTokens } from "./monitoringMock";
import { SERIES_METRICS, type NodeSnapshot, type TableMetrics } from "./monitoringTypes";

// Every field of the contract; a missing key would mean the mock drifted from monitoringTypes.ts.
const NODE_KEYS: (keyof NodeSnapshot)[] = [
  "hostId", "address", "datacenter", "rack", "state", "route", "error", "cassandraVersion", "javaVersion", "javaVendor", "uptimeSec",
  "loadBytes", "tokens", "heapUsedBytes", "heapMaxBytes", "offHeapBytes", "gc", "gcTimePct", "cpuProcessPct", "cpuSystemPct", "openFds",
  "maxFds", "pendingCompactions", "activeCompactions", "completedCompactions", "hintsInProgress", "totalHints", "threadPools", "dropped",
  "clientRequests", "liveSSTables", "dataDirs",
];
const TABLE_KEYS: (keyof TableMetrics)[] = [
  "keyspace", "table", "readCount", "writeCount", "readLatencyP99Micros", "writeLatencyP99Micros", "liveDiskSpaceBytes", "totalDiskSpaceBytes",
  "sstableCount", "meanPartitionSizeBytes", "maxPartitionSizeBytes", "tombstonesPerReadP99", "sstablesPerReadP99", "bloomFilterFalseRatio",
  "pendingCompactions", "keyCacheHitRate",
];
const T = Date.UTC(2026, 9, 9, 12, 0, 0);

describe("monitoring mock", () => {
  it("has a 2-DC 4.1 cluster with one unreachable node, every contract field present", () => {
    const s = mockSnapshot(T);
    expect(s.atEpochMs).toBe(T);
    expect(new Set(s.nodes.map((n) => n.datacenter))).toEqual(new Set(["dc-east", "dc-west"]));
    for (const n of s.nodes) {
      expect(Object.keys(n).sort()).toEqual([...NODE_KEYS].sort());
      for (const k of NODE_KEYS) expect(n[k]).not.toBeUndefined();
      expect(n.cassandraVersion).toMatch(/^4\.1\./);
    }
    const bad = s.nodes.filter((n) => n.error);
    expect(bad).toHaveLength(1);
    expect(bad[0].heapUsedBytes).toBeNull();
    const ok = s.nodes.find((n) => !n.error)!;
    expect(ok.heapUsedBytes!).toBeLessThanOrEqual(ok.heapMaxBytes!);
    expect(ok.threadPools!.length).toBeGreaterThan(3);
  });

  it("raises alerts consistent with the health level", () => {
    const s = mockSnapshot(T);
    expect(s.alerts.map((a) => a.rule)).toEqual(expect.arrayContaining(["node.unreachable", "load.imbalance", "compaction.backlog"]));
    expect(s.health.level).toBe(s.alerts.some((a) => a.level === "RED") ? "RED" : "YELLOW");
    expect(s.health.reasons).toHaveLength(s.alerts.length);
  });

  it("produces noisy series for every metric, ending early for the unreachable node", () => {
    for (const m of SERIES_METRICS) {
      const s = mockSeries(m, T - 15 * 60_000, T, 10);
      expect(s.metric).toBe(m);
      const pts = s.pointsByNode["10.0.1.11"];
      expect(pts.length).toBe(91);
      for (const [t, v] of pts) { expect(Number.isFinite(t)).toBe(true); expect(Number.isFinite(v)).toBe(true); }
    }
    const cpu = mockSeries("cpu.process_pct", T - 15 * 60_000, T, 10).pointsByNode["10.0.1.11"].map((p) => p[1]);
    expect(new Set(cpu).size).toBeGreaterThan(10);
    expect(mockSeries("heap.used", T - 24 * 3_600_000, T, 10).pointsByNode["10.0.1.11"].length).toBe(1441); // 1/min beyond 1 h
    expect(Object.keys(mockSeries("heap.used", T - 60_000, T, 10, "10.0.2.11").pointsByNode)).toEqual(["10.0.2.11"]);
  });

  it("gives every node 16 Murmur3 tokens and a ring that adds up", async () => {
    const tokens = mockTokens("10.0.1.11");
    expect(tokens).toHaveLength(16);
    for (const t of tokens) {
      const b = BigInt(t);
      expect(b >= -(2n ** 63n) && b < 2n ** 63n).toBe(true);
    }
    const c = createMockMonitoring({ now: () => T, latencyMs: 0 });
    const ring = await c.ring(null);
    expect(ring.keyspace).toBe("shop");
    const owns = ring.datacenters.flatMap((d) => d.nodes).reduce((s, n) => s + (n.ownershipPct ?? 0), 0);
    expect(owns).toBeCloseTo(100, 0);
    for (const n of ring.datacenters[0].nodes) expect(n.effectiveOwnershipPct).toBe(100); // RF 3 on 3 nodes
    const analytics = await c.ring("analytics");
    expect(analytics.datacenters[1].nodes.every((n) => n.effectiveOwnershipPct === 0)).toBe(true);
  });

  it("returns table metrics with all fields and a keyspace filter", async () => {
    const c = createMockMonitoring({ now: () => T, latencyMs: 0 });
    const all = await c.tables();
    for (const t of all) expect(Object.keys(t).sort()).toEqual([...TABLE_KEYS].sort());
    expect(all.some((t) => (t.maxPartitionSizeBytes ?? 0) > 100 * 1024 * 1024)).toBe(true);
    expect((await c.tables("shop")).every((t) => t.keyspace === "shop")).toBe(true);
  });

  it("starts, stops and keeps threshold overrides", async () => {
    const c = createMockMonitoring({ now: () => T, latencyMs: 0 });
    expect((await c.status()).polling).toBe(false);
    expect((await c.start(30)).pollIntervalSec).toBe(30);
    const st = await c.status();
    expect(st.method).toBe("SSH_TUNNEL");
    expect(st.nodes.filter((n) => !n.ok)).toHaveLength(1);
    await c.stop();
    expect((await c.status()).polling).toBe(false);
    expect(await c.thresholds()).toEqual(DEFAULT_THRESHOLDS);
    expect((await c.saveThresholds({ "heap.high.yellowPct": 80 }))["heap.high.yellowPct"]).toBe(80);
    expect((await c.saveThresholds({ "heap.high.yellowPct": null }))["heap.high.yellowPct"]).toBe(85);
  });
});
