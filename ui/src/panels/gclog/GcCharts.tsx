// Loaded lazily (with ECharts) by the GC logs panel. Adds the chart types the GC report needs
// to the shared ECharts runtime that monitoring/EChart.tsx sets up.
import { useCallback, useEffect, useMemo, useRef } from "react";
import * as echarts from "echarts/core";
import { BarChart, ScatterChart } from "echarts/charts";
import { DataZoomComponent, LegendComponent } from "echarts/components";
import { connectGroup, EChart } from "../monitoring/EChart";
import { buildGcOption, chartsFor } from "./gcCharts";
import { xOf } from "./gclogFormat";
import type { GcReport } from "./gclogTypes";
import type { ChartTheme } from "../monitoring/palette";

echarts.use([ScatterChart, BarChart, DataZoomComponent, LegendComponent]);

/** All GC charts on one shared, zoomable time axis; reports the zoomed window in x seconds. */
export default function GcCharts(props: { report: GcReport; dark: boolean; onZoom: (range: { fromX: number; toX: number } | null) => void }) {
  const { report, dark, onZoom } = props;
  const group = "gclog-" + report.id;
  const onZoomRef = useRef(onZoom);
  onZoomRef.current = onZoom;
  useEffect(() => connectGroup(group), [group]);

  const zoomRef = useCallback((chart: echarts.ECharts | null) => {
    if (!chart) return;
    chart.on("datazoom", () => {
      const dz = (chart.getOption() as { dataZoom?: { startValue?: number; endValue?: number; start?: number; end?: number }[] }).dataZoom?.[0];
      if (!dz || dz.startValue === undefined || dz.endValue === undefined) return;
      const whole = (dz.start ?? 0) <= 0.01 && (dz.end ?? 100) >= 99.99;
      onZoomRef.current(whole ? null : { fromX: xOf(report.log, dz.startValue), toX: xOf(report.log, dz.endValue) });
    });
  }, [report]);

  const specs = useMemo(() => chartsFor(report), [report]);
  // stable builders: a new function would re-apply the option and reset the zoom
  const builders = useMemo(() => new Map(specs.map((s) => [s.id, (theme: ChartTheme) => buildGcOption(s.id, report, theme, dark)])),
    [specs, report, dark]);
  if (!specs.length) return <div className="empty">This log has no data to chart.</div>;
  return (
    <div className="gcl-charts">
      {specs.map((s) => (
        <section key={s.id + report.id + report.range.fromX + report.range.toX} className="panel gcl-chart" aria-labelledby={`gcl-chart-${s.id}`}>
          <h3 id={`gcl-chart-${s.id}`}>{s.title}</h3>
          <EChart
            build={builders.get(s.id)!}
            dark={dark}
            label={s.label}
            testId={`gclog-chart-${s.id}`}
            group={s.id === "histogram" ? undefined : group}
            height={s.id === "pauses" ? 260 : 200}
            instanceRef={s.id === "pauses" ? zoomRef : undefined}
          />
        </section>
      ))}
    </div>
  );
}
