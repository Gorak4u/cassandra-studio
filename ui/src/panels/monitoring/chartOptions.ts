// Pure mapping from contract data to ECharts options (no echarts runtime import, so it is
// unit-testable and stays out of the main bundle).
import type { ComposeOption } from "echarts/core";
import type { LineSeriesOption, PieSeriesOption } from "echarts/charts";
import type { GridComponentOption, TitleComponentOption, TooltipComponentOption } from "echarts/components";
import type { Series, SeriesMetric } from "../../lib/monitoringTypes";
import { fmtBytes, fmtPct, formatterFor, type Unit } from "./format";
import { isDown, stateCode, type TokenRange } from "./health";
import type { ChartTheme, NodeStyle } from "./palette";

export type LineOption = ComposeOption<LineSeriesOption | GridComponentOption | TooltipComponentOption>;
export type RingOption = ComposeOption<PieSeriesOption | TooltipComponentOption | TitleComponentOption>;

export interface LineDef { metric: SeriesMetric; label: string; dashed?: boolean }
export interface ChartSpec { id: string; title: string; unit: Unit; lines: LineDef[] }

/** The time-series dashboards (MON-3, MON-15, MON-16, MON-17, MON-19). */
export const CHART_SPECS: ChartSpec[] = [
  { id: "heap", title: "Heap used vs max", unit: "bytes", lines: [{ metric: "heap.used", label: "used" }, { metric: "heap.max", label: "max", dashed: true }] },
  { id: "gc-time", title: "GC time (pressure)", unit: "pct", lines: [{ metric: "gc.time_pct", label: "GC time" }] },
  { id: "gc-pause", title: "GC pause", unit: "ms", lines: [{ metric: "gc.pause_ms", label: "pause" }] },
  { id: "cpu", title: "CPU (process)", unit: "pct", lines: [{ metric: "cpu.process_pct", label: "CPU" }] },
  { id: "read-rate", title: "Client reads", unit: "perSec", lines: [{ metric: "client.read.rate", label: "reads" }] },
  { id: "write-rate", title: "Client writes", unit: "perSec", lines: [{ metric: "client.write.rate", label: "writes" }] },
  { id: "read-latency", title: "Read latency", unit: "micros", lines: [{ metric: "client.read.p99_us", label: "p99" }, { metric: "client.read.p50_us", label: "p50", dashed: true }] },
  { id: "write-latency", title: "Write latency", unit: "micros", lines: [{ metric: "client.write.p99_us", label: "p99" }, { metric: "client.write.p50_us", label: "p50", dashed: true }] },
  { id: "client-errors", title: "Timeouts and unavailables", unit: "count", lines: [{ metric: "client.timeouts", label: "timeouts" }, { metric: "client.unavailables", label: "unavailables", dashed: true }] },
  { id: "compactions", title: "Pending compactions", unit: "count", lines: [{ metric: "compaction.pending", label: "pending" }] },
  { id: "hints", title: "Hints in progress", unit: "count", lines: [{ metric: "hints.in_progress", label: "hints" }] },
  { id: "dropped", title: "Dropped messages", unit: "count", lines: [{ metric: "dropped.total", label: "dropped" }] },
  { id: "threadpools", title: "Thread pools: pending / blocked", unit: "count", lines: [{ metric: "threadpool.pending_total", label: "pending" }, { metric: "threadpool.blocked_total", label: "blocked", dashed: true }] },
];

export const CHART_METRICS: SeriesMetric[] = [...new Set(CHART_SPECS.flatMap((s) => s.lines.map((l) => l.metric)))];

export type SeriesData = Partial<Record<SeriesMetric, Series>>;

export function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
}

function seriesName(spec: ChartSpec, address: string, line: LineDef): string {
  return spec.lines.length > 1 ? `${address} ${line.label}` : address;
}

/** Nodes shown in a chart: every node with data that the filter keeps, in palette order. */
export function chartNodes(spec: ChartSpec, data: SeriesData, styles: Map<string, NodeStyle>, visible: Set<string> | null): string[] {
  const present = new Set(spec.lines.flatMap((l) => Object.keys(data[l.metric]?.pointsByNode ?? {})));
  return [...styles.keys()].filter((a) => present.has(a) && (!visible || visible.has(a)));
}

