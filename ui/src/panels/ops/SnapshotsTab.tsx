// OPS-4: snapshots per node: list with sizes, create, clear by tag.
import { useCallback, useEffect, useMemo, useState } from "react";
import type { KeyspaceNode } from "../../lib/types";
import { jobDone, type Job } from "../../lib/jobsTypes";
import { errorText, useGuarded, useToast } from "../../components/feedback";
import { JobProgress } from "../../components/JobProgress";
import type { OpsClient, SnapshotList } from "./opsApi";

function defaultTag(now = new Date()): string {
  const p = (n: number) => String(n).padStart(2, "0");
  return `studio-${now.getFullYear()}${p(now.getMonth() + 1)}${p(now.getDate())}-${p(now.getHours())}${p(now.getMinutes())}${p(now.getSeconds())}`;
}

export function formatBytes(b: number | null): string {
  if (b === null) return "n/a";
  const u = ["bytes", "KiB", "MiB", "GiB", "TiB"];
  let v = b, i = 0;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return i === 0 ? `${b} bytes` : `${v.toFixed(2)} ${u[i]}`;
}

export function SnapshotsTab(props: { client: OpsClient; nodes: string[]; keyspaces: KeyspaceNode[]; onStarted?: (j: Job) => void }) {
  const guarded = useGuarded();
  const toast = useToast();
  const [list, setList] = useState<SnapshotList | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [tag, setTag] = useState(defaultTag);
  const [selKs, setSelKs] = useState<string[]>([]);
  const [skipFlush, setSkipFlush] = useState(false);
  const [jobId, setJobId] = useState<string | null>(null);
  const [filter, setFilter] = useState("");

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    props.client.snapshots().then(setList).catch((e) => setError(errorText(e))).finally(() => setLoading(false));
  }, [props.client]);
  useEffect(load, [load]);

  const nodes = new Set(props.nodes);
  const rows = useMemo(() => (list?.snapshots ?? [])
    .filter((s) => nodes.size === 0 || nodes.has(s.node))
    .filter((s) => !filter || s.tag.includes(filter) || s.keyspace.includes(filter)), [list, props.nodes, filter]); // eslint-disable-line react-hooks/exhaustive-deps
  const byTag = useMemo(() => {
    const m = new Map<string, { nodes: Set<string>; tables: number; trueBytes: number; diskBytes: number }>();
    for (const s of rows) {
      const g = m.get(s.tag) ?? { nodes: new Set<string>(), tables: 0, trueBytes: 0, diskBytes: 0 };
      g.nodes.add(s.node); g.tables++; g.trueBytes += s.trueSizeBytes ?? 0; g.diskBytes += s.sizeOnDiskBytes ?? 0;
      m.set(s.tag, g);
    }
    return [...m.entries()].sort(([a], [b]) => a.localeCompare(b));
  }, [rows]);

  const started = (j: Job | null) => {
    if (!j) return;
    setJobId(j.id);
    props.onStarted?.(j);
  };
  const create = () => guarded((c) => props.client.takeSnapshot({ nodes: props.nodes, tag: tag.trim(), keyspaces: selKs, skipFlush }, c))
    .then(started).catch((e) => toast.error(e));
  const clear = (t: string, onNodes: string[]) =>
    guarded((c) => props.client.clearSnapshot({ nodes: onNodes, tag: t }, c)).then(started).catch((e) => toast.error(e));
  const tagOk = /^[A-Za-z0-9_.-]{1,128}$/.test(tag.trim());

  return (
    <div className="ops-tab" data-testid="ops-snapshots">
      <section className="panel">
        <h3>Take a snapshot</h3>
        <div className="row ops-form">
          <label className="ops-field"><span>Tag</span><input value={tag} onChange={(e) => setTag(e.target.value)} aria-invalid={!tagOk} /></label>
          <label className="ops-check"><input type="checkbox" checked={skipFlush} onChange={(e) => setSkipFlush(e.target.checked)} /> Skip flush (-sf)</label>
        </div>
        <fieldset className="ops-tables">
          <legend>Keyspaces <span className="muted">(none = all keyspaces)</span></legend>
          {props.keyspaces.filter((k) => !k.system).map((k) => (
            <label key={k.name} className="ops-check">
              <input type="checkbox" checked={selKs.includes(k.name)}
                onChange={(e) => setSelKs(e.target.checked ? [...selKs, k.name] : selKs.filter((x) => x !== k.name))} /> {k.name}
            </label>
          ))}
        </fieldset>
        <div className="row">
          <button className="btn primary" onClick={create} disabled={!tagOk || props.nodes.length === 0}>
            Snapshot on {props.nodes.length} node{props.nodes.length === 1 ? "" : "s"}…
          </button>
          {!tagOk && <span className="error-text">Tag: letters, digits, _ . - only.</span>}
        </div>
      </section>
      {jobId && <div className="ops-job"><JobProgress jobId={jobId} onDone={(j) => { if (jobDone(j)) load(); }} /></div>}
      <section className="panel">
        <div className="row">
          <h3 style={{ margin: 0 }}>Snapshots {props.nodes.length ? "on the selected nodes" : "on all nodes"}</h3>
          <span className="spacer" />
          <label className="ops-field"><span className="ops-sr-only">Filter snapshots</span>
            <input placeholder="Filter by tag or keyspace" value={filter} onChange={(e) => setFilter(e.target.value)} aria-label="Filter snapshots" />
          </label>
          <button className="btn small" onClick={load} disabled={loading}>{loading ? "Loading…" : "Refresh"}</button>
        </div>
        {error && <div className="notice error" role="alert">{error}</div>}
        {list?.errors.map((e) => <div key={e.node} className="notice warn"><b className="mono">{e.node}</b>: {e.error}</div>)}
        {list && byTag.length === 0 && <div className="empty">No snapshots.</div>}
        {byTag.length > 0 && (
          <div className="ops-table-wrap" tabIndex={0} role="region" aria-label="Snapshots by tag">
            <table className="data ops-table" data-testid="ops-snapshot-tags">
              <thead><tr><th scope="col">Tag</th><th scope="col">Nodes</th><th scope="col">Tables</th><th scope="col" className="num">True size</th><th scope="col" className="num">Size on disk</th><th scope="col"><span className="ops-sr-only">Actions</span></th></tr></thead>
              <tbody>
                {byTag.map(([t, g]) => (
                  <tr key={t}>
                    <td className="mono">{t}</td>
                    <td>{[...g.nodes].join(", ")}</td>
                    <td>{g.tables}</td>
                    <td className="num">{formatBytes(g.trueBytes)}</td>
                    <td className="num">{formatBytes(g.diskBytes)}</td>
                    <td><button className="btn small danger" onClick={() => clear(t, [...g.nodes])} aria-label={`Clear snapshot ${t}`}>Clear…</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {rows.length > 0 && (
          <details className="ops-details">
            <summary>Per table ({rows.length})</summary>
            <div className="ops-table-wrap" tabIndex={0} role="region" aria-label="Snapshots per table">
              <table className="data ops-table">
                <thead><tr><th scope="col">Node</th><th scope="col">Tag</th><th scope="col">Keyspace</th><th scope="col">Table</th><th scope="col" className="num">True size</th><th scope="col" className="num">Size on disk</th><th scope="col">Created</th><th scope="col">Expires</th></tr></thead>
                <tbody>
                  {rows.map((s, i) => (
                    <tr key={i}>
                      <td className="mono">{s.node}</td><td className="mono">{s.tag}</td><td>{s.keyspace}</td><td>{s.table}</td>
                      <td className="num">{s.trueSize ?? "n/a"}</td><td className="num">{s.sizeOnDisk ?? "n/a"}</td>
                      <td>{s.createdAt ?? <span className="muted">n/a</span>}</td><td>{s.expiresAt ?? <span className="muted">never</span>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </details>
        )}
      </section>
    </div>
  );
}
