// @vitest-environment node
// Renders the real options with ECharts' server-side SVG renderer, so an option ECharts
// rejects (or that throws inside a formatter) fails here rather than in the app.
import { describe, expect, it } from "vitest";
import * as echarts from "echarts/core";
import type { EChartsCoreOption } from "echarts/core";
import { LineChart, PieChart } from "echarts/charts";
import { GridComponent, TitleComponent, TooltipComponent } from "echarts/components";
import { SVGRenderer } from "echarts/renderers";
import { buildLineOption, buildRingOption, CHART_SPECS, type SeriesData } from "../chartOptions";
import { tokenRanges } from "../health";
import { nodeStyles, type ChartTheme } from "../palette";
import { mockSeries, MOCK_NODES, createMockMonitoring } from "../../../lib/monitoringMock";

echarts.use([LineChart, PieChart, GridComponent, TitleComponent, TooltipComponent, SVGRenderer]);

const theme: ChartTheme = { text: "#1b1f24", muted: "#5f6b7a", border: "#d9dde3", panel: "#ffffff", panel2: "#f0f2f5", danger: "#c62828" };
const T = Date.UTC(2026, 9, 9, 12);
const styles = nodeStyles(MOCK_NODES.map((n) => n.address), false);

function render(option: EChartsCoreOption, width = 480, height = 220): string {
  const chart = echarts.init(null, undefined, { renderer: "svg", ssr: true, width, height });
  chart.setOption(option);
  const svg = chart.renderToSVGString();
  chart.dispose();
  return svg;
}

describe("ECharts rendering of the options", () => {
  it("renders every time-series chart from mock data", () => {
    const fromMs = T - 3_600_000;
    for (const spec of CHART_SPECS) {
      const data: SeriesData = {};
      for (const l of spec.lines) data[l.metric] = mockSeries(l.metric, fromMs, T, 10);
      const svg = render(buildLineOption(spec, data, styles, null, theme, { fromMs, toMs: T }));
      expect(svg).toContain("<path");
      expect(svg).toContain(styles.get("10.0.1.11")!.color);
    }
  });

  it("renders a DC ring", async () => {
    const ring = await createMockMonitoring({ latencyMs: 0, now: () => T }).ring(null);
    const dc = ring.datacenters[0];
    const info = new Map(dc.nodes.map((n) => [n.address, { state: n.state, rack: n.rack, loadBytes: n.loadBytes, ownershipPct: n.ownershipPct, effectiveOwnershipPct: n.effectiveOwnershipPct, vnodes: n.tokens.length }]));
    const svg = render(buildRingOption(dc.name, tokenRanges(dc.nodes, ring.partitioner), styles, info, theme), 320, 280);
    expect(svg).toContain(dc.name);
    expect((svg.match(/<path/g) ?? []).length).toBeGreaterThanOrEqual(48);
  });
});
