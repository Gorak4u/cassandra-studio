import { useCallback, useEffect, useState } from "react";
import { api } from "../lib/api";
import type { AuditEntry, ConnectionConfig, HistoryEntry } from "../lib/types";
import { useToast } from "../components/feedback";
import { download } from "../lib/export";
import { HelpLink } from "../components/HelpLink";
import { studioApi } from "../lib/studioApi";

/** Searchable query history per connection (CQL-8). */
export function HistoryPanel(props: { conn: ConnectionConfig; onOpenInEditor: (text: string) => void }) {
  const toast = useToast();
  const [q, setQ] = useState("");
  const [items, setItems] = useState<HistoryEntry[]>([]);
  const load = useCallback(() => { api.history(props.conn.id!, q).then(setItems).catch(toast.error); }, [props.conn.id, q, toast]);
  useEffect(load, [load]);
  return (
    <div className="pad stack scroll" style={{ height: "100%" }}>
      <div className="row">
        <input placeholder="Search statements…" value={q} onChange={(e) => setQ(e.target.value)} style={{ flex: 1 }} />
        <button className="btn small" onClick={load}>⟳</button>
        <button className="btn small" onClick={() => api.clearHistory(props.conn.id!).then(load).catch(toast.error)}>Clear</button>
        <HelpLink topic="history" />
      </div>
      <table className="data">
        <thead><tr><th>When</th><th>Statement</th><th>Keyspace</th><th>Node</th><th>Rows</th><th>ms</th><th /></tr></thead>
        <tbody>
          {items.map((h) => (
            <tr key={h.id}>
              <td className="muted" style={{ whiteSpace: "nowrap" }}>{new Date(h.executedAt).toLocaleString()}</td>
              <td><pre className={h.error ? "error-text" : ""}>{h.statement}</pre>{h.error && <div className="error-text">{h.error}</div>}</td>
              <td>{h.keyspace}</td><td className="mono">{h.node}</td><td>{h.rowCount}</td><td>{h.durationMs}</td>
              <td><button className="btn link small" onClick={() => props.onOpenInEditor(h.statement + ";")}>Open</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      {items.length === 0 && <div className="empty">No history yet.</div>}
    </div>
  );
}

/** Audit log of every change and every blocked attempt, across connections (NFR-AUD). */
export function AuditPanel() {
  const toast = useToast();
  const [q, setQ] = useState("");
  const [items, setItems] = useState<AuditEntry[]>([]);
  const load = useCallback(() => { api.audit({ q }).then(setItems).catch(toast.error); }, [q, toast]);
  useEffect(load, [load]);
  return (
    <div className="pad stack scroll" style={{ height: "100%" }}>
      <div className="row">
        <h3 style={{ margin: 0 }}>Audit log</h3>
        <input placeholder="Search…" aria-label="Search the audit log" value={q} onChange={(e) => setQ(e.target.value)} style={{ flex: 1 }} />
        <button className="btn small" onClick={load}>⟳</button>
        {(["csv", "json"] as const).map((fmt) => (
          <button
            key={fmt}
            className="btn small"
            title={`Every entry matching the search (up to 100,000), not only the ${items.length} shown`}
            onClick={() => studioApi.auditExport(fmt, q)
              .then((text) => download(`studio-audit-${new Date().toISOString().slice(0, 10)}.${fmt}`, text, fmt === "csv" ? "text/csv" : "application/json"))
              .catch(toast.error)}
          >
            Export {fmt.toUpperCase()}
          </button>
        ))}
        <HelpLink topic="audit" />
      </div>
      <table className="data">
        <thead><tr><th>When</th><th>Who</th><th>Connection</th><th>Action</th><th>Detail</th><th>Outcome</th></tr></thead>
        <tbody>
          {items.map((a) => (
            <tr key={a.id}>
              <td className="muted" style={{ whiteSpace: "nowrap" }}>{new Date(a.at).toLocaleString()}</td>
              <td>{a.actor}</td>
              <td>{a.environment && <span className={"env " + a.environment}>{a.environment}</span>} {a.connectionName}</td>
              <td>{a.category} · {a.action}</td>
              <td><pre>{a.detail}</pre></td>
              <td><span className={"status " + a.outcome}>{a.outcome}</span>{a.error && <div className="muted">{a.error}</div>}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {items.length === 0 && <div className="empty">Nothing audited yet. Writes, DDL, role changes and operations are recorded here.</div>}
    </div>
  );
}
