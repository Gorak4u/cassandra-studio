// GC report charts (GCL-2) as pure ECharts options: no echarts runtime import, so they are
// unit-testable and stay out of the main bundle.
import type { ComposeOption } from "echarts/core";
import type { BarSeriesOption, LineSeriesOption, ScatterSeriesOption } from "echarts/charts";
import type {
  DataZoomComponentOption, GridComponentOption, LegendComponentOption, TooltipComponentOption,
} from "echarts/components";
import { escapeHtml } from "../monitoring/chartOptions";
import { nodeStyles, type ChartTheme } from "../monitoring/palette";
import { axisValue, fmtK, fmtPause, fmtWhen } from "./gclogFormat";
import type { GcEvent, GcReport } from "./gclogTypes";

export type GcOption = ComposeOption<LineSeriesOption | ScatterSeriesOption | BarSeriesOption | GridComponentOption
  | TooltipComponentOption | LegendComponentOption | DataZoomComponentOption>;

export interface GcChartSpec { id: string; title: string; label: string }

/** The charts in display order; a chart without data for this log is left out by {@link chartsFor}. */
export const GC_CHARTS: GcChartSpec[] = [
  { id: "pauses", title: "Pause time per collection", label: "Scatter of every stop-the-world pause over time, by type" },
  { id: "heap", title: "Heap before and after GC", label: "Heap used before and after each collection, and the heap size" },
  { id: "generations", title: "Young, old and metaspace after GC", label: "Occupancy of the young and old generation and metaspace after each collection" },
  { id: "rates", title: "Allocation and promotion rate", label: "Allocation and promotion rate in MB per second over time" },
  { id: "gctime", title: "Time in GC", label: "Share of time spent in GC pauses per time bucket, in percent" },
  { id: "concurrent", title: "Concurrent phases", label: "Duration of concurrent collector phases over time, by phase" },
  { id: "histogram", title: "Pause histogram", label: "Number of pauses per duration range" },
];

export function chartsFor(r: GcReport): GcChartSpec[] {
  const has = (f: (e: GcEvent) => unknown) => r.events.some((e) => f(e) !== undefined && f(e) !== null);
  return GC_CHARTS.filter((c) => {
    switch (c.id) {
      case "pauses": case "histogram": return r.summary.pauses.count > 0;
      case "heap": return has((e) => e.heapAfterK);
      case "generations": return has((e) => e.oldAfterK ?? e.youngAfterK ?? e.metaAfterK);
      case "rates": return r.series.allocationMBs.length > 0 || r.series.promotionMBs.length > 0;
      case "gctime": return r.series.gcTimePct.length > 0 && r.summary.pauses.count > 0;
      case "concurrent": return r.events.some((e) => e.kind === "concurrent" && e.durationMs > 0);
      default: return false;
    }
  });
}

/** One colour per name (pause type, phase, line), stable for a sorted set of names. */
function colours(names: string[], dark: boolean): Map<string, string> {
  const st = nodeStyles(names, dark);
  return new Map([...st].map(([k, v]) => [k, v.color]));
}

function xAxis(r: GcReport, theme: ChartTheme) {
  const wall = r.log.timeAxis === "wall" && r.log.startTs !== undefined;
  return {
    type: wall ? ("time" as const) : ("value" as const),
    min: axisValue(r.log, r.range.fromX),
    max: axisValue(r.log, r.range.toX),
    name: wall ? undefined : "uptime (s)",
    nameTextStyle: { color: theme.muted },
    axisLine: { lineStyle: { color: theme.border } },
    axisLabel: { color: theme.muted, hideOverlap: true },
    splitLine: { show: false },
  };
}

