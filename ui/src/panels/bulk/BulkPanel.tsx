import { useCallback, useEffect, useId, useMemo, useState, type KeyboardEvent, type ReactNode } from "react";
import { api, ApiError } from "../../lib/api";
import type { ClusterInfo, ConnectionConfig, SchemaTree } from "../../lib/types";
import { jobDone, type Job } from "../../lib/jobsTypes";
import { JobProgress } from "../../components/JobProgress";
import { errorText, useGuarded, useToast } from "../../components/feedback";
import { bulkApi, type BulkJob, type LoadPreview, type LoadRequest, type TextOptions, type UnloadRequest } from "./bulkApi";
import {
  defaultUnloadPath, fmtBytes, fmtDuration, fmtInt, mappingList, mappingProblems, mappingRecord, statsLine, withExtension,
} from "./bulkFormat";
import "./bulk.css";

// Phase 3 Track 6: unload and load CSV/JSON (BLK-1/2), a DSBulk equivalent.
const CONSISTENCY = ["LOCAL_ONE", "ONE", "LOCAL_QUORUM", "QUORUM", "EACH_QUORUM", "ALL", "TWO", "THREE", "ANY"];
type View = "unload" | "load";
const VIEWS: [View, string][] = [["unload", "Unload (export)"], ["load", "Load (import)"]];

export function BulkPanel(props: { conn: ConnectionConfig; info: ClusterInfo; dark: boolean }) {
  const id = props.conn.id ?? "";
  const uid = useId();
  const [view, setView] = useState<View>("unload");
  const [schema, setSchema] = useState<SchemaTree | null>(null);
  const [defaults, setDefaults] = useState<{ downloadsDir: string; separator: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [activeJob, setActiveJob] = useState<string | null>(null);
  const [jobs, setJobs] = useState<BulkJob[]>([]);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let alive = true;
    api.schema(id).then((s) => alive && setSchema(s)).catch((e) => alive && setError(errorText(e)));
    bulkApi.defaults(id).then((d) => alive && setDefaults(d)).catch(() => alive && setDefaults({ downloadsDir: "", separator: "/" }));
    return () => { alive = false; };
  }, [id]);

  // Recent bulk jobs with live counters: every 1.5 s while one runs, else on demand.
  useEffect(() => {
    let alive = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const load = () => bulkApi.jobs(id).then((j) => {
      if (!alive) return;
      setJobs(j);
      if (j.some((x) => !jobDone(x.job))) timer = setTimeout(load, 1500);
    }).catch(() => undefined);
    load();
    return () => { alive = false; if (timer) clearTimeout(timer); };
  }, [id, tick]);

  const started = useCallback((j: Job) => { setActiveJob(j.id); setTick((t) => t + 1); }, []);
  const keyspaces = useMemo(() => (schema?.keyspaces ?? []).slice().sort((a, b) => Number(a.system) - Number(b.system) || a.name.localeCompare(b.name)), [schema]);

  const onTabKey = (e: KeyboardEvent) => {
    const i = VIEWS.findIndex(([k]) => k === view);
    const next = e.key === "ArrowRight" ? i + 1 : e.key === "ArrowLeft" ? i - 1 : null;
    if (next === null) return;
    e.preventDefault();
    const k = VIEWS[(next + VIEWS.length) % VIEWS.length][0];
    setView(k);
    document.getElementById(`${uid}-tab-${k}`)?.focus();
  };
  const active = jobs.find((j) => j.job.id === activeJob);

  return (
    <div className="bulk scroll" data-testid="bulk-panel">
      <div className="bulk-top">
        <div className="bulk-tabs" role="tablist" aria-label="Bulk views" onKeyDown={onTabKey}>
          {VIEWS.map(([k, label]) => (
            <button key={k} id={`${uid}-tab-${k}`} role="tab" aria-selected={view === k} aria-controls={`${uid}-view`}
              tabIndex={view === k ? 0 : -1} className={"bulk-tab" + (view === k ? " active" : "")} onClick={() => setView(k)}>
              {label}
            </button>
          ))}
        </div>
        <span className="spacer" />
        <span className="muted">Files are read and written on this computer{defaults?.downloadsDir ? ` (default folder ${defaults.downloadsDir})` : ""}.</span>
      </div>
      {error && <div className="notice error" role="alert">Could not read the schema: {error}</div>}
      <div id={`${uid}-view`} role="tabpanel" aria-labelledby={`${uid}-tab-${view}`} className="pad stack">
        {view === "unload"
          ? <UnloadForm id={id} keyspaces={keyspaces} defaults={defaults} onStarted={started} />
          : <LoadForm id={id} keyspaces={keyspaces} readOnly={props.conn.readOnly} onStarted={started} />}
        {activeJob && (
          <section className="panel" aria-label="Current bulk job">
            <h3>Current job</h3>
            <JobProgress jobId={activeJob} onDone={() => setTick((t) => t + 1)} />
            {active?.stats && <div className="bulk-stats" data-testid="bulk-live-stats">{statsLine(active.stats)}</div>}
            {active?.stats?.errorFile && <div className="muted">Errors: <span className="mono">{active.stats.errorFile}</span></div>}
            {active?.stats?.rejectFile && <div className="muted">Rejected rows: <span className="mono">{active.stats.rejectFile}</span></div>}
          </section>
        )}
        <RecentJobs jobs={jobs} active={activeJob} onSelect={setActiveJob} onRefresh={() => setTick((t) => t + 1)} />
      </div>
    </div>
  );
}

