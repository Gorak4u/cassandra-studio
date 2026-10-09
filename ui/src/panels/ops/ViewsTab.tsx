// OPS-1: nodetool views per node as tables, with "copy as text".
import { useCallback, useEffect, useState } from "react";
import type { KeyspaceNode } from "../../lib/types";
import { errorText, useToast } from "../../components/feedback";
import { VIEWS, type OpsClient, type OpsView, type Section, type ViewName } from "./opsApi";
import { isNumeric, viewText } from "./opsText";
import { KeyspaceSelect } from "./pickers";

type Result = { node: string; view?: OpsView; error?: string };

export function ViewsTab(props: { client: OpsClient; nodes: string[]; keyspaces: KeyspaceNode[]; idPrefix: string }) {
  const toast = useToast();
  const [view, setView] = useState<ViewName>("status");
  const [keyspace, setKeyspace] = useState("");
  const [table, setTable] = useState("");
  const [key, setKey] = useState("");
  const [results, setResults] = useState<Result[]>([]);
  const [loading, setLoading] = useState(false);
  const def = VIEWS.find((v) => v.id === view)!;
  const uses = new Set([...(def.needs ?? []), ...(def.optional ?? [])]);
  const missing = (def.needs ?? []).filter((n) => (n === "keyspace" ? !keyspace : n === "table" ? !table : !key));
  // Cluster-wide views are read through one node; per-node views from every selected node.
  const targets = props.nodes.length === 0 ? [] : def.cluster ? [props.nodes[0]] : props.nodes;
  const targetKey = targets.join(",");

  const run = useCallback(() => {
    if (missing.length || targets.length === 0) return;
    setLoading(true);
    const p = { keyspace: uses.has("keyspace") ? keyspace : undefined, table: uses.has("table") ? table : undefined,
      key: uses.has("key") ? key : undefined };
    Promise.all(targets.map((node) => props.client.view(view, { ...p, node })
      .then((v): Result => ({ node, view: v }))
      .catch((e): Result => ({ node, error: errorText(e) }))))
      .then(setResults)
      .finally(() => setLoading(false));
  }, [view, keyspace, table, key, targetKey, props.client]); // eslint-disable-line react-hooks/exhaustive-deps

  // Load automatically when the view or nodes change and nothing is missing.
  useEffect(() => {
    setResults([]);
    if ((def.needs ?? []).length === 0) run();
  }, [view, targetKey]); // eslint-disable-line react-hooks/exhaustive-deps

  const ks = props.keyspaces.find((k) => k.name === keyspace);
  const copy = (v: OpsView) => {
    navigator.clipboard?.writeText(viewText(v)).then(() => toast.ok("Copied " + v.view + " of " + v.node), (e) => toast.error(e));
  };
  return (
    <div className="ops-tab" data-testid="ops-views">
      <div className="row ops-form">
        <label className="ops-field">
          <span>View</span>
          <select value={view} onChange={(e) => setView(e.target.value as ViewName)} aria-label="View">
            {VIEWS.map((v) => <option key={v.id} value={v.id}>{v.label}</option>)}
          </select>
        </label>
        {uses.has("keyspace") && (
          <KeyspaceSelect id={`${props.idPrefix}-view-ks`} keyspaces={props.keyspaces} value={keyspace}
            optional={!(def.needs ?? []).includes("keyspace")} onChange={(v) => { setKeyspace(v); setTable(""); }} />
        )}
        {uses.has("table") && (
          <label className="ops-field">
            <span>Table</span>
            <select value={table} onChange={(e) => setTable(e.target.value)} disabled={!ks}>
              <option value="">{(def.needs ?? []).includes("table") ? "Choose…" : "(all)"}</option>
              {ks?.tables.map((t) => <option key={t.name} value={t.name}>{t.name}</option>)}
            </select>
          </label>
        )}
        {uses.has("key") && (
          <label className="ops-field">
            <span>Partition key</span>
            <input value={key} onChange={(e) => setKey(e.target.value)} placeholder="e.g. 42 or a:b" />
          </label>
        )}
        <button className="btn primary" onClick={run} disabled={loading || missing.length > 0 || targets.length === 0}>
          {loading ? "Reading…" : results.length ? "Refresh" : "Show"}
        </button>
        {def.cluster && targets.length > 0 && <span className="muted">Cluster view, read through {targets[0]}</span>}
      </div>
      {targets.length === 0 && <div className="empty">Select at least one node.</div>}
      {missing.length > 0 && targets.length > 0 && <div className="muted pad">Choose {missing.join(", ")} to show this view.</div>}
      {loading && results.length === 0 && <div className="empty" role="status">Reading {def.label.toLowerCase()}…</div>}
      {results.map((r) => (
        <div key={r.node} className="ops-result" data-testid="ops-view-result">
          {r.error && <div className="notice error" role="alert"><b className="mono">{r.node}</b>: {r.error}</div>}
          {r.view && (
            <>
              <div className="row ops-result-head">
                <code className="ops-cmd">$ {r.view.command}</code>
                <span className="spacer" />
                <button className="btn small" onClick={() => copy(r.view!)} aria-label={`Copy ${r.view.view} of ${r.node} as text`}>
                  Copy as text
                </button>
              </div>
              {r.view.sections.map((s, i) => <SectionTable key={i} s={s} />)}
              {r.view.notes.map((n, i) => <div key={i} className="muted ops-note">{n}</div>)}
            </>
          )}
        </div>
      ))}
    </div>
  );
}

function SectionTable(props: { s: Section }) {
  const { s } = props;
  return (
    <div className="ops-section">
      <h4>{s.title}</h4>
      <div className="ops-table-wrap" tabIndex={0} role="region" aria-label={s.title}>
        <table className={"data ops-table" + (s.keyValue ? " kv" : "")}>
          {!s.keyValue && (
            <thead><tr>{s.columns.map((c, i) => <th key={i} scope="col">{c}</th>)}</tr></thead>
          )}
          <tbody>
            {s.rows.length === 0 && <tr><td colSpan={s.columns.length} className="muted">None</td></tr>}
            {s.rows.map((r, i) => (
              <tr key={i}>
                {r.map((c, j) => s.keyValue && j === 0
                  ? <th key={j} scope="row">{c}</th>
                  : <td key={j} className={isNumeric(c) ? "num" : undefined}>{c ?? <span className="muted">n/a</span>}</td>)}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