function base(r: GcReport, theme: ChartTheme, yFormatter: (v: number) => string, zoomSlider: boolean): GcOption {
  return {
    backgroundColor: "transparent",
    animation: false,
    textStyle: { color: theme.text },
    grid: { left: 8, right: 16, top: 30, bottom: zoomSlider ? 44 : 8, containLabel: true },
    legend: { top: 0, type: "scroll", textStyle: { color: theme.text, fontSize: 11 }, pageTextStyle: { color: theme.muted } },
    tooltip: {
      confine: true,
      backgroundColor: theme.panel,
      borderColor: theme.border,
      textStyle: { color: theme.text, fontSize: 12 },
    },
    dataZoom: [
      { type: "inside", xAxisIndex: 0, filterMode: "none" },
      ...(zoomSlider ? [{ type: "slider" as const, xAxisIndex: 0, filterMode: "none" as const, height: 18, bottom: 6,
        textStyle: { color: theme.muted }, borderColor: theme.border }] : []),
    ],
    xAxis: xAxis(r, theme),
    yAxis: {
      type: "value",
      min: 0,
      axisLabel: { color: theme.muted, formatter: yFormatter },
      splitLine: { lineStyle: { color: theme.border, opacity: 0.6 } },
    },
  };
}

const kFmt = (v: number) => fmtK(v);

function eventTooltip(r: GcReport) {
  return (p: unknown) => {
    const d = (p as { data?: { e?: GcEvent } }).data;
    const e = d?.e;
    if (!e) return "";
    const rows = [
      `<b>${escapeHtml(e.type)}</b>${e.cause ? " — " + escapeHtml(e.cause) : ""}`,
      escapeHtml(fmtWhen(r.log, e.x)),
      "Duration: " + escapeHtml(fmtPause(e.durationMs)),
    ];
    if (e.heapBeforeK !== undefined) rows.push(`Heap: ${escapeHtml(fmtK(e.heapBeforeK))} → ${escapeHtml(fmtK(e.heapAfterK))} (${escapeHtml(fmtK(e.heapTotalK))})`);
    if (e.flags?.length) rows.push(escapeHtml(e.flags.join(", ")));
    return rows.join("<br>");
  };
}

/** Scatter of event durations, one series per type. */
function durationsOption(r: GcReport, events: GcEvent[], theme: ChartTheme, dark: boolean, slider: boolean): GcOption {
  const types = [...new Set(events.map((e) => e.type))];
  const col = colours(types, dark);
  const series: ScatterSeriesOption[] = types.map((t) => ({
    type: "scatter",
    name: t,
    symbolSize: 6,
    large: events.length > 5000,
    color: col.get(t),
    data: events.filter((e) => e.type === t).map((e) => ({ value: [axisValue(r.log, e.x), e.durationMs], e })),
  }));
  return { ...base(r, theme, (v) => fmtPause(v), slider), tooltip: { ...base(r, theme, String, false).tooltip as object, trigger: "item", formatter: eventTooltip(r) }, series };
}

function line(name: string, data: [number, number | null][], color: string | undefined, dashed = false): LineSeriesOption {
  return { type: "line", name, data, showSymbol: false, sampling: "lttb", connectNulls: true, color, lineStyle: { width: 2, color, type: dashed ? "dashed" : "solid" } };
}

function axisTooltip(r: GcReport, fmt: (v: number) => string, theme: ChartTheme) {
  return (params: unknown) => {
    const ps = params as { seriesName?: string; value?: [number, number | null]; color?: string }[];
    if (!ps.length || !ps[0].value) return "";
    const head = r.log.timeAxis === "wall" ? new Date(ps[0].value[0]).toLocaleString() : ps[0].value[0].toFixed(1) + " s";
    return `<div style="color:${theme.muted}">${escapeHtml(head)}</div>` + ps.map((p) =>
      `<div>${escapeHtml(p.seriesName ?? "")}: <b>${escapeHtml(p.value?.[1] === null || p.value?.[1] === undefined ? "n/a" : fmt(p.value[1]))}</b></div>`).join("");
  };
}

