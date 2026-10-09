// @vitest-environment node
// Renders the GC chart options with ECharts' server-side SVG renderer, so an option ECharts
// rejects (or a formatter that throws) fails here rather than in the app.
import { describe, expect, it } from "vitest";
import * as echarts from "echarts/core";
import type { EChartsCoreOption } from "echarts/core";
import { BarChart, LineChart, ScatterChart } from "echarts/charts";
import { DataZoomComponent, GridComponent, LegendComponent, TooltipComponent } from "echarts/components";
import { SVGRenderer } from "echarts/renderers";
import { buildGcOption, chartsFor, GC_CHARTS } from "../gcCharts";
import { axisValue, eventsCsv, exportName, fmtK, fmtPause, xOf } from "../gclogFormat";
import type { ChartTheme } from "../../monitoring/palette";
import { sampleReport } from "./sampleReport";

echarts.use([LineChart, ScatterChart, BarChart, GridComponent, TooltipComponent, LegendComponent, DataZoomComponent, SVGRenderer]);

const theme: ChartTheme = { text: "#1b1f24", muted: "#5f6b7a", border: "#d9dde3", panel: "#ffffff", panel2: "#f0f2f5", danger: "#c62828" };

function render(option: EChartsCoreOption): string {
  const chart = echarts.init(null, undefined, { renderer: "svg", ssr: true, width: 520, height: 240 });
  chart.setOption(option);
  const svg = chart.renderToSVGString();
  chart.dispose();
  return svg;
}

describe("GC charts", () => {
  it("renders every chart of a report", () => {
    const r = sampleReport();
    const specs = chartsFor(r);
    expect(specs.map((s) => s.id)).toEqual(GC_CHARTS.map((s) => s.id));
    for (const s of specs) {
      const svg = render(buildGcOption(s.id, r, theme, false) as EChartsCoreOption);
      expect(svg, s.id).toContain("<path");
    }
  });

  it("leaves out charts without data", () => {
    const r = sampleReport();
    const noHeap = sampleReport({ events: r.events.map((e) => ({ ...e, heapAfterK: undefined, heapBeforeK: undefined, oldAfterK: undefined, youngAfterK: undefined, metaAfterK: undefined })),
      series: { ...r.series, allocationMBs: [], promotionMBs: [] } });
    expect(chartsFor(noHeap).map((s) => s.id)).toEqual(["pauses", "gctime", "concurrent", "histogram"]);
  });

  it("uses uptime seconds when the log has no dates", () => {
    const r = sampleReport();
    const up = { ...r.log, timeAxis: "uptime" as const, startTs: undefined };
    expect(axisValue(up, 12.5)).toBe(12.5);
    expect(axisValue(r.log, 12.5)).toBe(r.log.startTs! + 12500);
    expect(xOf(r.log, r.log.startTs! + 3000)).toBe(3);
    const svg = render(buildGcOption("heap", { ...r, log: up }, theme, true) as EChartsCoreOption);
    expect(svg).toContain("uptime (s)");
  });
});

describe("GC report formatting and export", () => {
  it("formats sizes and pauses", () => {
    expect(fmtK(524288)).toBe("512 MiB");
    expect(fmtK(undefined)).toBe("n/a");
    expect(fmtPause(0.012)).toBe("0.012 ms");
    expect(fmtPause(6900)).toBe("6900 ms");
    expect(fmtPause(27.2)).toBe("27.2 ms");
  });

  it("exports events as CSV", () => {
    const r = sampleReport();
    const csv = eventsCsv(r).trim().split("\n");
    expect(csv[0]).toContain("time,uptime_s,gc_id,kind,type");
    expect(csv).toHaveLength(r.events.length + 1);
    expect(csv[5]).toContain("concurrent mode failure");
    expect(csv[1]).toContain("2026-10-09 08:20:01.000");
    expect(exportName(r, "csv")).toBe("gc-report-10.0.0.1_gc.log.csv");
  });
});
