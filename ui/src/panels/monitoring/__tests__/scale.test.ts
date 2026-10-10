import { describe, expect, it } from "vitest";
import { aggregateByDc, buildLineOption, CHART_SPECS, MAX_NODE_LINES, type SeriesData } from "../chartOptions";
import { pointsPerNode } from "../ChartsView";
import { nodeStyles } from "../palette";

describe("charts of large clusters", () => {
  const addrs = Array.from({ length: 40 }, (_, i) => `10.0.${i % 2}.${i}`);
  const dcOf = new Map(addrs.map((a, i) => [a, `dc${i % 2}`]));
  const range = { fromMs: 0, toMs: 240_000 };
  const pointsByNode = Object.fromEntries(addrs.map((a, i) => [a, [[0, i], [120_000, i * 2]] as [number, number][]]));

  it("aggregates per datacenter on a time grid", () => {
    const g = aggregateByDc(pointsByNode, addrs, dcOf, range);
    expect([...g.keys()]).toEqual(["dc0", "dc1"]);
    expect(g.get("dc1")!.max[0][1]).toBe(39);
    expect(g.get("dc0")!.mean[0][1]).toBe(19); // mean of 0, 2, ..., 38
    expect(g.get("dc0")!.max[1][1]).toBeNull(); // a gap where no node has a point
  });

  it("draws max and mean per DC above the line limit, one line per node below it", () => {
    const spec = CHART_SPECS.find((s) => s.id === "cpu")!;
    const data: SeriesData = { "cpu.process_pct": { metric: "cpu.process_pct", unit: "pct", pointsByNode } };
    const theme = { text: "#000", muted: "#555", border: "#ccc", panel: "#fff", panel2: "#eee", danger: "#c00" };
    const styles = nodeStyles(addrs, false);
    const agg = { dcOf, colors: new Map([["dc0", "#111"], ["dc1", "#222"]]) };
    const opt = buildLineOption(spec, data, styles, null, theme, range, agg);
    expect((opt.series as { name: string }[]).map((s) => s.name)).toEqual(["dc0 max", "dc0 mean", "dc1 max", "dc1 mean"]);
    const few = new Set(addrs.slice(0, MAX_NODE_LINES));
    expect((buildLineOption(spec, data, styles, few, theme, range, agg).series as unknown[]).length).toBe(MAX_NODE_LINES);
  });

  it("asks for fewer points per node on big clusters", () => {
    expect(pointsPerNode(3)).toBe(400);
    expect(pointsPerNode(500)).toBe(60);
    expect(pointsPerNode(150)).toBe(200);
  });
});