// ---- shared bits ---------------------------------------------------------------------------

function Field(props: { label: string; children: ReactNode; hint?: string; wide?: boolean }) {
  return (
    <label className={"field" + (props.wide ? " bulk-wide" : "")} title={props.hint}>
      <span>{props.label}</span>
      {props.children}
    </label>
  );
}

function KeyspaceTable(props: {
  keyspaces: SchemaTree["keyspaces"]; keyspace: string; table: string; idPrefix: string;
  onKeyspace: (k: string) => void; onTable: (t: string) => void;
}) {
  const tables = props.keyspaces.find((k) => k.name === props.keyspace)?.tables ?? [];
  return (
    <>
      <Field label="Keyspace">
        <select value={props.keyspace} onChange={(e) => props.onKeyspace(e.target.value)} data-testid={`${props.idPrefix}-keyspace`}>
          <option value="">Choose…</option>
          {props.keyspaces.map((k) => <option key={k.name} value={k.name}>{k.name}{k.system ? " (system)" : ""}</option>)}
        </select>
      </Field>
      <Field label="Table">
        <select value={props.table} onChange={(e) => props.onTable(e.target.value)} disabled={!props.keyspace} data-testid={`${props.idPrefix}-table`}>
          <option value="">Choose…</option>
          {tables.map((t) => <option key={t.name} value={t.name}>{t.name}</option>)}
        </select>
      </Field>
    </>
  );
}

function TextOptionsFields(props: { value: TextOptions; onChange: (v: TextOptions) => void; csv: boolean }) {
  const v = props.value;
  const set = (patch: Partial<TextOptions>) => props.onChange({ ...v, ...patch });
  return (
    <>
      {props.csv && (
        <>
          <label className="check"><input type="checkbox" checked={v.header !== false} onChange={(e) => set({ header: e.target.checked })} /> Header row</label>
          <Field label="Delimiter" hint="One character; \t for tab">
            <input type="text" value={v.delimiter ?? ","} onChange={(e) => set({ delimiter: e.target.value })} size={3} />
          </Field>
          <Field label="Null as" hint="How a null looks in the file (empty by default); a real value equal to it is quoted">
            <input type="text" value={v.nullString ?? ""} onChange={(e) => set({ nullString: e.target.value })} placeholder="(empty)" />
          </Field>
        </>
      )}
      <Field label="Timestamp format" hint="Java DateTimeFormatter pattern; empty = ISO-8601 (2024-05-01T10:00:00Z)">
        <input type="text" value={v.timestampFormat ?? ""} onChange={(e) => set({ timestampFormat: e.target.value })} placeholder="ISO-8601" />
      </Field>
      <Field label="Date format" hint="Pattern for date columns; empty = ISO (2024-05-01)">
        <input type="text" value={v.dateFormat ?? ""} onChange={(e) => set({ dateFormat: e.target.value })} placeholder="ISO" />
      </Field>
      <Field label="Time zone" hint="For timestamps without an offset">
        <input type="text" value={v.timeZone ?? "UTC"} onChange={(e) => set({ timeZone: e.target.value })} />
      </Field>
      <Field label="Blobs">
        <select value={v.blobFormat ?? "hex"} onChange={(e) => set({ blobFormat: e.target.value as "hex" | "base64" })}>
          <option value="hex">0x hex</option>
          <option value="base64">base64</option>
        </select>
      </Field>
    </>
  );
}