export function buildLineOption(
  spec: ChartSpec,
  data: SeriesData,
  styles: Map<string, NodeStyle>,
  visible: Set<string> | null,
  theme: ChartTheme,
  range: { fromMs: number; toMs: number },
): LineOption {
  const fmt = formatterFor(spec.unit);
  const nodes = chartNodes(spec, data, styles, visible);
  const series: LineSeriesOption[] = spec.lines.flatMap((line) =>
    nodes.map((address) => {
      const st = styles.get(address)!;
      return {
        type: "line",
        name: seriesName(spec, address, line),
        data: data[line.metric]?.pointsByNode[address] ?? [],
        showSymbol: false,
        symbolSize: 8,
        sampling: "lttb",
        connectNulls: false,
        color: st.color,
        lineStyle: { width: 2, color: st.color, type: line.dashed ? "dashed" : st.cycle > 0 ? "dotted" : "solid" },
        emphasis: { focus: "series" },
      };
    }),
  );
  return {
    backgroundColor: "transparent",
    animation: false,
    textStyle: { color: theme.text },
    grid: { left: 8, right: 16, top: 12, bottom: 4, containLabel: true },
    tooltip: {
      trigger: "axis",
      confine: true,
      backgroundColor: theme.panel,
      borderColor: theme.border,
      textStyle: { color: theme.text, fontSize: 12 },
      axisPointer: { type: "line", lineStyle: { color: theme.muted } },
      formatter: (params) => tooltipHtml(params as TooltipParam[], fmt, theme),
    },
    xAxis: {
      type: "time",
      min: range.fromMs,
      max: range.toMs,
      axisLine: { lineStyle: { color: theme.border } },
      axisLabel: { color: theme.muted, hideOverlap: true },
      splitLine: { show: false },
    },
    yAxis: {
      type: "value",
      min: 0,
      interval: spec.unit === "bytes" ? binaryInterval(maxValue(series)) : undefined,
      axisLabel: { color: theme.muted, formatter: (v: number) => fmt(v) ?? "" },
      splitLine: { lineStyle: { color: theme.border, opacity: 0.6 } },
    },
    series,
  };
}

function maxValue(series: LineSeriesOption[]): number {
  let m = 0;
  for (const s of series) for (const p of (s.data ?? []) as [number, number][]) if (p[1] > m) m = p[1];
  return m;
}

/** Axis step for byte values on binary boundaries (256 MiB, 1 GiB, 2 GiB, ...) instead of 1.9 GiB. */
export function binaryInterval(max: number, ticks = 5): number | undefined {
  if (!(max > 0)) return undefined;
  const unit = 1024 ** Math.max(0, Math.floor(Math.log(max) / Math.log(1024)));
  for (const f of [1 / 256, 1 / 128, 1 / 64, 1 / 32, 1 / 16, 1 / 8, 1 / 4, 1 / 2, 1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024]) {
    if (unit * f * ticks >= max) return unit * f;
  }
  return undefined;
}

interface TooltipParam { seriesName?: string; value?: unknown; color?: unknown; axisValue?: unknown }

export function tooltipHtml(params: TooltipParam[], fmt: (v: number | null) => string | null, theme: ChartTheme): string {
  if (!params.length) return "";
  const t = Number(params[0].axisValue ?? (params[0].value as [number, number] | undefined)?.[0]);
  const rows = params
    .map((p) => ({ name: p.seriesName ?? "", v: Array.isArray(p.value) ? (p.value[1] as number | null) : null, color: String(p.color ?? theme.muted) }))
    .sort((a, b) => (b.v ?? -Infinity) - (a.v ?? -Infinity))
    .map((r) => `<div style="display:flex;gap:8px;align-items:center"><span style="width:10px;height:3px;background:${escapeHtml(r.color)};display:inline-block"></span>`
      + `<span style="flex:1">${escapeHtml(r.name)}</span><b>${escapeHtml(fmt(r.v) ?? "n/a")}</b></div>`);
  const when = Number.isFinite(t) ? new Date(t).toLocaleTimeString() : "";
  return `<div style="color:${theme.muted};margin-bottom:4px">${escapeHtml(when)}</div>${rows.join("")}`;
}

