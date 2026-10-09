import { useEffect, useMemo, useState } from "react";
import { JobProgress } from "../../components/JobProgress";
import { errorText } from "../../components/feedback";
import { api } from "../../lib/api";
import type { Job } from "../../lib/jobsTypes";
import { Empty, ErrorState, Loading, SortTable, Val, type Col } from "../monitoring/common";
import { fmtBytes, fmtCount, fmtNumber } from "../monitoring/format";
import {
  isSystemKeyspace, type DiagClient, type DiagSettings, type HistView, type HotResult, type LogWarning,
  type Pcts, type ScanResult, type TableHist, type TableResult, type WarningsView,
} from "./diagApi";

const SAMPLER_LABEL: Record<string, string> = {
  READS: "Top keys by reads",
  WRITES: "Top keys by writes",
  WRITE_SIZE: "Largest partition updates (bytes)",
};

function Flag(props: { on: boolean; v: string | null; why: string }) {
  return props.on ? <span className="mon-warn-text" title={props.why}>▲ {props.v}</span> : <Val v={props.v} />;
}

const pct = (p: Pcts, k: keyof Pcts, fmt: (v: number | null) => string | null) => fmt(p[k] as number | null);
const bytes = (v: number | null) => fmtBytes(v);
const num = (v: number | null) => fmtNumber(v, 1);

function histCols(th: HistView["thresholds"]): Col<TableHist>[] {
  return [
    { key: "t", label: "Table", sort: (t) => t.keyspace + "." + t.table, render: (t) => <span>{t.keyspace}.<b>{t.table}</b>{t.node && <span className="muted"> @{t.node}</span>}</span> },
    { key: "p50", label: "Partition p50", num: true, sort: (t) => t.partitionSize.p50, render: (t) => <Val v={pct(t.partitionSize, "p50", bytes)} /> },
    { key: "p99", label: "Partition p99", num: true, sort: (t) => t.partitionSize.p99, render: (t) => <Val v={pct(t.partitionSize, "p99", bytes)} /> },
    {
      key: "pmax", label: "Partition max", num: true, sort: (t) => t.partitionSize.max,
      render: (t) => <Flag on={t.flags.includes("LARGE_PARTITION")} v={pct(t.partitionSize, "max", bytes)} why={`Larger than ${fmtBytes(th.largePartitionBytes)}`} />,
    },
    { key: "c99", label: "Cells p99", num: true, sort: (t) => t.cellCount.p99, render: (t) => <Val v={pct(t.cellCount, "p99", (v) => fmtCount(v))} /> },
    { key: "cmax", label: "Cells max", num: true, sort: (t) => t.cellCount.max, render: (t) => <Val v={pct(t.cellCount, "max", (v) => fmtCount(v))} /> },
    {
      key: "t99", label: "Tombstones/read p99", num: true, sort: (t) => t.tombstonesPerRead.p99,
      render: (t) => <Flag on={t.flags.includes("TOMBSTONES")} v={pct(t.tombstonesPerRead, "p99", num)} why={`Over ${th.tombstonesP99} tombstones per read (p99)`} />,
    },
    { key: "tmax", label: "Tombstones/read max", num: true, sort: (t) => t.tombstonesPerRead.max, render: (t) => <Val v={pct(t.tombstonesPerRead, "max", num)} /> },
    { key: "s99", label: "SSTables/read p99", num: true, sort: (t) => t.sstablesPerRead.p99, render: (t) => <Val v={pct(t.sstablesPerRead, "p99", num)} /> },
    { key: "l99", label: "Live cells/read p99", num: true, sort: (t) => t.liveCellsPerRead.p99, render: (t) => <Val v={pct(t.liveCellsPerRead, "p99", num)} /> },
  ];
}

