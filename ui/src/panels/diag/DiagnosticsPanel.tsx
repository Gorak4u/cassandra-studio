import { useId, useMemo, useState, type KeyboardEvent } from "react";
import type { ClusterInfo, ConnectionConfig } from "../../lib/types";
import { diagApi } from "./diagApi";
import { DumpsView, TopThreadsView } from "./ThreadsView";
import { PartitionsView } from "./PartitionsView";
import "../monitoring/monitoring.css";
import "./diag.css";

type Tab = "threads" | "partitions";
const TABS: [Tab, string][] = [["threads", "Threads"], ["partitions", "Partitions"]];

/**
 * Diagnostics (JVM-1/2, PRF-1/2): thread dumps and top threads over JMX for one node, and hot
 * partitions, table histograms and log warnings for the cluster.
 */
export function DiagnosticsPanel(props: { conn: ConnectionConfig; info: ClusterInfo; dark: boolean }) {
  const id = useId();
  const connId = props.conn.id ?? "";
  const client = useMemo(() => diagApi(connId), [connId]);
  const nodes = useMemo(() => props.info.nodes.map((n) => n.address).sort(), [props.info.nodes]);
  const [tab, setTab] = useState<Tab>("threads");
  const [threadView, setThreadView] = useState<"dumps" | "top">("dumps");
  const [node, setNode] = useState(nodes[0] ?? "");
  const nodeInfo = props.info.nodes.find((n) => n.address === node);

  const onTabKey = (e: KeyboardEvent) => {
    if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") return;
    const i = TABS.findIndex(([k]) => k === tab);
    const next = TABS[(i + (e.key === "ArrowRight" ? 1 : TABS.length - 1)) % TABS.length][0];
    setTab(next);
    document.getElementById(`diag-tab-${id}-${next}`)?.focus();
  };

  return (
    <div className="panel diag" data-testid="diagnostics-panel">
      <div className="mon-subtabs" role="tablist" aria-label="Diagnostics views" onKeyDown={onTabKey}>
        {TABS.map(([k, label]) => (
          <button key={k} id={`diag-tab-${id}-${k}`} role="tab" aria-selected={tab === k} aria-controls={`diag-view-${id}`}
            tabIndex={tab === k ? 0 : -1} className={"tab" + (tab === k ? " active" : "")} onClick={() => setTab(k)}>
            {label}
          </button>
        ))}
      </div>
      <div className="pad diag-body" id={`diag-view-${id}`} role="tabpanel" aria-labelledby={`diag-tab-${id}-${tab}`}>
        {tab === "threads" ? (
          <div className="stack">
            <div className="row">
              <label className="row"><span className="muted">Node</span>
                <select value={node} onChange={(e) => setNode(e.target.value)} aria-label="Node">
                  {nodes.map((n) => <option key={n} value={n}>{n}</option>)}
                </select>
              </label>
              {nodeInfo && <span className="muted">{nodeInfo.datacenter} · {nodeInfo.version}</span>}
              <span className="spacer" />
              <div className="diag-seg" role="group" aria-label="Thread view">
                <button className={"btn small" + (threadView === "dumps" ? " primary" : "")} aria-pressed={threadView === "dumps"} onClick={() => setThreadView("dumps")}>Thread dumps</button>
                <button className={"btn small" + (threadView === "top" ? " primary" : "")} aria-pressed={threadView === "top"} onClick={() => setThreadView("top")}>Top threads (live)</button>
              </div>
            </div>
            {!nodes.length && <div className="notice warn">No nodes known for this cluster.</div>}
            {threadView === "dumps" ? <DumpsView client={client} node={node} /> : <TopThreadsView client={client} node={node} />}
          </div>
        ) : (
          <PartitionsView client={client} connectionId={connId} nodes={nodes} />
        )}
      </div>
    </div>
  );
}
