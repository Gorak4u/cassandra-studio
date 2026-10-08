import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api } from "../lib/api";
import { download, toCsv, toJson } from "../lib/export";
import type { Cell, ClusterInfo, ConnectionConfig, QueryRequest, StatementResult, TableDetails } from "../lib/types";
import type { Schema } from "../lib/cqlText";
import { CqlEditor, type CqlEditorHandle } from "../components/CqlEditor";
import { ResultGrid, gridValue, toGridRows, type GridRow } from "../components/ResultGrid";
import { CONSISTENCY_LEVELS } from "../components/ConnectionDialog";
import { errorText, useGuarded, useToast } from "../components/feedback";
import { SaveScriptDialog, ScriptLibrary } from "../components/ScriptLibrary";

interface EditorTab {
  id: number;
  title: string;
  scriptId?: string;
  folder?: string;
  text: string;
  results: StatementResult[];
  active: number;
  running: boolean;
}

let nextTab = 1;
const newTab = (text = ""): EditorTab => ({
  id: nextTab++, title: `Query ${nextTab - 1}`, text, results: [], active: 0, running: false,
});

/** Multi-tab CQL editor with results (CQL-1 ... CQL-10). */
export function QueryPanel(props: {
  conn: ConnectionConfig;
  info: ClusterInfo | null;
  dark: boolean;
  openText?: { text: string; seq: number } | null;
}) {
  const toast = useToast();
  const guarded = useGuarded();
  const [tabs, setTabs] = useState<EditorTab[]>(() => [newTab("SELECT cluster_name, release_version FROM system.local;\n")]);
  const [activeTab, setActiveTab] = useState(tabs[0].id);
  const [keyspace, setKeyspace] = useState<string | null>(null);
  const [node, setNode] = useState<string>("");
  const [consistency, setConsistency] = useState(props.conn.defaultConsistency);
  const [pageSize, setPageSize] = useState(props.conn.pageSize);
  const [tracing, setTracing] = useState(false);
  const [stopOnError, setStopOnError] = useState(true);
  const [schema, setSchema] = useState<Schema>({});
  const editor = useRef<CqlEditorHandle | null>(null);
  const fileInput = useRef<HTMLInputElement | null>(null);
  const [library, setLibrary] = useState(false);
  const [saving, setSaving] = useState(false);

  const tab = tabs.find((t) => t.id === activeTab) ?? tabs[0];
  const update = useCallback((id: number, patch: Partial<EditorTab>) =>
    setTabs((ts) => ts.map((t) => (t.id === id ? { ...t, ...patch } : t))), []);

  const loadSchema = useCallback(() => {
    api.completions(props.conn.id!).then(setSchema).catch(() => setSchema({}));
  }, [props.conn.id]);
  useEffect(loadSchema, [loadSchema]);

  // "Open in editor" from history or the schema browser.
  useEffect(() => {
    if (!props.openText) return;
    const t = newTab(props.openText.text);
    setTabs((ts) => [...ts, t]);
    setActiveTab(t.id);
  }, [props.openText]);

  const request = (cql: string, extra: Partial<QueryRequest> = {}): QueryRequest => ({
    cql,
    keyspace,
    node: node || null,
    consistency,
    pageSize,
    tracing,
    stopOnError,
    ...extra,
  });

  const run = async (cql: string) => {
    if (!cql.trim()) return;
    const id = tab.id;
    update(id, { running: true });
    try {
      const res = await guarded((c) => api.query(props.conn.id!, request(cql, c)));
      if (!res) return;
      update(id, { results: res.results, active: Math.max(0, res.results.findIndex((r) => r.status === "error")) });
      if (res.keyspace !== undefined) setKeyspace(res.keyspace ?? null);
      if (res.consistency) setConsistency(res.consistency);
      setTracing(res.tracing);
      if (res.results.some((r) => r.kind === "DDL" && r.status === "ok")) loadSchema();
    } catch (e) {
      toast.error(e);
    } finally {
      update(id, { running: false });
    }
  };

  const saveFile = () => download(`${tab.title.replace(/\W+/g, "_")}.cql`, tab.text, "text/plain");
  const openFile = async (f: File) => {
    const t = { ...newTab(await f.text()), title: f.name };
    setTabs((ts) => [...ts, t]);
    setActiveTab(t.id);
  };

  const keyspaces = Object.keys(schema).sort();
  const result = tab.results[tab.active];

  return (
    <div className="query">
      <div>
        <div className="tabs">
          {tabs.map((t) => (
            <button key={t.id} className={"tab" + (t.id === tab.id ? " active" : "")} onClick={() => setActiveTab(t.id)}>
              {t.running ? "⏳ " : ""}
              {t.title}
              {tabs.length > 1 && (
                <span className="close" role="button" aria-label="Close tab"
                  onClick={(e) => {
                    e.stopPropagation();
                    setTabs((ts) => ts.filter((x) => x.id !== t.id));
                    if (t.id === tab.id) setActiveTab(tabs.find((x) => x.id !== t.id)!.id);
                  }}>
                  ×
                </span>
              )}
            </button>
          ))}
          <button className="tab" title="New query tab" onClick={() => {
            const t = newTab();
            setTabs((ts) => [...ts, t]);
            setActiveTab(t.id);
          }}>+</button>
        </div>
        <div className="toolbar">
          <button className="btn primary" disabled={tab.running} onClick={() => run(editor.current?.current() ?? "")}
            title="Run the selection, or the statement under the cursor (Ctrl/Cmd+Enter)" data-testid="run">
            ▶ Run
          </button>
          <button className="btn" disabled={tab.running} onClick={() => run(editor.current?.all() ?? "")}
            title="Run the whole script (Ctrl/Cmd+Shift+Enter)">
            ▶▶ Run script
          </button>
          <label className="row" style={{ gap: 4 }}>
            Keyspace
            <select value={keyspace ?? ""} onChange={(e) => setKeyspace(e.target.value || null)} aria-label="Keyspace">
              <option value="">(none)</option>
              {keyspaces.map((k) => <option key={k}>{k}</option>)}
            </select>
          </label>
          <label className="row" style={{ gap: 4 }} title="Coordinator node (CQL-3)">
            Node
            <select value={node} onChange={(e) => setNode(e.target.value)} aria-label="Coordinator node">
              <option value="">Auto (load balanced)</option>
              {props.info?.nodes.map((n) => (
                <option key={n.address} value={n.address} disabled={n.state !== "UP"}>
                  {n.address} · {n.datacenter}/{n.rack} {n.state !== "UP" ? `(${n.state})` : ""}
                </option>
              ))}
            </select>
          </label>
          <label className="row" style={{ gap: 4 }}>
            CL
            <select value={consistency} onChange={(e) => setConsistency(e.target.value)} aria-label="Consistency level">
              {CONSISTENCY_LEVELS.map((l) => <option key={l}>{l}</option>)}
            </select>
          </label>
          <label className="row" style={{ gap: 4 }}>
            Page
            <input type="number" min={1} max={10000} style={{ width: 70 }} value={pageSize}
              onChange={(e) => setPageSize(Number(e.target.value))} aria-label="Page size" />
          </label>
          <label className="check"><input type="checkbox" checked={tracing} onChange={(e) => setTracing(e.target.checked)} /> Tracing</label>
          <label className="check" title="Script mode: stop at the first failing statement">
            <input type="checkbox" checked={stopOnError} onChange={(e) => setStopOnError(e.target.checked)} /> Stop on error
          </label>
          <span className="spacer" />
          <button className="btn small" onClick={() => setLibrary(true)}>Scripts…</button>
          <button className="btn small" onClick={() => setSaving(true)}>Save to library</button>
          <button className="btn small" onClick={() => fileInput.current?.click()}>Open .cql</button>
          <button className="btn small" onClick={saveFile}>Save .cql</button>
          <input ref={fileInput} type="file" accept=".cql,.txt,.sql" hidden
            onChange={(e) => e.target.files?.[0] && openFile(e.target.files[0])} />
        </div>
      </div>
      {library && (
        <ScriptLibrary onClose={() => setLibrary(false)} onOpen={(sc) => {
          const t = { ...newTab(sc.content ?? ""), title: sc.name, scriptId: sc.id, folder: sc.folder };
          setTabs((ts) => [...ts, t]);
          setActiveTab(t.id);
          setLibrary(false);
        }} />
      )}
      {saving && (
        <SaveScriptDialog
          initial={{ name: tab.scriptId ? tab.title : "", folder: tab.folder ?? "" }}
          onClose={() => setSaving(false)}
          onSave={async (name, folder) => {
            try {
              const saved = await api.saveScript({ id: tab.scriptId, name, folder, content: tab.text });
              update(tab.id, { scriptId: saved.id, title: saved.name, folder: saved.folder });
              toast.ok(`Saved "${saved.name}"`);
              setSaving(false);
            } catch (e) {
              toast.error(e);
            }
          }}
        />
      )}
      <div className="editor" key={tab.id}>
        <CqlEditor
          value={tab.text}
          onChange={(v) => update(tab.id, { text: v })}
          schema={schema}
          keyspace={keyspace}
          dark={props.dark}
          onRunCurrent={() => run(editor.current?.current() ?? "")}
          onRunAll={() => run(editor.current?.all() ?? "")}
          handleRef={editor}
        />
      </div>
      <div className="divider" />
      <div className="results">
        {tab.results.length > 1 && (
          <div className="tabs">
            {tab.results.map((r, i) => (
              <button key={i} className={"tab" + (i === tab.active ? " active" : "")} onClick={() => update(tab.id, { active: i })}
                title={r.statement}>
                <span className={"status " + r.status}>{r.status}</span> {i + 1}. {r.statement.slice(0, 30)}
              </button>
            ))}
          </div>
        )}
        {result ? (
          <ResultView
            key={`${tab.id}-${tab.active}-${result.statement}-${result.durationMs}`}
            conn={props.conn}
            result={result}
            request={request}
            dark={props.dark}
            onRerun={() => run(result.statement)}
          />
        ) : (
          <div className="empty">Run a statement with Ctrl/Cmd+Enter. Results, warnings and traces appear here.</div>
        )}
      </div>
    </div>
  );
}

