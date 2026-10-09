import { describe, expect, it } from "vitest";
import type { LineSeriesOption, PieSeriesOption } from "echarts/charts";
import { binaryInterval, buildLineOption, buildRingOption, CHART_METRICS, CHART_SPECS, chartSummary, seriesCsv, tooltipHtml, type SeriesData } from "../chartOptions";
import { nodeStyles, type ChartTheme } from "../palette";
import { tokenRanges } from "../health";
import { fmtBytes } from "../format";
import { SERIES_METRICS } from "../../../lib/monitoringTypes";

const theme: ChartTheme = { text: "#111", muted: "#666", border: "#ddd", panel: "#fff", panel2: "#eee", danger: "#c00" };
const heap = CHART_SPECS.find((s) => s.id === "heap")!;
const data: SeriesData = {
  "heap.used": { metric: "heap.used", unit: "bytes", pointsByNode: { "10.0.0.2": [[1000, 1024], [2000, 2048]], "10.0.0.10": [[1000, 4096]] } },
  "heap.max": { metric: "heap.max", unit: "bytes", pointsByNode: { "10.0.0.2": [[1000, 8192]], "10.0.0.10": [[1000, 8192]] } },
};

describe("chart specs", () => {
  it("only use metrics from the contract", () => {
    for (const m of CHART_METRICS) expect(SERIES_METRICS).toContain(m);
    expect(new Set(CHART_SPECS.map((s) => s.id)).size).toBe(CHART_SPECS.length);
  });
});

describe("buildLineOption", () => {
  const styles = nodeStyles(["10.0.0.10", "10.0.0.2", "10.0.0.3"], false);

  it("draws one line per node and metric, coloured by node, dashed for secondary metrics", () => {
    const o = buildLineOption(heap, data, styles, null, theme, { fromMs: 0, toMs: 3000 });
    const s = o.series as LineSeriesOption[];
    expect(s.map((x) => x.name)).toEqual(["10.0.0.2 used", "10.0.0.10 used", "10.0.0.2 max", "10.0.0.10 max"]);
    expect(s[0].color).toBe(styles.get("10.0.0.2")!.color);
    expect(s[2].color).toBe(styles.get("10.0.0.2")!.color);
    expect(s[0].lineStyle!.type).toBe("solid");
    expect(s[2].lineStyle!.type).toBe("dashed");
    expect(s[0].data).toEqual([[1000, 1024], [2000, 2048]]);
    expect(o.xAxis).toMatchObject({ type: "time", min: 0, max: 3000 });
    const fmt = (o.yAxis as unknown as { axisLabel: { formatter: (v: number) => string } }).axisLabel.formatter;
    expect(fmt(1024 ** 3)).toBe("1 GiB");
  });

  it("steps byte axes on binary boundaries", () => {
    const G = 1024 ** 3;
    expect(binaryInterval(8.4 * G)).toBe(2 * G);
    expect(binaryInterval(3 * G)).toBe(G);
    expect(binaryInterval(900 * 1024 ** 2)).toBe(256 * 1024 ** 2);
    expect(binaryInterval(0)).toBeUndefined();
    const o = buildLineOption(heap, data, styles, null, theme, { fromMs: 0, toMs: 1 });
    expect((o.yAxis as { interval?: number }).interval).toBe(2048);
  });

  it("keeps node colours when a filter hides nodes", () => {
    const all = buildLineOption(heap, data, styles, null, theme, { fromMs: 0, toMs: 1 }).series as LineSeriesOption[];
    const some = buildLineOption(heap, data, styles, new Set(["10.0.0.10"]), theme, { fromMs: 0, toMs: 1 }).series as LineSeriesOption[];
    expect(some.map((x) => x.name)).toEqual(["10.0.0.10 used", "10.0.0.10 max"]);
    expect(some[0].color).toBe(all[1].color);
  });

  it("handles missing data", () => {
    const gc = CHART_SPECS.find((s) => s.id === "gc-time")!;
    expect(buildLineOption(gc, {}, styles, null, theme, { fromMs: 0, toMs: 1 }).series).toEqual([]);
    expect(chartSummary(gc, {}, styles, null, "15 min")).toBe("GC time (pressure), last 15 min: no data.");
  });

  it("summarises the latest values for screen readers", () => {
    expect(chartSummary(heap, data, styles, null, "1 h")).toBe("Heap used vs max, last 1 h, 2 nodes. Latest used: 10.0.0.2 2 KiB; 10.0.0.10 4 KiB.");
  });

  it("renders an escaped, sorted tooltip with n/a for gaps", () => {
    const html = tooltipHtml(
      [{ seriesName: "<a>", value: [1, 5], color: "#123456", axisValue: 1 }, { seriesName: "b", value: [1, null], color: "#654321" }, { seriesName: "c", value: [1, 9] }],
      (v) => fmtBytes(v),
      theme,
    );
    expect(html).toContain("&lt;a&gt;");
    expect(html.indexOf(">c<")).toBeLessThan(html.indexOf("&lt;a&gt;"));
    expect(html).toContain("n/a");
  });

  it("exports CSV", () => {
    const csv = seriesCsv(heap, data, ["10.0.0.2"]);
    expect(csv.split("\r\n")[0]).toBe("time,node,metric,value");
    expect(csv).toContain("1970-01-01T00:00:01.000Z,10.0.0.2,heap.used,1024");
    expect(csv).toContain("heap.max,8192");
    expect(csv).not.toContain("10.0.0.10");
  });
});