/** Text alternative for a chart (role="img" aria-label). */
export function chartSummary(spec: ChartSpec, data: SeriesData, styles: Map<string, NodeStyle>, visible: Set<string> | null, rangeLabel: string): string {
  const fmt = formatterFor(spec.unit);
  const nodes = chartNodes(spec, data, styles, visible);
  if (!nodes.length) return `${spec.title}, last ${rangeLabel}: no data.`;
  const main = spec.lines[0];
  const latest = nodes.slice(0, 8).map((a) => {
    const pts = data[main.metric]?.pointsByNode[a] ?? [];
    return `${a} ${fmt(pts.length ? pts[pts.length - 1][1] : null) ?? "n/a"}`;
  });
  return `${spec.title}, last ${rangeLabel}, ${nodes.length} node${nodes.length === 1 ? "" : "s"}. Latest ${main.label}: ${latest.join("; ")}.`;
}

/** CSV export of a chart's data (MON-4). */
export function seriesCsv(spec: ChartSpec, data: SeriesData, nodes: string[]): string {
  const lines = ["time,node,metric,value"];
  for (const l of spec.lines) {
    for (const a of nodes) {
      for (const [t, v] of data[l.metric]?.pointsByNode[a] ?? []) lines.push(`${new Date(t).toISOString()},${a},${l.metric},${v}`);
    }
  }
  return lines.join("\r\n") + "\r\n";
}

// ---- ring --------------------------------------------------------------------

export interface RingNodeInfo { state: string; rack: string | null; loadBytes: number | null; ownershipPct: number | null; effectiveOwnershipPct: number | null; vnodes: number }

/** One DC ring as a donut: each token range is an arc in its owner's colour; down nodes are hatched and faded. */
export function buildRingOption(
  dc: string,
  ranges: TokenRange[],
  styles: Map<string, NodeStyle>,
  info: Map<string, RingNodeInfo>,
  theme: ChartTheme,
): RingOption {
  const nodeCount = new Set(ranges.map((r) => r.address)).size;
  return {
    backgroundColor: "transparent",
    animation: false,
    title: {
      text: dc,
      subtext: `${nodeCount} node${nodeCount === 1 ? "" : "s"} · ${ranges.length} ranges`,
      left: "center",
      top: "center",
      itemGap: 4,
      textStyle: { color: theme.text, fontSize: 14 },
      subtextStyle: { color: theme.muted, fontSize: 11 },
    },
    tooltip: {
      trigger: "item",
      confine: true,
      backgroundColor: theme.panel,
      borderColor: theme.border,
      textStyle: { color: theme.text, fontSize: 12 },
      formatter: (p) => {
        const d = (p as unknown as { data: { name: string; token: string; size: number } }).data;
        const n = info.get(d.name);
        const row = (k: string, v: string | null) => `<div><span style="color:${theme.muted}">${k}</span> ${escapeHtml(v ?? "n/a")}</div>`;
        return `<b>${escapeHtml(d.name)}</b> ${escapeHtml(stateCode(n?.state))}`
          + row("Rack", n?.rack ?? null) + row("Load", fmtBytes(n?.loadBytes)) + row("Owns", fmtPct(n?.ownershipPct))
          + row("Effective", fmtPct(n?.effectiveOwnershipPct)) + row("Vnodes", n ? String(n.vnodes) : null)
          + row("Range end token", d.token) + row("Range size", fmtPct(d.size * 100, 3));
      },
    },
    series: [
      {
        type: "pie",
        radius: ["50%", "80%"],
        startAngle: ranges.length ? 90 - 360 * ranges[0].start : 90,
        clockwise: true,
        avoidLabelOverlap: false,
        label: { show: false },
        labelLine: { show: false },
        emphasis: { scale: true, scaleSize: 4 },
        data: ranges.map((r) => {
          const st = styles.get(r.address);
          const down = isDown(info.get(r.address)?.state);
          return {
            name: r.address,
            value: r.size,
            token: r.token,
            size: r.size,
            itemStyle: {
              color: st?.color ?? theme.muted,
              opacity: down ? 0.45 : 1,
              borderColor: theme.panel,
              borderWidth: 1,
              decal: down ? { symbol: "rect", dashArrayX: [1, 0], dashArrayY: [2, 4], rotation: Math.PI / 4, color: theme.panel } : undefined,
            },
          };
        }),
      },
    ],
  };
}
