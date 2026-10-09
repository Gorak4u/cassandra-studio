import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { ECharts } from "echarts/core";
import type { MonitoringClient } from "../../lib/monitoringApi";
import { download } from "../../lib/export";
import { errorText } from "../../components/feedback";
import { buildLineOption, CHART_METRICS, CHART_SPECS, chartNodes, chartSummary, seriesCsv, type ChartSpec, type SeriesData } from "./chartOptions";
import { Empty, ErrorState, Loading } from "./common";
import { EChart, connectGroup } from "./EChart";
import { readChartTheme, type ChartTheme, type NodeStyle } from "./palette";

export const RANGES = [
  { key: "15m", label: "15 min", ms: 15 * 60_000 },
  { key: "1h", label: "1 h", ms: 3_600_000 },
  { key: "6h", label: "6 h", ms: 6 * 3_600_000 },
  { key: "24h", label: "24 h", ms: 24 * 3_600_000 },
] as const;
type RangeKey = (typeof RANGES)[number]["key"];

interface Loaded { data: SeriesData; fromMs: number; toMs: number; failed: number; firstError: unknown }

/** Time-series dashboards (MON-3, MON-15/16/17, MON-19); refreshes with every poll. */
export default function ChartsView(props: { client: MonitoringClient; styles: Map<string, NodeStyle>; dark: boolean; tick: number; group: string }) {
  const [range, setRange] = useState<RangeKey>("15m");
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [retry, setRetry] = useState(0);
  const instances = useRef(new Map<string, ECharts>());
  const { client, tick, group } = props;
  const rangeDef = RANGES.find((r) => r.key === range)!;

  useEffect(() => connectGroup(group), [group]);

  useEffect(() => {
    let alive = true;
    const toMs = Date.now();
    const fromMs = toMs - rangeDef.ms;
    Promise.allSettled(CHART_METRICS.map((m) => client.series(m, { fromMs, toMs }))).then((results) => {
      if (!alive) return;
      const data: SeriesData = {};
      let failed = 0;
      let firstError: unknown = null;
      results.forEach((r, i) => {
        if (r.status === "fulfilled") data[CHART_METRICS[i]] = r.value;
        else { failed++; firstError ??= r.reason; }
      });
      if (failed === results.length) { setError(firstError); return; }
      setError(null);
      setLoaded({ data, fromMs, toMs, failed, firstError });
    });
    return () => { alive = false; };
  }, [client, rangeDef.ms, tick, retry]);

  const visible = useMemo(() => new Set([...props.styles.keys()].filter((a) => !hidden.has(a))), [props.styles, hidden]);
  const toggle = (a: string) => setHidden((h) => { const n = new Set(h); if (n.has(a)) n.delete(a); else n.add(a); return n; });

  const refs = useMemo(
    () => Object.fromEntries(CHART_SPECS.map((s) => [s.id, (c: ECharts | null) => (c ? instances.current.set(s.id, c) : instances.current.delete(s.id))])),
    [],
  );

  if (error && !loaded) return <ErrorState error={error} onRetry={() => setRetry((r) => r + 1)} />;
  if (!loaded) return <Loading what="metric history" />;
  const anyData = CHART_SPECS.some((s) => chartNodes(s, loaded.data, props.styles, null).length);

  return (
    <div className="pad stack">
      <div className="row mon-toolbar" role="toolbar" aria-label="Chart controls">
        <div className="mon-seg" role="group" aria-label="Time range">
          {RANGES.map((r) => (
            <button key={r.key} className={"btn small" + (r.key === range ? " primary" : "")} aria-pressed={r.key === range} onClick={() => setRange(r.key)}>{r.label}</button>
          ))}
        </div>
        <span className="muted">Nodes:</span>
        {[...props.styles].map(([a, st]) => (
          <button key={a} className={"mon-chip" + (hidden.has(a) ? " off" : "")} aria-pressed={!hidden.has(a)} onClick={() => toggle(a)} title={hidden.has(a) ? "Show " + a : "Hide " + a}>
            <span className="mon-swatch" style={{ background: st.color }} aria-hidden="true" />
            <span className="mono">{a}</span>
          </button>
        ))}
        {hidden.size > 0 && <button className="btn link" onClick={() => setHidden(new Set())}>Show all</button>}
      </div>
      {loaded.failed > 0 && <div className="notice warn">{loaded.failed} of {CHART_METRICS.length} metrics could not be loaded{loaded.firstError ? ": " + errorText(loaded.firstError) : ""}.</div>}
      {!anyData ? (
        <Empty>No history yet. Points appear after the first polls.</Empty>
      ) : (
        <div className="mon-chart-grid">
          {CHART_SPECS.map((spec) => (
            <ChartCard
              key={spec.id}
              spec={spec}
              loaded={loaded}
              styles={props.styles}
              visible={visible}
              dark={props.dark}
              group={group}
              rangeLabel={rangeDef.label}
              instanceRef={refs[spec.id]}
              getInstance={() => instances.current.get(spec.id)}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function ChartCard(props: {
  spec: ChartSpec; loaded: Loaded; styles: Map<string, NodeStyle>; visible: Set<string>; dark: boolean; group: string; rangeLabel: string;
  instanceRef: (c: ECharts | null) => void; getInstance: () => ECharts | undefined;
}) {
  const { spec, loaded, styles, visible } = props;
  const build = useCallback(
    (theme: ChartTheme) => buildLineOption(spec, loaded.data, styles, visible, theme, loaded),
    [spec, loaded, styles, visible],
  );
  const legend = spec.lines.length > 1 ? spec.lines.map((l) => `${l.dashed ? "dashed" : "solid"} = ${l.label}`).join(" · ") : null;
  const savePng = () => {
    const url = props.getInstance()?.getDataURL({ type: "png", pixelRatio: 2, backgroundColor: readChartTheme().panel });
    if (!url) return;
    const a = document.createElement("a");
    a.href = url;
    a.download = `${spec.id}.png`;
    a.click();
  };
  const saveCsv = () => download(`${spec.id}.csv`, seriesCsv(spec, loaded.data, chartNodes(spec, loaded.data, styles, visible)), "text/csv");
  return (
    <div className="panel mon-chart">
      <div className="row">
        <h3>{spec.title}</h3>
        {legend && <span className="muted mon-chart-legend">{legend}</span>}
        <span className="spacer" />
        <button className="btn link" onClick={savePng} aria-label={`Export ${spec.title} as PNG`}>PNG</button>
        <button className="btn link" onClick={saveCsv} aria-label={`Export ${spec.title} as CSV`}>CSV</button>
      </div>
      <EChart
        build={build}
        dark={props.dark}
        group={props.group}
        label={chartSummary(spec, loaded.data, styles, visible, props.rangeLabel)}
        testId={`monitoring-chart-${spec.id}`}
        instanceRef={props.instanceRef}
      />
    </div>
  );
}
