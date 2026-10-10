import { lazy, Suspense, useCallback, useMemo, useState, type KeyboardEvent } from "react";
import { mockRequested, monitoringClient } from "../../lib/monitoringApi";
import type { ClusterInfo, ConnectionConfig } from "../../lib/types";
import { errorText } from "../../components/feedback";
import { HelpLink } from "../../components/HelpLink";
import { ErrorState, Loading } from "./common";
import { accessProblem } from "./health";
import { HealthView } from "./HealthView";
import { NodeDrawer } from "./NodeDrawer";
import { NodesView } from "./NodesView";
import { nodeStyles } from "./palette";
import { TablesView } from "./TablesView";
import { ThresholdsView } from "./ThresholdsView";
import { useMonitoring } from "./useMonitoring";
import "./monitoring.css";

// ECharts is only loaded with these two views, in its own chunk.
const ChartsView = lazy(() => import("./ChartsView"));
const RingView = lazy(() => import("./RingView"));

type View = "health" | "nodes" | "charts" | "ring" | "tables" | "thresholds";
const VIEWS: [View, string][] = [
  ["health", "Health"], ["nodes", "Nodes"], ["charts", "Charts"], ["ring", "Ring"], ["tables", "Tables"], ["thresholds", "Thresholds"],
];
const INTERVALS = [5, 10, 30, 60];

/** Live JMX dashboards for one cluster (MON-1..MON-18, ALR-1). */
export function MonitoringPanel(props: { conn: ConnectionConfig; info: ClusterInfo; dark: boolean }) {
  const id = props.conn.id!;
  const [demo, setDemo] = useState(mockRequested);
  const client = useMemo(() => monitoringClient(id, () => setDemo(true)), [id]);
  const [intervalSec, setIntervalSec] = useState(10);
  const m = useMonitoring(client, intervalSec);
  const [view, setView] = useState<View>("health");
  const [selected, setSelected] = useState<string | null>(null);
  const closeDrawer = useCallback(() => setSelected(null), []);

  const addressKey = (m.snapshot?.nodes.map((n) => n.address) ?? []).join(",");
  const styles = useMemo(() => nodeStyles(addressKey ? addressKey.split(",") : [], props.dark), [addressKey, props.dark]);
  const problem = accessProblem(m.status, m.snapshot?.nodes ?? null);

  const onTabKey = (e: KeyboardEvent) => {
    const i = VIEWS.findIndex(([k]) => k === view);
    const next = e.key === "ArrowRight" ? i + 1 : e.key === "ArrowLeft" ? i - 1 : e.key === "Home" ? 0 : e.key === "End" ? VIEWS.length - 1 : null;
    if (next === null) return;
    e.preventDefault();
    const k = VIEWS[(next + VIEWS.length) % VIEWS.length][0];
    setView(k);
    document.getElementById(`mon-tab-${id}-${k}`)?.focus();
  };

  const needsSnapshot = view === "health" || view === "nodes" || view === "charts" || view === "ring";
  let body;
  if (needsSnapshot && !m.snapshot) {
    body = m.error ? <ErrorState error={m.error} onRetry={m.paused ? m.resume : m.refresh} /> : m.paused ? <div className="empty">Monitoring is paused.</div> : <Loading what="the first poll" />;
  } else if (view === "health") body = <HealthView snapshot={m.snapshot!} info={props.info} onNode={setSelected} />;
  else if (view === "nodes") body = <NodesView nodes={m.snapshot!.nodes} alerts={m.snapshot!.alerts} styles={styles} onNode={setSelected} />;
  else if (view === "charts") body = <ChartsView client={client} styles={styles} dark={props.dark} tick={m.tick} group={`monitoring-${id}`} />;
  else if (view === "ring") body = <RingView client={client} styles={styles} dark={props.dark} nodes={m.snapshot!.nodes} onNode={setSelected} />;
  else if (view === "tables") body = <TablesView client={client} />;
  else body = <ThresholdsView client={client} />;

  return (
    <div className="mon" data-testid="monitoring-panel">
      <div className="mon-bar-top">
        <div className="mon-subtabs" role="tablist" aria-label="Monitoring views" onKeyDown={onTabKey}>
          {VIEWS.map(([k, label]) => (
            <button
              key={k}
              id={`mon-tab-${id}-${k}`}
              role="tab"
              aria-selected={view === k}
              aria-controls={`mon-view-${id}`}
              tabIndex={view === k ? 0 : -1}
              className={"mon-subtab" + (view === k ? " active" : "")}
              onClick={() => setView(k)}
            >
              {label}
              {k === "health" && m.snapshot && m.snapshot.health.level !== "GREEN" && (
                <span className={`mon-dot ${m.snapshot.health.level}`} aria-label={m.snapshot.health.level} />
              )}
            </button>
          ))}
        </div>
        <span className="spacer" />
        {demo && <span className="mon-demo" title="The engine has no monitoring routes yet, so this is generated sample data" data-testid="monitoring-demo">demo data</span>}
        {m.snapshot && <span className="muted" title={new Date(m.snapshot.atEpochMs).toLocaleString()}>Updated {new Date(m.snapshot.atEpochMs).toLocaleTimeString()}</span>}
        <label className="row">
          <span className="muted">Every</span>
          <select value={intervalSec} onChange={(e) => setIntervalSec(Number(e.target.value))} aria-label="Poll interval">
            {INTERVALS.map((s) => <option key={s} value={s}>{s} s</option>)}
          </select>
        </label>
        {m.paused
          ? <button className="btn small primary" onClick={m.resume}>▶ Resume</button>
          : <button className="btn small" onClick={m.pause} title="Stop polling this cluster's JMX">⏸ Pause</button>}
        <button className="btn small" onClick={m.refresh} disabled={m.paused} aria-label="Poll now">⟳</button>
        <HelpLink topic="monitoring" />
      </div>

      {problem && (
        <div className="notice warn mon-banner" role="alert" data-testid="monitoring-access-banner">
          <b>JMX not reachable on {problem.failed} of {problem.total} node{problem.total === 1 ? "" : "s"} via {problem.method}</b> — {problem.errors[0].error}
          {problem.errors.length > 1 && (
            <ul>{problem.errors.slice(1).map((e) => <li key={e.address}><span className="mono">{e.address}</span>: {e.error}</li>)}</ul>
          )}
          <div className="mon-hint">Metrics from these nodes show as n/a. Check the JMX and SSH settings of this connection (Edit connection → JMX / SSH).</div>
        </div>
      )}
      {m.paused && <div className="notice info mon-banner">Monitoring is paused for this cluster. Resume to poll again.</div>}
      {m.error != null && m.snapshot && (
        <div className="notice error mon-banner" role="alert">Last poll failed; showing the previous data. {errorText(m.error)}</div>
      )}

      <div className="mon-body" id={`mon-view-${id}`} role="tabpanel" aria-labelledby={`mon-tab-${id}-${view}`}>
        <Suspense fallback={<Loading what="charts" />}>{body}</Suspense>
      </div>

      {selected && (
        <NodeDrawer address={selected} node={m.snapshot?.nodes.find((n) => n.address === selected) ?? null} onClose={closeDrawer} />
      )}
    </div>
  );
}