describe("palette", () => {
  it("assigns colours by sorted address, stable across subsets, different per theme", () => {
    const a = nodeStyles(["10.0.0.10", "10.0.0.2"], false);
    expect([...a.keys()]).toEqual(["10.0.0.2", "10.0.0.10"]);
    const dark = nodeStyles(["10.0.0.10", "10.0.0.2"], true);
    expect(dark.get("10.0.0.2")!.color).not.toBe(a.get("10.0.0.2")!.color);
    const many = nodeStyles(Array.from({ length: 10 }, (_, i) => `10.0.0.${i + 1}`), false);
    expect(many.get("10.0.0.9")).toEqual({ color: many.get("10.0.0.1")!.color, cycle: 1 });
  });
});

describe("buildRingOption", () => {
  it("draws token ranges as arcs in owner colours and marks down nodes", () => {
    const nodes = [
      { hostId: null, address: "a", rack: "r1", state: "UN", loadBytes: 1, tokens: ["0"], ownershipPct: 50, effectiveOwnershipPct: 100 },
      { hostId: null, address: "b", rack: "r2", state: "DN", loadBytes: 1, tokens: ["-4611686018427387904", "4611686018427387904"], ownershipPct: 50, effectiveOwnershipPct: 100 },
    ];
    const ranges = tokenRanges(nodes, "Murmur3Partitioner");
    const styles = nodeStyles(["a", "b"], false);
    const info = new Map(nodes.map((n) => [n.address, {
      state: n.state, rack: n.rack, loadBytes: n.loadBytes, ownershipPct: n.ownershipPct, effectiveOwnershipPct: n.effectiveOwnershipPct, vnodes: n.tokens.length,
    }]));
    const o = buildRingOption("dc1", ranges, styles, info, theme);
    const pie = (o.series as PieSeriesOption[])[0];
    const d = pie.data as unknown as { name: string; value: number; itemStyle: { color: string; opacity: number; decal?: unknown } }[];
    expect(d.map((x) => x.name)).toEqual(["b", "a", "b"]);
    expect(d[1].itemStyle).toMatchObject({ color: styles.get("a")!.color, opacity: 1 });
    expect(d[0].itemStyle.opacity).toBeLessThan(1);
    expect(d[0].itemStyle.decal).toBeDefined();
    expect(pie.startAngle).toBeCloseTo(90 - 360 * 0.75);
    expect(o.title).toMatchObject({ text: "dc1", subtext: "2 nodes · 3 ranges" });
  });
});
