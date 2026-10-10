import { memo, useCallback, useDeferredValue, useEffect, useMemo, useRef, useState } from "react";
import type { ECharts } from "echarts/core";
import type { MonitoringClient } from "../../lib/monitoringApi";
import { download } from "../../lib/export";
import { errorText } from "../../components/feedback";
import {
  aggregated, buildLineOption, CHART_METRICS, CHART_SPECS, chartNodes, chartSummary, MAX_NODE_LINES, seriesCsv, type ChartSpec, type DcAggregation, type SeriesData,
} from "./chartOptions";
import { Empty, ErrorState, Loading } from "./common";
import { EChart, connectGroup } from "./EChart";
import { categoricalColors, readChartTheme, type ChartTheme, type NodeStyle } from "./palette";

export const RANGES = [
  { key: "15m", label: "15 min", ms: 15 * 60_000 },
  { key: "1h", label: "1 h", ms: 3_600_000 },
  { key: "6h", label: "6 h", ms: 6 * 3_600_000 },
  { key: "24h", label: "24 h", ms: 24 * 3_600_000 },
] as const;
type RangeKey = (typeof RANGES)[number]["key"];

/**
 * Points per node and metric asked from the engine: about the chart's pixel width for small
 * clusters, fewer for large ones so a 24 h view of 500 nodes stays a few MB (NFR-SCALE).
 */
export function pointsPerNode(nodes: number): number {
  return Math.max(60, Math.min(400, Math.floor(30_000 / Math.max(1, nodes))));
}

interface Loaded { data: SeriesData; fromMs: number; toMs: number; failed: number; firstError: unknown }

/** Time-series dashboards (MON-3, MON-15/16/17, MON-19); refreshes with every poll. */
export default function ChartsView(props: {
  client: MonitoringClient; styles: Map<string, NodeStyle>; dark: boolean; tick: number; group: string;
  /** Datacenter per node address: charts of more than MAX_NODE_LINES nodes draw per-DC max and mean. */
  dcOf?: Map<string, string>;
}) {
  const [range, setRange] = useState<RangeKey>("15m");
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [retry, setRetry] = useState(0);
  const instances = useRef(new Map<string, ECharts>());
  const { client, tick, group } = props;
  const rangeDef = RANGES.find((r) => r.key === range)!;
  const nodeCount = props.styles.size;

  useEffect(() => connectGroup(group), [group]);

  useEffect(() => {
    let alive = true;
    const toMs = Date.now();
    const fromMs = toMs - rangeDef.ms;
    const maxPoints = pointsPerNode(nodeCount);
    Promise.allSettled(CHART_METRICS.map((m) => client.series(m, { fromMs, toMs, maxPoints }))).then((results) => {
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
  }, [client, rangeDef.ms, tick, retry, nodeCount]);

  // The chips update at once; the charts (heavy with hundreds of nodes) follow in a deferred render.
  const deferredHidden = useDeferredValue(hidden);
  const visible = useMemo(() => new Set([...props.styles.keys()].filter((a) => !deferredHidden.has(a))), [props.styles, deferredHidden]);
  const agg = useMemo<DcAggregation | undefined>(
    () => (props.dcOf ? { dcOf: props.dcOf, colors: categoricalColors(props.dcOf.values(), props.dark) } : undefined),
    [props.dcOf, props.dark],
  );
  const toggle = (a: string) => setHidden((h) => { const n = new Set(h); if (n.has(a)) n.delete(a); else n.add(a); return n; });

  const getInstance = useCallback((id: string) => instances.current.get(id), []);
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
      {aggregated(visible.size, agg) && (
        <div className="muted" data-testid="monitoring-charts-aggregated">
          {visible.size} nodes: lines show the maximum (solid) and mean (dotted) per datacenter. Hide nodes until {MAX_NODE_LINES} or fewer remain to see one line per node.
        </div>
      )}
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
              agg={agg}
              getInstance={getInstance}
            />
          ))}
        </div>
      )}
    </div>
  );
}

/** Memoised: toggling a node chip re-renders the chips at once, the charts only when their data changes. */
const ChartCard = memo(function ChartCard(props: {
  spec: ChartSpec; loaded: Loaded; styles: Map<string, NodeStyle>; visible: Set<string>; dark: boolean; group: string; rangeLabel: string;
  instanceRef: (c: ECharts | null) => void; getInstance: (id: string) => ECharts | undefined; agg?: DcAggregation;
}) {
  const { spec, loaded, styles, visible } = props;
  const build = useCallback(
    (theme: ChartTheme) => buildLineOption(spec, loaded.data, styles, visible, theme, loaded, props.agg),
    [spec, loaded, styles, visible, props.agg],
  );
  // Charts scrolled out of view keep their last drawing and catch up when they come back, so a poll
  // or a node toggle redraws only the visible ones.
  const box = useRef<HTMLDivElement | null>(null);
  const [onScreen, setOnScreen] = useState(true);
  useEffect(() => {
    const el = box.current;
    if (!el || typeof IntersectionObserver === "undefined") return;
    const io = new IntersectionObserver(([e]) => setOnScreen(e.isIntersecting), { rootMargin: "200px" });
    io.observe(el);
    return () => io.disconnect();
  }, []);
  const shown = useRef(build);
  if (onScreen) shown.current = build;
  const legend = spec.lines.length > 1 ? spec.lines.map((l) => `${l.dashed ? "dashed" : "solid"} = ${l.label}`).join(" · ") : null;
  const savePng = () => {
    const url = props.getInstance(spec.id)?.getDataURL({ type: "png", pixelRatio: 2, backgroundColor: readChartTheme().panel });
    if (!url) return;
    const a = document.createElement("a");
    a.href = url;
    a.download = `${spec.id}.png`;
    a.click();
  };
  const saveCsv = () => download(`${spec.id}.csv`, seriesCsv(spec, loaded.data, chartNodes(spec, loaded.data, styles, visible)), "text/csv");
  return (
    <div className="panel mon-chart" ref={box}>
      <div className="row">
        <h3>{spec.title}</h3>
        {legend && <span className="muted mon-chart-legend">{legend}</span>}
        <span className="spacer" />
        <button className="btn link" onClick={savePng} aria-label={`Export ${spec.title} as PNG`}>PNG</button>
        <button className="btn link" onClick={saveCsv} aria-label={`Export ${spec.title} as CSV`}>CSV</button>
      </div>
      <EChart
        build={shown.current}
        dark={props.dark}
        group={props.group}
        label={chartSummary(spec, loaded.data, styles, visible, props.rangeLabel, props.agg)}
        testId={`monitoring-chart-${spec.id}`}
        instanceRef={props.instanceRef}
      />
    </div>
  );
});
