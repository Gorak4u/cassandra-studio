import { useCallback, useEffect, useMemo, useState, type KeyboardEvent } from "react";
import type { ClusterInfo, ConnectionConfig } from "../../lib/types";
import { jobsApi } from "../../lib/jobsApi";
import { jobDone, type Job } from "../../lib/jobsTypes";
import { errorText } from "../../components/feedback";
import { JobProgress } from "../../components/JobProgress";
import { HelpLink } from "../../components/HelpLink";
import { MaintenanceTab, RepairTab } from "./ActionsTab";
import { OPS_JOB_KINDS, opsClient } from "./opsApi";
import { NodePicker, useKeyspaces } from "./pickers";
import { SnapshotsTab } from "./SnapshotsTab";
import { ViewsTab } from "./ViewsTab";
import "./ops.css";

type Tab = "views" | "maintenance" | "repair" | "snapshots" | "jobs";
const TABS: [Tab, string][] = [
  ["views", "Views"], ["maintenance", "Maintenance"], ["repair", "Repair"], ["snapshots", "Snapshots"], ["jobs", "Jobs"],
];

/** Phase 3 Track 1: nodetool views, maintenance, repair and snapshots for one cluster (OPS-1..4). */
export function OperationsPanel(props: { conn: ConnectionConfig; info: ClusterInfo; dark: boolean }) {
  const id = props.conn.id!;
  const client = useMemo(() => opsClient(id), [id]);
  const { keyspaces, error: ksError } = useKeyspaces(id);
  const nodes = props.info.nodes;
  // Default: the first node that is up, so views show something at once.
  const [selected, setSelected] = useState<string[]>(() => {
    const up = nodes.find((n) => n.state === "UP") ?? nodes[0];
    return up ? [up.address] : [];
  });
  const [tab, setTab] = useState<Tab>("views");
  const [jobsTick, setJobsTick] = useState(0);
  const onStarted = useCallback(() => setJobsTick((t) => t + 1), []);
  const prefix = `ops-${id}`;
  const jmxMethod = props.conn.jmx?.method;
  const noJmx = jmxMethod === "EXPORTER" || jmxMethod === "SIDECAR" || jmxMethod === "NONE";

  const onTabKey = (e: KeyboardEvent) => {
    const i = TABS.findIndex(([k]) => k === tab);
    const next = e.key === "ArrowRight" ? i + 1 : e.key === "ArrowLeft" ? i - 1 : e.key === "Home" ? 0 : e.key === "End" ? TABS.length - 1 : null;
    if (next === null) return;
    e.preventDefault();
    const k = TABS[(next + TABS.length) % TABS.length][0];
    setTab(k);
    document.getElementById(`${prefix}-tab-${k}`)?.focus();
  };

  let body;
  if (tab === "views") body = <ViewsTab client={client} nodes={selected} keyspaces={keyspaces} idPrefix={prefix} />;
  else if (tab === "maintenance") body = <MaintenanceTab client={client} nodes={selected} keyspaces={keyspaces} idPrefix={prefix} onStarted={onStarted} />;
  else if (tab === "repair") body = <RepairTab client={client} nodes={selected} keyspaces={keyspaces} datacenters={props.info.datacenters} idPrefix={prefix} onStarted={onStarted} />;
  else if (tab === "snapshots") body = <SnapshotsTab client={client} nodes={selected} keyspaces={keyspaces} onStarted={onStarted} />;
  else body = <JobsList connectionId={id} tick={jobsTick} />;

  return (
    <div className="ops" data-testid="operations-panel">
      <div className="ops-bar-top">
        <div className="ops-subtabs" role="tablist" aria-label="Operations" onKeyDown={onTabKey}>
          {TABS.map(([k, label]) => (
            <button key={k} id={`${prefix}-tab-${k}`} role="tab" aria-selected={tab === k} aria-controls={`${prefix}-view`}
              tabIndex={tab === k ? 0 : -1} className={"ops-subtab" + (tab === k ? " active" : "")} onClick={() => setTab(k)}>
              {label}
            </button>
          ))}
        </div>
        <span className="spacer" />
        <span className="muted">{selected.length} of {nodes.length} node{nodes.length === 1 ? "" : "s"} selected</span>
        <HelpLink topic={`ops-${tab}` as const} />
      </div>
      {noJmx && (
        <div className="notice warn ops-banner" role="alert">
          Operations need JMX; this connection uses {jmxMethod}. Set JMX to SSH tunnel or direct (Edit connection → JMX).
        </div>
      )}
      {ksError && <div className="notice warn ops-banner">Keyspaces could not be loaded: {ksError}</div>}
      <div className="ops-main">
        <NodePicker nodes={nodes} selected={selected} onChange={setSelected} idPrefix={prefix} />
        <div className="ops-body" id={`${prefix}-view`} role="tabpanel" aria-labelledby={`${prefix}-tab-${tab}`}>
          {body}
        </div>
      </div>
    </div>
  );
}

/** Recent operations jobs of this connection, newest first; refreshes while any is running. */
function JobsList(props: { connectionId: string; tick: number }) {
  const [jobs, setJobs] = useState<Job[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    let stop = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const load = () => jobsApi.list(props.connectionId).then((all) => {
      if (stop) return;
      const mine = all.filter((j) => OPS_JOB_KINDS.has(j.kind));
      setJobs(mine);
      setError(null);
      if (mine.some((j) => !jobDone(j))) timer = setTimeout(load, 3000);
    }).catch((e) => { if (!stop) setError(errorText(e)); });
    load();
    return () => { stop = true; if (timer) clearTimeout(timer); };
  }, [props.connectionId, props.tick, refresh]);
  return (
    <div className="ops-tab" data-testid="ops-jobs">
      <div className="row">
        <span className="muted">Operations started in this engine session for this cluster (kept in memory).</span>
        <span className="spacer" />
        <button className="btn small" onClick={() => setRefresh((r) => r + 1)}>Refresh</button>
      </div>
      {error && <div className="notice error" role="alert">{error}</div>}
      {jobs && jobs.length === 0 && <div className="empty">No operations yet.</div>}
      {jobs && jobs.length > 0 && (
        <div className="ops-table-wrap" tabIndex={0} role="region" aria-label="Recent operations">
          <table className="data ops-table">
            <thead><tr><th scope="col">Started</th><th scope="col">Operation</th><th scope="col">Nodes</th><th scope="col">State</th><th scope="col" className="num">Progress</th><th scope="col"><span className="ops-sr-only">Details</span></th></tr></thead>
            <tbody>
              {jobs.map((j) => (
                <tr key={j.id}>
                  <td>{new Date(j.createdAtMs).toLocaleString()}</td>
                  <td>{j.title}</td>
                  <td className="mono">{j.node}</td>
                  <td><span className={"status " + (j.state === "SUCCEEDED" ? "ok" : j.state === "FAILED" ? "error" : j.state === "CANCELLED" ? "skipped" : "")}>{j.state}</span></td>
                  <td className="num">{j.progress === null ? "" : Math.round(j.progress * 100) + " %"}</td>
                  <td><button className="btn small" aria-expanded={open === j.id} onClick={() => setOpen(open === j.id ? null : j.id)}>
                    {open === j.id ? "Hide" : "Details"}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {open && <div className="ops-job"><JobProgress jobId={open} /></div>}
    </div>
  );
}