/** Table histograms with large-partition and tombstone flags (PRF-2). */
function HistogramsSection(props: { client: DiagClient; nodes: string[]; keyspaces: string[] }) {
  const [node, setNode] = useState("");
  const [keyspace, setKeyspace] = useState("");
  const [view, setView] = useState<HistView | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);
  const [flaggedOnly, setFlaggedOnly] = useState(false);
  const load = () => {
    setLoading(true);
    setError(null);
    props.client.histograms(node || null, keyspace || null).then(setView).catch(setError).finally(() => setLoading(false));
  };
  useEffect(load, [props.client, node, keyspace]); // eslint-disable-line react-hooks/exhaustive-deps
  const rows = view ? (flaggedOnly ? view.merged.filter((t) => t.flags.length) : view.merged) : [];
  return (
    <section className="stack" aria-labelledby="diag-hist-h">
      <h3 id="diag-hist-h">Table histograms</h3>
      <div className="row">
        <label className="row"><span className="muted">Node</span>
          <select value={node} onChange={(e) => setNode(e.target.value)} aria-label="Histogram node">
            <option value="">All nodes (worst value)</option>
            {props.nodes.map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
        </label>
        <label className="row"><span className="muted">Keyspace</span>
          <select value={keyspace} onChange={(e) => setKeyspace(e.target.value)} aria-label="Histogram keyspace">
            <option value="">All non-system keyspaces</option>
            {props.keyspaces.map((k) => <option key={k} value={k}>{k}</option>)}
          </select>
        </label>
        <label className="row"><input type="checkbox" checked={flaggedOnly} onChange={(e) => setFlaggedOnly(e.target.checked)} /> Flagged only</label>
        <span className="spacer" />
        <button className="btn small" onClick={load} disabled={loading}>⟳ Refresh</button>
      </div>
      {error ? <ErrorState error={error} onRetry={load} /> : !view ? <Loading what="table histograms" /> : (
        <>
          {view.errors.map((e) => <div key={e.node} className="notice warn">{e.node}: {e.error}</div>)}
          {rows.length ? (
            <SortTable rows={rows} cols={histCols(view.thresholds)} rowKey={(t) => t.keyspace + "." + t.table} label="Table histograms"
              testId="diag-hist-table" rowClass={(t) => (t.flags.length ? "diag-flagged" : undefined)} />
          ) : <Empty>{flaggedOnly ? "No table is over a threshold." : "No tables found."}</Empty>}
          <div className="muted">
            ▲ max partition over {fmtBytes(view.thresholds.largePartitionBytes)} or p99 tombstones per read over {view.thresholds.tombstonesP99}.
            Partition size and cell count come from the SSTables (memtable-only tables show 0); per-read values are recent reads.
          </div>
        </>
      )}
    </section>
  );
}

function SamplerTable(props: { r: TableResult["samplers"][number]; showNodes: boolean }) {
  const { r } = props;
  const size = r.sampler === "WRITE_SIZE";
  return (
    <div className="diag-sampler">
      <h5>{SAMPLER_LABEL[r.sampler] ?? r.sampler}{r.cardinality != null && <span className="muted"> · ~{r.cardinality} distinct keys</span>}</h5>
      {r.error ? <div className="notice warn">{r.error}</div> : r.top.length ? (
        <table className="data diag-small-table" aria-label={`${SAMPLER_LABEL[r.sampler] ?? r.sampler}`}>
          <thead><tr><th>Partition key</th><th className="num">{size ? "Bytes" : "Count"}</th><th className="num">± error</th>{props.showNodes && <th>Nodes</th>}</tr></thead>
          <tbody>
            {r.top.map((k) => (
              <tr key={k.key}>
                <td><code>{k.key}</code></td><td className="num">{size ? fmtBytes(k.count) : k.count}</td><td className="num">{k.error}</td>
                {props.showNodes && <td>{k.nodes.join(", ")}</td>}
              </tr>
            ))}
          </tbody>
        </table>
      ) : <div className="muted">No activity sampled.</div>}
    </div>
  );
}