export function buildGcOption(id: string, r: GcReport, theme: ChartTheme, dark: boolean): GcOption {
  const pauses = r.events.filter((e) => e.kind === "pause");
  const ax = (e: GcEvent) => axisValue(r.log, e.x);
  switch (id) {
    case "pauses":
      return durationsOption(r, pauses, theme, dark, true);
    case "concurrent":
      return durationsOption(r, r.events.filter((e) => e.kind === "concurrent" && e.durationMs > 0), theme, dark, false);
    case "heap": {
      const withHeap = r.events.filter((e) => e.heapAfterK !== undefined);
      const col = colours(["after", "before", "size"], dark);
      const o = base(r, theme, kFmt, false);
      return {
        ...o,
        tooltip: { ...(o.tooltip as object), trigger: "axis", formatter: axisTooltip(r, kFmt, theme) },
        series: [
          { type: "scatter", name: "before GC", symbolSize: 4, color: col.get("before"), data: withHeap.filter((e) => e.heapBeforeK !== undefined).map((e) => [ax(e), e.heapBeforeK!]) },
          line("after GC", withHeap.map((e) => [ax(e), e.heapAfterK!]), col.get("after")),
          line("heap size", withHeap.filter((e) => e.heapTotalK !== undefined).map((e) => [ax(e), e.heapTotalK!]), col.get("size"), true),
        ],
      };
    }
    case "generations": {
      const col = colours(["humongous", "metaspace", "old", "young"], dark);
      const o = base(r, theme, kFmt, false);
      const pick = (f: (e: GcEvent) => number | undefined) => pauses.filter((e) => f(e) !== undefined).map((e) => [ax(e), f(e)!] as [number, number]);
      const series = [
        line("young after GC", pick((e) => e.youngAfterK), col.get("young")),
        line("old after GC", pick((e) => e.oldAfterK), col.get("old")),
        line("metaspace", pick((e) => e.metaAfterK), col.get("metaspace")),
        line("humongous", pick((e) => e.humongousAfterK), col.get("humongous"), true),
      ].filter((s) => (s.data as unknown[]).length > 0);
      return { ...o, tooltip: { ...(o.tooltip as object), trigger: "axis", formatter: axisTooltip(r, kFmt, theme) }, series };
    }
    case "rates": {
      const col = colours(["allocation", "promotion"], dark);
      const fmt = (v: number) => (v >= 100 ? v.toFixed(0) : v.toFixed(1)) + " MB/s";
      const o = base(r, theme, fmt, false);
      const pts = (s: [number, number][]) => s.map(([x, v]) => [axisValue(r.log, x), v] as [number, number]);
      return {
        ...o,
        tooltip: { ...(o.tooltip as object), trigger: "axis", formatter: axisTooltip(r, fmt, theme) },
        series: [
          line("allocation", pts(r.series.allocationMBs), col.get("allocation")),
          line("promotion", pts(r.series.promotionMBs), col.get("promotion"), true),
        ].filter((s) => (s.data as unknown[]).length > 0),
      };
    }
    case "gctime": {
      const fmt = (v: number) => v.toFixed(v >= 10 ? 0 : 1) + " %";
      const o = base(r, theme, fmt, false);
      return {
        ...o,
        tooltip: { ...(o.tooltip as object), trigger: "axis", formatter: axisTooltip(r, fmt, theme) },
        series: [line(`time in GC per ${r.series.bucketSec} s`, r.series.gcTimePct.map(([x, v]) => [axisValue(r.log, x), v]), theme.danger)],
      };
    }
    case "histogram": {
      const h = r.summary.histogram;
      return {
        backgroundColor: "transparent",
        animation: false,
        textStyle: { color: theme.text },
        grid: { left: 8, right: 16, top: 12, bottom: 8, containLabel: true },
        tooltip: { trigger: "axis", confine: true, backgroundColor: theme.panel, borderColor: theme.border, textStyle: { color: theme.text, fontSize: 12 } },
        xAxis: { type: "category", data: h.map((b) => b.label), axisLabel: { color: theme.muted, interval: 0, rotate: 30 }, axisLine: { lineStyle: { color: theme.border } } },
        yAxis: { type: "value", minInterval: 1, axisLabel: { color: theme.muted }, splitLine: { lineStyle: { color: theme.border, opacity: 0.6 } } },
        series: [{ type: "bar", name: "pauses", data: h.map((b) => b.count), color: colours(["pauses"], dark).get("pauses") }],
      } as GcOption;
    }
    default:
      return {};
  }
}