type View = "grid" | "messages" | "trace";

function ResultView(props: {
  conn: ConnectionConfig;
  result: StatementResult;
  request: (cql: string, extra?: Partial<QueryRequest>) => QueryRequest;
  dark: boolean;
  onRerun: () => void;
}) {
  const toast = useToast();
  const guarded = useGuarded();
  const r = props.result;
  const [rows, setRows] = useState<GridRow[]>(() => toGridRows(r.rows));
  const [pagingState, setPagingState] = useState(r.pagingState);
  const [hasMore, setHasMore] = useState(r.hasMore);
  const [view, setView] = useState<View>(r.status === "error" ? "messages" : r.columns.length ? "grid" : "messages");
  const [editing, setEditing] = useState(false);
  const [table, setTable] = useState<TableDetails | null>(null);
  const [changes, setChanges] = useState<Map<number, Record<string, Cell>>>(new Map());
  const [selected, setSelected] = useState<GridRow[]>([]);
  const [loading, setLoading] = useState(false);

  // A result is editable when every column comes from one non-system table and the connection allows writes.
  const source = useMemo(() => {
    const ks = r.columns[0]?.keyspace;
    const t = r.columns[0]?.table;
    if (!ks || !t || r.columns.some((c) => c.keyspace !== ks || c.table !== t)) return null;
    if (ks.startsWith("system")) return null;
    return { ks, t };
  }, [r.columns]);
  const canEdit = !!source && !props.conn.readOnly && r.kind === "READ";

  const startEditing = async () => {
    if (!source) return;
    try {
      const t = await api.table(props.conn.id!, source.ks, source.t);
      const pk = [...t.partitionKey, ...t.clusteringColumns];
      const missing = pk.filter((k) => !r.columns.some((c) => c.name === k));
      if (missing.length) {
        toast.error(`To edit rows, select the full primary key too (missing: ${missing.join(", ")}).`);
        return;
      }
      setTable(t);
      setEditing(true);
    } catch (e) {
      toast.error(e);
    }
  };

  const editable = useMemo(() => {
    if (!editing || !table) return undefined;
    const pk = new Set([...table.partitionKey, ...table.clusteringColumns]);
    return new Set(r.columns.map((c) => c.name).filter((n) => !pk.has(n)));
  }, [editing, table, r.columns]);

  const loadMore = async (all: boolean) => {
    setLoading(true);
    try {
      const res = await api.query(props.conn.id!, props.request(r.statement, {
        pagingState, maxRows: all ? 10000 : null, tracing: false,
      }));
      const s = res.results[0];
      if (s.status === "error") throw new Error(s.error);
      setRows((old) => [...old, ...toGridRows(s.rows, old.length)]);
      setPagingState(s.pagingState);
      setHasMore(s.hasMore);
    } catch (e) {
      toast.error(e);
    } finally {
      setLoading(false);
    }
  };

  const keyOf = (row: GridRow) =>
    Object.fromEntries([...table!.partitionKey, ...table!.clusteringColumns].map((k) => [k, String(gridValue(row, r.columns, k))]));

  const applyChanges = async (deletes: GridRow[] = []) => {
    if (!table || !source) return;
    try {
      const statements: string[] = [];
      for (const row of rows) {
        const ch = changes.get(row.__id);
        if (row.__new) {
          const all = Object.fromEntries(r.columns.map((c) => [c.name, gridValue(row, r.columns, c.name)]));
          const key = Object.fromEntries([...table.partitionKey, ...table.clusteringColumns].map((k) => [k, all[k] === null ? null : String(all[k])]));
          if (Object.values(key).some((v) => v === null)) throw new Error("New rows need every primary key column filled in");
          const values = Object.fromEntries(Object.entries(all).filter(([k, v]) => !(k in key) && v !== null).map(([k, v]) => [k, String(v)]));
          statements.push((await api.rowCql(props.conn.id!, { keyspace: source.ks, table: source.t, op: "INSERT", key: key as Record<string, string>, values })).cql);
        } else if (ch) {
          const values = Object.fromEntries(Object.entries(ch).map(([k, v]) => [k, v === null ? null : String(v)]));
          statements.push((await api.rowCql(props.conn.id!, { keyspace: source.ks, table: source.t, op: "UPDATE", key: keyOf(row), values })).cql);
        }
      }
      for (const row of deletes) {
        statements.push((await api.rowCql(props.conn.id!, { keyspace: source.ks, table: source.t, op: "DELETE", key: keyOf(row) })).cql);
      }
      if (!statements.length) return;
      const res = await guarded((c) => api.query(props.conn.id!, { cql: statements.join("\n"), confirmed: c.confirmed, confirmName: c.confirmName, stopOnError: true }));
      if (!res) return;
      const failed = res.results.find((x) => x.status === "error");
      if (failed) throw new Error(failed.error);
      toast.ok(`Applied ${statements.length} change(s)`);
      setChanges(new Map());
      props.onRerun();
    } catch (e) {
      toast.error(errorText(e));
    }
  };

  const pending = changes.size + rows.filter((x) => x.__new).length;

  return (
    <>
      <div className="tabs">
        {r.columns.length > 0 && <button className={"tab" + (view === "grid" ? " active" : "")} onClick={() => setView("grid")}>Result ({rows.length}{hasMore ? "+" : ""})</button>}
        <button className={"tab" + (view === "messages" ? " active" : "")} onClick={() => setView("messages")}>
          Messages {r.serverWarnings.length + r.clientWarnings.length > 0 && <span className="status UNKNOWN">{r.serverWarnings.length + r.clientWarnings.length}</span>}
        </button>
        {r.trace && <button className={"tab" + (view === "trace" ? " active" : "")} onClick={() => setView("trace")}>Trace</button>}
        <span className="spacer" />
        {view === "grid" && r.columns.length > 0 && (
          <div className="row" style={{ padding: "0 6px" }}>
            {canEdit && !editing && <button className="btn small" onClick={startEditing}>✎ Edit data</button>}
            {editing && (
              <>
                <button className="btn small" onClick={() => setRows((x) => [...x, { __id: Date.now(), __new: true }])}>+ Row</button>
                <button className="btn small" disabled={!selected.length} onClick={() => applyChanges(selected.filter((s) => !s.__new))}>Delete selected</button>
                <button className="btn small primary" disabled={!pending} onClick={() => applyChanges()}>Apply {pending} change(s)</button>
                <button className="btn small" onClick={() => { setEditing(false); setChanges(new Map()); setRows(toGridRows(r.rows)); }}>Cancel</button>
              </>
            )}
            <button className="btn small" onClick={() => download("result.csv", toCsv(r.columns, rows.filter((x) => !x.__new).map((x) => r.columns.map((_, i) => (x["c" + i] as Cell) ?? null))), "text/csv")}>CSV</button>
            <button className="btn small" onClick={() => download("result.json", toJson(r.columns, rows.filter((x) => !x.__new).map((x) => r.columns.map((_, i) => (x["c" + i] as Cell) ?? null))), "application/json")}>JSON</button>
          </div>
        )}
      </div>
      <div className="body">
        {view === "grid" && r.columns.length > 0 && (
          <ResultGrid
            columns={r.columns}
            rows={rows}
            dark={props.dark}
            editableColumns={editable}
            selectable={editing}
            onSelectionChanged={setSelected}
            onCellChanged={(row, col, _old, val) => {
              if (row.__new) return;
              setChanges((m) => new Map(m).set(row.__id, { ...(m.get(row.__id) ?? {}), [col]: val }));
            }}
          />
        )}
        {view === "messages" && (
          <div className="pad stack" style={{ gap: 6 }}>
            {r.status === "error" && <div className="notice error" data-testid="query-error">{r.error}</div>}
            {r.message && <div className="notice info">{r.message}</div>}
            {r.clientWarnings.map((w, i) => <div key={"c" + i} className="notice warn">⚠ {w}</div>)}
            {r.serverWarnings.map((w, i) => <div key={"s" + i} className="notice warn">Server warning: {w}</div>)}
            <pre className="ddl">{r.statement}</pre>
          </div>
        )}
        {view === "trace" && r.trace && (
          <div className="pad">
            <p className="muted">
              Trace {r.trace.traceId} · coordinator {r.trace.coordinator} · {r.trace.durationMicros >= 0 ? `${(r.trace.durationMicros / 1000).toFixed(2)} ms` : "duration unknown"}
            </p>
            <table className="data">
              <thead><tr><th>Elapsed (µs)</th><th>Source</th><th>Thread</th><th>Activity</th></tr></thead>
              <tbody>
                {r.trace.events.map((e, i) => (
                  <tr key={i}><td className="mono">{e.elapsedMicros}</td><td className="mono">{e.source}</td><td>{e.thread}</td><td>{e.activity}</td></tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      <div className="meta">
        <span className={"status " + r.status}>{r.status}</span>
        <span>{r.kind}</span>
        {r.columns.length > 0 && <span>{rows.length} row(s){hasMore ? " (more available)" : ""}</span>}
        <span>{r.durationMs} ms</span>
        {r.coordinator && <span>coordinator {r.coordinator}</span>}
        {hasMore && pagingState && (
          <>
            <button className="btn small" disabled={loading} onClick={() => loadMore(false)}>Next page</button>
            <button className="btn small" disabled={loading} onClick={() => loadMore(true)} title="Fetch up to 10,000 more rows">Fetch all</button>
          </>
        )}
      </div>
    </>
  );
}
