// The only module that loads the ECharts runtime; it is reached through React.lazy views so
// ECharts lands in its own chunk. Tree-shaken: line + pie, four components, canvas renderer.
import { useEffect, useRef } from "react";
import * as echarts from "echarts/core";
import { LineChart, PieChart } from "echarts/charts";
import { GridComponent, TitleComponent, TooltipComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import type { EChartsCoreOption } from "echarts/core";
import { readChartTheme, type ChartTheme } from "./palette";

echarts.use([LineChart, PieChart, GridComponent, TitleComponent, TooltipComponent, CanvasRenderer]);

/** Charts in one group share the crosshair and tooltip (echarts.connect). */
export function connectGroup(group: string): () => void {
  echarts.connect(group);
  return () => echarts.disconnect(group);
}

export interface EChartProps {
  /** Builds the option from theme colours read after the app applied its theme. */
  build: (theme: ChartTheme) => EChartsCoreOption;
  dark: boolean;
  label: string;
  testId: string;
  group?: string;
  height?: number;
  onClick?: (name: string) => void;
  /** Receives the instance (or null on dispose), e.g. to export a PNG. */
  instanceRef?: (chart: echarts.ECharts | null) => void;
}

export function EChart(props: EChartProps) {
  const el = useRef<HTMLDivElement>(null);
  const chart = useRef<echarts.ECharts | null>(null);
  const onClick = useRef(props.onClick);
  onClick.current = props.onClick;
  const { group, instanceRef } = props;

  useEffect(() => {
    const c = echarts.init(el.current!, undefined, { renderer: "canvas" });
    chart.current = c;
    if (group) c.group = group;
    c.on("click", (p) => onClick.current?.(String((p as { name?: string }).name ?? "")));
    instanceRef?.(c);
    const ro = new ResizeObserver(() => c.resize());
    ro.observe(el.current!);
    return () => {
      ro.disconnect();
      instanceRef?.(null);
      c.dispose();
      chart.current = null;
    };
  }, [group, instanceRef]);

  // The theme attribute is set by App in a parent effect that runs after ours, so read the
  // CSS variables on the next frame.
  useEffect(() => {
    const id = requestAnimationFrame(() => chart.current?.setOption(props.build(readChartTheme()), { notMerge: true, lazyUpdate: true }));
    return () => cancelAnimationFrame(id);
  }, [props.build, props.dark]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div
      ref={el}
      role="img"
      aria-label={props.label}
      data-testid={props.testId}
      className="mon-chart-canvas"
      style={{ height: props.height ?? 200 }}
    />
  );
}