/** toppartitions as a job (PRF-1). */
function HotSection(props: { client: DiagClient; nodes: string[]; tables: string[] }) {
  const [chosen, setChosen] = useState<string[]>([]);
  const [duration, setDuration] = useState(10);
  const [top, setTop] = useState(10);
  const [node, setNode] = useState("");
  const [jobId, setJobId] = useState<string | null>(null);
  const [result, setResult] = useState<HotResult | null>(null);
  const [perNode, setPerNode] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const start = () => {
    setErr(null);
    setResult(null);
    props.client.hot({ tables: chosen, durationSec: duration, top, nodes: node ? [node] : [] })
      .then((j) => setJobId(j.id)).catch((e) => setErr(errorText(e)));
  };
  const done = (j: Job) => { if (j.state === "SUCCEEDED") setResult(j.result as HotResult); };
  return (
    <section className="stack" aria-labelledby="diag-hot-h">
      <h3 id="diag-hot-h">Hot partitions</h3>
      <div className="row diag-form">
        <label className="stack diag-tables"><span className="muted">Tables (Ctrl/Cmd-click for several)</span>
          <select multiple size={Math.min(8, Math.max(3, props.tables.length))} value={chosen} aria-label="Tables to sample"
            onChange={(e) => setChosen(Array.from(e.target.selectedOptions).map((o) => o.value))}>
            {props.tables.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        </label>
        <div className="stack">
          <label className="row"><span className="muted">Duration</span>
            <input type="number" min={1} max={600} value={duration} onChange={(e) => setDuration(Number(e.target.value))} className="diag-num" aria-label="Sampling duration in seconds" /> s
          </label>
          <label className="row"><span className="muted">Top</span>
            <input type="number" min={1} max={100} value={top} onChange={(e) => setTop(Number(e.target.value))} className="diag-num" aria-label="Keys per list" />
          </label>
          <label className="row"><span className="muted">Nodes</span>
            <select value={node} onChange={(e) => setNode(e.target.value)} aria-label="Nodes to sample">
              <option value="">All nodes</option>
              {props.nodes.map((n) => <option key={n} value={n}>{n}</option>)}
            </select>
          </label>
          <button className="btn primary" onClick={start} disabled={!chosen.length}>Sample hot partitions</button>
        </div>
      </div>
      {err && <div className="notice error" role="alert">{err}</div>}
      {jobId && <JobProgress jobId={jobId} onDone={done} />}
      {result && (
        <div className="stack" data-testid="diag-hot-result">
          <label className="row"><input type="checkbox" checked={perNode} onChange={(e) => setPerNode(e.target.checked)} /> Per node (default: all nodes merged)</label>
          {result.nodes.filter((n) => n.error).map((n) => <div key={n.node} className="notice warn">{n.node}: {n.error}</div>)}
          {perNode ? result.nodes.filter((n) => n.tables).map((n) => (
            <div key={n.node} className="stack">
              <h4>{n.node} <span className="muted">({n.api} API)</span></h4>
              {n.tables!.map((t) => <HotTable key={t.keyspace + t.table} t={t} showNodes={false} />)}
            </div>
          )) : result.merged.map((t) => <HotTable key={t.keyspace + t.table} t={t} showNodes />)}
        </div>
      )}
    </section>
  );
}

function HotTable(props: { t: TableResult; showNodes: boolean }) {
  return (
    <div className="stack">
      <h4>{props.t.keyspace}.{props.t.table}</h4>
      <div className="diag-cols">{props.t.samplers.map((s) => <SamplerTable key={s.sampler} r={s} showNodes={props.showNodes} />)}</div>
    </div>
  );
}

const KIND_LABEL: Record<LogWarning["kind"], string> = {
  TOMBSTONE_WARN: "Tombstones",
  TOMBSTONE_ABORT: "Tombstones (aborted)",
  LARGE_PARTITION_WRITE: "Large partition (write)",
  LARGE_PARTITION_COMPACT: "Large partition (compaction)",
};

const warnCols: Col<LogWarning>[] = [
  { key: "time", label: "Time", sort: (w) => w.time, render: (w) => w.time },
  { key: "node", label: "Node", sort: (w) => w.node, render: (w) => w.node },
  { key: "kind", label: "Kind", sort: (w) => w.kind, render: (w) => <span className={"status " + (w.kind === "TOMBSTONE_ABORT" ? "error" : "BLOCKED")}>{KIND_LABEL[w.kind]}</span> },
  { key: "table", label: "Table", sort: (w) => (w.keyspace ?? "") + "." + (w.table ?? ""), render: (w) => <Val v={w.keyspace ? `${w.keyspace}.${w.table}` : null} /> },
  { key: "tomb", label: "Tombstones", num: true, sort: (w) => w.tombstones, render: (w) => <Val v={fmtCount(w.tombstones)} /> },
  { key: "size", label: "Size", num: true, sort: (w) => w.sizeBytes, render: (w) => <Val v={fmtBytes(w.sizeBytes)} /> },
  { key: "detail", label: "Partition / query", render: (w) => <code className="diag-detail">{w.partitionKey ?? w.detail ?? ""}</code> },
];

/** Warnings from system.log over SSH, and the estate's tombstone-scan.sh (PRF-2). */
function LogsSection(props: { client: DiagClient; nodes: string[]; keyspaces: string[]; settings: DiagSettings | null }) {
  const [view, setView] = useState<WarningsView | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);
  const [scanNode, setScanNode] = useState(props.nodes[0] ?? "");
  const [scanKs, setScanKs] = useState("");
  const [scanJob, setScanJob] = useState<string | null>(null);
  const [scan, setScan] = useState<ScanResult | null>(null);
  const [scanErr, setScanErr] = useState<string | null>(null);
  const load = () => {
    setLoading(true);
    setError(null);
    props.client.warnings(null).then(setView).catch(setError).finally(() => setLoading(false));
  };
  const runScan = () => {
    setScan(null);
    setScanErr(null);
    props.client.tombstoneScan(scanNode, scanKs || null, null).then((j) => setScanJob(j.id)).catch((e) => setScanErr(errorText(e)));
  };
  return (
    <section className="stack" aria-labelledby="diag-logs-h">
      <h3 id="diag-logs-h">Warnings from system.log</h3>
      <div className="row">
        <button className="btn" onClick={load} disabled={loading}>{loading ? "Reading logs…" : view ? "⟳ Read again" : "Read logs over SSH"}</button>
        <span className="muted">{props.settings?.logPath} on every node: tombstone warnings, large partitions written or compacted.</span>
      </div>
      {error ? <ErrorState error={error} onRetry={load} /> : null}
      {view && (
        <>
          {view.nodes.filter((n) => n.error).map((n) => <div key={n.node} className="notice warn">{n.node}: {n.error}</div>)}
          {view.warnings.length
            ? <SortTable rows={view.warnings} cols={warnCols} rowKey={(w) => w.node + w.time + w.kind + (w.detail ?? w.partitionKey ?? "")} label="Log warnings" testId="diag-warnings" initial={{ key: "time", dir: "desc" }} />
            : <Empty>No tombstone or large-partition warning in the logs read.</Empty>}
        </>
      )}
      <h4>Tombstone scan (estate script)</h4>
      <div className="row">
        <label className="row"><span className="muted">Node</span>
          <select value={scanNode} onChange={(e) => setScanNode(e.target.value)} aria-label="Node to scan">
            {props.nodes.map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
        </label>
        <label className="row"><span className="muted">Keyspace</span>
          <select value={scanKs} onChange={(e) => setScanKs(e.target.value)} aria-label="Keyspace to scan">
            <option value="">All</option>
            {props.keyspaces.map((k) => <option key={k} value={k}>{k}</option>)}
          </select>
        </label>
        <button className="btn" onClick={runScan} disabled={!scanNode}>Run tombstone-scan</button>
        <span className="muted">runs {props.settings?.tombstoneScanScript} (cass-ops tombstone-scan) over SSH, read-only</span>
      </div>
      {scanErr && <div className="notice error" role="alert">{scanErr}</div>}
      {scanJob && <JobProgress jobId={scanJob} onDone={(j) => { if (j.state === "SUCCEEDED") setScan(j.result as ScanResult); }} />}
      {scan && (scan.rows.length ? (
        <table className="data diag-small-table" aria-label="Tombstone scan" data-testid="diag-scan">
          <thead><tr>{scan.header.map((h) => <th key={h}>{h}</th>)}</tr></thead>
          <tbody>{scan.rows.map((r, i) => <tr key={i}>{r.cells.map((c, j) => <td key={j}>{c}</td>)}</tr>)}</tbody>
        </table>
      ) : <pre className="diag-stack">{scan.output || "(no output)"}</pre>)}
    </section>
  );
}

/** Thresholds and paths for this connection. */
function SettingsSection(props: { client: DiagClient; settings: DiagSettings | null; onSaved: (s: DiagSettings) => void }) {
  const [draft, setDraft] = useState<DiagSettings | null>(props.settings);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  useEffect(() => setDraft(props.settings), [props.settings]);
  if (!draft) return null;
  const save = () => props.client.saveSettings(draft)
    .then((s) => { props.onSaved(s); setMsg({ ok: true, text: "Saved." }); })
    .catch((e) => setMsg({ ok: false, text: errorText(e) }));
  return (
    <details className="diag-item">
      <summary><b>Settings</b> <span className="muted">thresholds and paths on the nodes</span></summary>
      <div className="diag-settings">
        <label>Large partition over (MiB)<input type="number" min={1} value={draft.largePartitionMb} onChange={(e) => setDraft({ ...draft, largePartitionMb: Number(e.target.value) })} /></label>
        <label>p99 tombstones per read over<input type="number" min={1} value={draft.tombstonesP99} onChange={(e) => setDraft({ ...draft, tombstonesP99: Number(e.target.value) })} /></label>
        <label>system.log path<input value={draft.logPath} onChange={(e) => setDraft({ ...draft, logPath: e.target.value })} /></label>
        <label>tombstone-scan script<input value={draft.tombstoneScanScript} onChange={(e) => setDraft({ ...draft, tombstoneScanScript: e.target.value })} /></label>
        <div className="row"><button className="btn small primary" onClick={save}>Save</button>
          {msg && <span className={msg.ok ? "muted" : "notice error"} role={msg.ok ? "status" : "alert"}>{msg.text}</span>}</div>
      </div>
    </details>
  );
}

/** Partitions tab: hot partitions, histograms, log warnings. */
export function PartitionsView(props: { client: DiagClient; connectionId: string; nodes: string[] }) {
  const [tables, setTables] = useState<string[]>([]);
  const [settings, setSettings] = useState<DiagSettings | null>(null);
  const [histKey, setHistKey] = useState(0);
  useEffect(() => {
    api.schema(props.connectionId).then((s) => setTables(s.keyspaces.filter((k) => !k.system && !isSystemKeyspace(k.name))
      .flatMap((k) => k.tables.map((t) => `${k.name}.${t.name}`)).sort())).catch(() => setTables([]));
    props.client.settings().then(setSettings).catch(() => setSettings(null));
  }, [props.client, props.connectionId]);
  const keyspaces = useMemo(() => [...new Set(tables.map((t) => t.split(".")[0]))], [tables]);
  return (
    <div className="stack">
      <SettingsSection client={props.client} settings={settings} onSaved={(s) => { setSettings(s); setHistKey((k) => k + 1); }} />
      <HotSection client={props.client} nodes={props.nodes} tables={tables} />
      <HistogramsSection key={histKey} client={props.client} nodes={props.nodes} keyspaces={keyspaces} />
      <LogsSection client={props.client} nodes={props.nodes} keyspaces={keyspaces} settings={settings} />
    </div>
  );
}