function num(s: string): number | undefined {
  return s.trim() === "" ? undefined : Number(s);
}

// ---- unload --------------------------------------------------------------------------------

function UnloadForm(props: { id: string; keyspaces: SchemaTree["keyspaces"]; defaults: { downloadsDir: string; separator: string } | null; onStarted: (j: Job) => void }) {
  const toast = useToast();
  const [mode, setMode] = useState<"table" | "query">("table");
  const [keyspace, setKeyspace] = useState("");
  const [table, setTable] = useState("");
  const [columns, setColumns] = useState<string[] | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [query, setQuery] = useState("");
  const [format, setFormat] = useState<"csv" | "json">("csv");
  const [gzip, setGzip] = useState(false);
  const [path, setPath] = useState("");
  const [pathEdited, setPathEdited] = useState(false);
  const [overwrite, setOverwrite] = useState(false);
  const [text, setText] = useState<TextOptions>({ delimiter: ",", nullString: "", header: true, timeZone: "UTC", blobFormat: "hex" });
  const [consistency, setConsistency] = useState("LOCAL_ONE");
  const [pageSize, setPageSize] = useState("5000");
  const [concurrency, setConcurrency] = useState("8");
  const [maxRows, setMaxRows] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  useEffect(() => {
    if (!props.defaults || pathEdited) return;
    const name = mode === "table" ? (keyspace && table ? `${keyspace}.${table}` : "export") : "query";
    setPath(defaultUnloadPath(props.defaults.downloadsDir, props.defaults.separator, name, format, gzip));
  }, [props.defaults, keyspace, table, mode, format, gzip, pathEdited]);

  useEffect(() => {
    setColumns(null);
    setSelected(new Set());
    if (!keyspace || !table) return;
    let alive = true;
    api.table(props.id, keyspace, table).then((t) => {
      if (!alive) return;
      setColumns(t.columns.map((c) => c.name));
      setSelected(new Set(t.columns.map((c) => c.name)));
    }).catch(() => undefined);
    return () => { alive = false; };
  }, [props.id, keyspace, table]);

  const switchFormat = (f: "csv" | "json", g: boolean) => {
    setFormat(f);
    setGzip(g);
    if (pathEdited) setPath((p) => withExtension(p, f, g));
  };

  const ready = mode === "table" ? Boolean(keyspace && table && selected.size) : Boolean(query.trim());
  const submit = async () => {
    setProblem(null);
    setBusy(true);
    const req: UnloadRequest = {
      mode, path: path.trim(), overwrite, format, compression: gzip ? "gzip" : "none", consistency,
      pageSize: num(pageSize), concurrency: num(concurrency), maxRows: num(maxRows), ...text,
      ...(mode === "table"
        ? { keyspace, table, columns: columns && selected.size < columns.length ? columns.filter((c) => selected.has(c)) : [] }
        : { query, keyspace: keyspace || undefined }),
    };
    try {
      const job = await bulkApi.unload(props.id, req);
      toast.info("Unload started");
      props.onStarted(job);
    } catch (e) {
      setProblem(e instanceof ApiError && e.code === "file_exists" ? e.message : errorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="panel stack" aria-label="Unload">
      <div className="row" role="radiogroup" aria-label="What to unload">
        <label className="check"><input type="radio" name="bulk-unload-mode" checked={mode === "table"} onChange={() => setMode("table")} /> A whole table (token-range parallel)</label>
        <label className="check"><input type="radio" name="bulk-unload-mode" checked={mode === "query"} onChange={() => setMode("query")} /> A CQL query</label>
      </div>
      <div className="bulk-grid">
        <KeyspaceTable keyspaces={props.keyspaces} keyspace={keyspace} table={mode === "table" ? table : ""} idPrefix="bulk-unload"
          onKeyspace={(k) => { setKeyspace(k); setTable(""); }} onTable={setTable} />
        {mode === "query" && (
          <Field label="SELECT query" wide hint="Read in one stream, page by page">
            <textarea rows={3} value={query} onChange={(e) => setQuery(e.target.value)} placeholder="SELECT * FROM shop.orders WHERE customer_id = 42" data-testid="bulk-unload-query" />
          </Field>
        )}
      </div>
      {mode === "table" && columns && (
        <fieldset className="bulk-columns">
          <legend>Columns ({selected.size} of {columns.length})</legend>
          <div className="row">
            {columns.map((c) => (
              <label key={c} className="check">
                <input type="checkbox" checked={selected.has(c)} onChange={(e) => {
                  const n = new Set(selected);
                  if (e.target.checked) n.add(c); else n.delete(c);
                  setSelected(n);
                }} /> {c}
              </label>
            ))}
          </div>
        </fieldset>
      )}
      <div className="bulk-grid">
        <Field label="File on this computer" wide>
          <input type="text" value={path} onChange={(e) => { setPath(e.target.value); setPathEdited(true); }} data-testid="bulk-unload-path" spellCheck={false} />
        </Field>
        <Field label="Format">
          <select value={format} onChange={(e) => switchFormat(e.target.value as "csv" | "json", gzip)} data-testid="bulk-unload-format">
            <option value="csv">CSV</option>
            <option value="json">JSON lines</option>
          </select>
        </Field>
        <Field label="Compression">
          <select value={gzip ? "gzip" : "none"} onChange={(e) => switchFormat(format, e.target.value === "gzip")}>
            <option value="none">None</option>
            <option value="gzip">gzip</option>
          </select>
        </Field>
        <label className="check"><input type="checkbox" checked={overwrite} onChange={(e) => setOverwrite(e.target.checked)} /> Overwrite if the file exists</label>
      </div>
      <details className="bulk-advanced">
        <summary>Options: values, consistency, paging, concurrency, limits</summary>
        <div className="bulk-grid">
          <TextOptionsFields value={text} onChange={setText} csv={format === "csv"} />
          <Field label="Consistency">
            <select value={consistency} onChange={(e) => setConsistency(e.target.value)}>{CONSISTENCY.filter((c) => c !== "ANY").map((c) => <option key={c}>{c}</option>)}</select>
          </Field>
          <Field label="Page size"><input type="number" min={10} max={100000} value={pageSize} onChange={(e) => setPageSize(e.target.value)} /></Field>
          <Field label="Concurrency" hint="Token ranges read in parallel"><input type="number" min={1} max={64} value={concurrency} onChange={(e) => setConcurrency(e.target.value)} /></Field>
          <Field label="Max rows" hint="Stop after this many rows; empty = all"><input type="number" min={0} value={maxRows} onChange={(e) => setMaxRows(e.target.value)} placeholder="all" /></Field>
        </div>
      </details>
      {problem && <div className="notice error" role="alert" data-testid="bulk-unload-error">{problem}</div>}
      <div className="row">
        <button className="btn primary" disabled={!ready || !path.trim() || busy} onClick={submit} data-testid="bulk-unload-start">Unload</button>
        <span className="muted">Reads only; on a PROD connection the export is recorded in the audit log.</span>
      </div>
    </section>
  );
}

// ---- load ----------------------------------------------------------------------------------

function LoadForm(props: { id: string; keyspaces: SchemaTree["keyspaces"]; readOnly: boolean; onStarted: (j: Job) => void }) {
  const toast = useToast();
  const guarded = useGuarded();
  const [path, setPath] = useState("");
  const [format, setFormat] = useState<"auto" | "csv" | "json">("auto");
  const [keyspace, setKeyspace] = useState("");
  const [table, setTable] = useState("");
  const [text, setText] = useState<TextOptions>({ delimiter: ",", nullString: "", header: true, timeZone: "UTC", blobFormat: "hex" });
  const [preview, setPreview] = useState<LoadPreview | null>(null);
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [ttlMode, setTtlMode] = useState<"none" | "fixed" | "field">("none");
  const [ttl, setTtl] = useState("");
  const [tsMode, setTsMode] = useState<"none" | "fixed" | "field">("none");
  const [ts, setTs] = useState("");
  const [batchSize, setBatchSize] = useState("32");
  const [concurrency, setConcurrency] = useState("16");
  const [rateLimit, setRateLimit] = useState("");
  const [maxErrors, setMaxErrors] = useState("100");
  const [consistency, setConsistency] = useState("LOCAL_QUORUM");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const csv = format === "csv" || (format === "auto" && !/\.(jsonl?|ndjson)(\.gz)?$/i.test(path.trim()));
  const base = (): Omit<LoadRequest, "keyspace" | "table"> & { keyspace?: string; table?: string } => ({
    path: path.trim(), format: format === "auto" ? undefined : format, keyspace: keyspace || undefined, table: table || undefined, ...text,
  });

  const doPreview = async () => {
    setProblem(null);
    setBusy(true);
    try {
      const p = await bulkApi.preview(props.id, base());
      setPreview(p);
      setMapping(mappingRecord(p.tableColumns ?? [], p.mapping));
    } catch (e) {
      setPreview(null);
      setProblem(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  // Re-read the file's header and the table's columns when the table changes after a preview.
  useEffect(() => {
    if (preview && keyspace && table && preview.tableColumns === undefined) void doPreview();
  }, [keyspace, table]); // eslint-disable-line react-hooks/exhaustive-deps

  const cols = preview?.tableColumns ?? [];
  const problems = preview && cols.length ? mappingProblems(cols, mapping) : [];
  const fileCols = preview?.fileColumns ?? [];

  const request = (dryRun: boolean): LoadRequest => ({
    ...base(), keyspace, table, mapping: mappingList(mapping), dryRun,
    ttlSeconds: ttlMode === "fixed" ? num(ttl) ?? null : null, ttlField: ttlMode === "field" ? ttl || null : null,
    timestampMicros: tsMode === "fixed" ? num(ts) ?? null : null, timestampField: tsMode === "field" ? ts || null : null,
    batchSize: num(batchSize), concurrency: num(concurrency), rateLimit: num(rateLimit) ?? 0, maxErrors: num(maxErrors), consistency,
  });

  const start = async (dryRun: boolean) => {
    setProblem(null);
    setBusy(true);
    try {
      const job = await guarded((c) => bulkApi.load(props.id, request(dryRun), c));
      if (job) {
        toast.info(dryRun ? "Validation started" : "Load started");
        props.onStarted(job);
      }
    } catch (e) {
      setProblem(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const canRun = Boolean(preview && keyspace && table && cols.length && !problems.length && !preview.error);
  return (
    <section className="panel stack" aria-label="Load">
      <div className="bulk-grid">
        <Field label="File on this computer" wide hint="CSV, or JSON lines (one object per line); .gz is read as gzip">
          <input type="text" value={path} onChange={(e) => setPath(e.target.value)} placeholder="/home/me/Downloads/orders.csv" data-testid="bulk-load-path" spellCheck={false} />
        </Field>
        <Field label="Format">
          <select value={format} onChange={(e) => setFormat(e.target.value as "auto" | "csv" | "json")}>
            <option value="auto">From the file name</option>
            <option value="csv">CSV</option>
            <option value="json">JSON lines</option>
          </select>
        </Field>
        <KeyspaceTable keyspaces={props.keyspaces} keyspace={keyspace} table={table} idPrefix="bulk-load"
          onKeyspace={(k) => { setKeyspace(k); setTable(""); }}
          onTable={(t) => { setTable(t); if (preview) setPreview({ ...preview, tableColumns: undefined }); }} />
      </div>
      {csv && (
        <div className="bulk-grid">
          <TextOptionsFields value={text} onChange={setText} csv={true} />
        </div>
      )}
      <div className="row">
        <button className="btn" onClick={doPreview} disabled={!path.trim() || busy} data-testid="bulk-load-preview">Preview file</button>
        {preview && <span className="muted">{preview.format.toUpperCase()}{preview.gzip ? " (gzip)" : ""} · {fmtBytes(preview.sizeBytes)} · ~{fmtInt(preview.estimatedRows)} rows · {preview.fileColumns.length} columns</span>}
      </div>
      {problem && <div className="notice error" role="alert" data-testid="bulk-load-error">{problem}</div>}
      {preview?.error && <div className="notice error" role="alert">{preview.error}</div>}

      {preview && (
        <div className="bulk-sample scroll" data-testid="bulk-load-sample" tabIndex={0} role="region" aria-label="First rows of the file">
          <table className="data">
            <caption className="muted">First {preview.sampleRows.length} rows of the file</caption>
            <thead><tr>{fileCols.map((c) => <th key={c} scope="col">{c}</th>)}</tr></thead>
            <tbody>
              {preview.sampleRows.map((r, i) => (
                <tr key={i}>{fileCols.map((_, j) => <td key={j}>{r[j] === null || r[j] === undefined ? <span className="muted">null</span> : r[j]}</td>)}</tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {preview && cols.length > 0 && (
        <table className="data bulk-mapping" data-testid="bulk-load-mapping">
          <caption className="muted">Column mapping: which file column feeds each table column</caption>
          <thead><tr><th scope="col">Table column</th><th scope="col">Type</th><th scope="col">Key</th><th scope="col">From file column</th></tr></thead>
          <tbody>
            {cols.map((c) => (
              <tr key={c.name}>
                <td><b>{c.name}</b></td>
                <td className="mono">{c.type}</td>
                <td>{c.kind === "partition_key" ? "partition" : c.kind === "clustering" ? "clustering" : ""}</td>
                <td>
                  <select aria-label={`File column for ${c.name}`} value={mapping[c.name] ?? ""}
                    onChange={(e) => setMapping({ ...mapping, [c.name]: e.target.value })}>
                    <option value="">{c.kind === "regular" ? "— not loaded —" : "— choose (required) —"}</option>
                    {fileCols.map((f) => <option key={f} value={f}>{f}</option>)}
                  </select>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {problems.map((p) => <div key={p} className="notice warn">{p}</div>)}

      {preview && (
        <details className="bulk-advanced" open>
          <summary>Write options</summary>
          <div className="bulk-grid">
            <Field label="TTL">
              <select value={ttlMode} onChange={(e) => { setTtlMode(e.target.value as "none" | "fixed" | "field"); setTtl(""); }}>
                <option value="none">None (table default)</option>
                <option value="fixed">Fixed seconds</option>
                <option value="field">From a file column</option>
              </select>
            </Field>
            {ttlMode === "fixed" && <Field label="TTL seconds"><input type="number" min={0} value={ttl} onChange={(e) => setTtl(e.target.value)} /></Field>}
            {ttlMode === "field" && (
              <Field label="TTL column">
                <select value={ttl} onChange={(e) => setTtl(e.target.value)}><option value="">Choose…</option>{fileCols.map((f) => <option key={f}>{f}</option>)}</select>
              </Field>
            )}
            <Field label="Write timestamp (USING TIMESTAMP)">
              <select value={tsMode} onChange={(e) => { setTsMode(e.target.value as "none" | "fixed" | "field"); setTs(""); }}>
                <option value="none">Now (server)</option>
                <option value="fixed">Fixed (epoch µs)</option>
                <option value="field">From a file column</option>
              </select>
            </Field>
            {tsMode === "fixed" && <Field label="Timestamp µs"><input type="number" min={0} value={ts} onChange={(e) => setTs(e.target.value)} /></Field>}
            {tsMode === "field" && (
              <Field label="Timestamp column" hint="Epoch microseconds or a timestamp">
                <select value={ts} onChange={(e) => setTs(e.target.value)}><option value="">Choose…</option>{fileCols.map((f) => <option key={f}>{f}</option>)}</select>
              </Field>
            )}
            <Field label="Batch size" hint="Rows of the same partition per unlogged batch; 1 = no batches"><input type="number" min={1} max={500} value={batchSize} onChange={(e) => setBatchSize(e.target.value)} /></Field>
            <Field label="Concurrency" hint="Requests in flight"><input type="number" min={1} max={256} value={concurrency} onChange={(e) => setConcurrency(e.target.value)} /></Field>
            <Field label="Rate limit (rows/s)"><input type="number" min={0} value={rateLimit} onChange={(e) => setRateLimit(e.target.value)} placeholder="none" /></Field>
            <Field label="Max errors" hint="Stop after this many rejected rows; -1 = never"><input type="number" min={-1} value={maxErrors} onChange={(e) => setMaxErrors(e.target.value)} /></Field>
            <Field label="Consistency">
              <select value={consistency} onChange={(e) => setConsistency(e.target.value)}>{CONSISTENCY.map((c) => <option key={c}>{c}</option>)}</select>
            </Field>
            {!csv && <TextOptionsFields value={text} onChange={setText} csv={false} />}
          </div>
        </details>
      )}
      <div className="row">
        <button className="btn" disabled={!canRun || busy} onClick={() => start(true)} data-testid="bulk-load-dry-run">Validate (dry run)</button>
        <button className="btn primary" disabled={!canRun || busy || props.readOnly} onClick={() => start(false)} data-testid="bulk-load-start">Load</button>
        {props.readOnly && <span className="muted">Read-only connection: only a dry run is possible.</span>}
        <span className="muted">Rejected rows go to a .rejected file next to the source, with the reason in .rejected.log.</span>
      </div>
    </section>
  );
}

// ---- recent jobs ---------------------------------------------------------------------------

function RecentJobs(props: { jobs: BulkJob[]; active: string | null; onSelect: (id: string) => void; onRefresh: () => void }) {
  return (
    <section className="panel" aria-label="Recent bulk jobs">
      <div className="row">
        <h3 style={{ margin: 0 }}>Recent bulk jobs</h3>
        <span className="spacer" />
        <button className="btn small" onClick={props.onRefresh}>⟳ Refresh</button>
      </div>
      {props.jobs.length === 0 ? <div className="muted">No bulk jobs yet in this session.</div> : (
        <table className="data" data-testid="bulk-jobs">
          <thead><tr><th scope="col">Job</th><th scope="col">State</th><th scope="col">Rows</th><th scope="col">Rejected</th><th scope="col">Rate</th><th scope="col">Time</th><th scope="col">File</th></tr></thead>
          <tbody>
            {props.jobs.map(({ job, stats }) => (
              <tr key={job.id} className={job.id === props.active ? "bulk-selected" : ""}>
                <td><button className="bulk-job-link" onClick={() => props.onSelect(job.id)} aria-current={job.id === props.active ? "true" : undefined}>{job.title}</button></td>
                <td><span className={"status " + (job.state === "SUCCEEDED" ? "ok" : job.state === "FAILED" ? "error" : job.state === "CANCELLED" ? "skipped" : "")}>{job.state}</span></td>
                <td>{fmtInt(stats?.rowsWritten)}</td>
                <td>{stats?.kind === "load" ? fmtInt(stats.rejected) : stats?.rangesFailed ? `${stats.rangesFailed} ranges` : "–"}</td>
                <td>{stats ? `${fmtInt(stats.rowsPerSecond)}/s` : "–"}</td>
                <td>{fmtDuration(stats?.elapsedMs)}</td>
                <td className="mono bulk-path" title={stats?.path}>{stats?.path ?? ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
