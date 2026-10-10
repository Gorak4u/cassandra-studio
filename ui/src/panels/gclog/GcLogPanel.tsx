import { lazy, Suspense, useCallback, useEffect, useMemo, useState, type KeyboardEvent } from "react";
import type { ClusterInfo, ConnectionConfig } from "../../lib/types";
import type { Job } from "../../lib/jobsTypes";
import { errorText } from "../../components/feedback";
import { JobProgress } from "../../components/JobProgress";
import { HelpLink } from "../../components/HelpLink";
import { gclogApi } from "./gclogApi";
import { eventsCsv, exportName, fmtDuration, fmtK, fmtPause, fmtWhen } from "./gclogFormat";
import type { AnalysisInfo, Discovery, Finding, GcEvent, GcReport } from "./gclogTypes";
import "./gclog.css";

// ECharts loads with the charts view only, in its own chunk.
const GcCharts = lazy(() => import("./GcCharts"));

const DEFAULT_CAP_MB = 200;
type View = "summary" | "charts" | "events";
const VIEWS: [View, string][] = [["summary", "Summary & findings"], ["charts", "Charts"], ["events", "Events"]];

/** GC log analysis (GCL-1..4): load logs from a node over SSH or a file, then summary, charts, findings, events. */
export function GcLogPanel(props: { conn: ConnectionConfig; info: ClusterInfo; dark: boolean }) {
  const id = props.conn.id!;
  const [analyses, setAnalyses] = useState<AnalysisInfo[]>([]);
  const [report, setReport] = useState<GcReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [zoom, setZoom] = useState<{ fromX: number; toX: number } | null>(null);
  const [view, setView] = useState<View>("summary");

  const refreshList = useCallback(() => {
    gclogApi.list(id).then(setAnalyses).catch(() => setAnalyses([]));
  }, [id]);
  useEffect(refreshList, [refreshList]);

  const open = useCallback((aid: string, range?: { fromX: number; toX: number }) => {
    setLoading(true);
    setError(null);
    gclogApi.report(id, aid, range)
      .then((r) => { setReport(r); setZoom(null); })
      .catch((e) => setError(errorText(e)))
      .finally(() => setLoading(false));
  }, [id]);

  const loaded = useCallback((aid: string) => {
    refreshList();
    open(aid);
  }, [open, refreshList]);

  return (
    <div className="gcl" data-testid="gclogs-panel">
      <HelpLink topic="gclog" corner />
      <SourcePanel connId={id} info={props.info} analyses={analyses} current={report?.id ?? null} onLoaded={loaded}
        onOpen={(aid) => open(aid)}
        onDelete={(aid) => gclogApi.remove(id, aid).then(() => { if (report?.id === aid) setReport(null); refreshList(); }).catch((e) => setError(errorText(e)))} />
      {error && <div className="notice error" role="alert">{error}</div>}
      {loading && <div className="muted" role="status">Analysing…</div>}
      {report && (
        <ReportView report={report} dark={props.dark} view={view} setView={setView} zoom={zoom} setZoom={setZoom}
          onRange={(r) => open(report.id, r ?? undefined)} />
      )}
      {!report && !loading && <div className="empty">Load GC logs from a node or a file to analyse them.</div>}
    </div>
  );
}

// ---- source: node files over SSH, upload, previous analyses ------------------------------

function SourcePanel(props: {
  connId: string; info: ClusterInfo; analyses: AnalysisInfo[]; current: string | null;
  onLoaded: (aid: string) => void; onOpen: (aid: string) => void; onDelete: (aid: string) => void;
}) {
  const nodes = props.info.nodes;
  const [node, setNode] = useState(nodes[0]?.address ?? "");
  const [disc, setDisc] = useState<Discovery | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [capMB, setCapMB] = useState(DEFAULT_CAP_MB);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [jobId, setJobId] = useState<string | null>(null);

  useEffect(() => { if (!node && nodes[0]) setNode(nodes[0].address); }, [nodes, node]);

  const find = () => {
    setBusy(true);
    setErr(null);
    setDisc(null);
    gclogApi.discover(props.connId, node)
      .then((d) => { setDisc(d); setSelected(new Set(defaultSelection(d, capMB))); })
      .catch((e) => setErr(errorText(e)))
      .finally(() => setBusy(false));
  };

  const load = () => {
    setErr(null);
    gclogApi.fetch(props.connId, node, [...selected], capMB, disc?.javaVersion).then((j) => setJobId(j.id)).catch((e) => setErr(errorText(e)));
  };

  const onJobDone = (j: Job) => {
    const aid = (j.result as { analysisId?: string } | null)?.analysisId;
    if (j.state === "SUCCEEDED" && aid) props.onLoaded(aid);
  };

  const upload = (file: File | undefined) => {
    if (!file) return;
    setBusy(true);
    setErr(null);
    gclogApi.upload(props.connId, file, file.name)
      .then((a) => props.onLoaded(a.id))
      .catch((e) => setErr(errorText(e)))
      .finally(() => setBusy(false));
  };

  const total = disc ? disc.files.filter((f) => selected.has(f.path)).reduce((s, f) => s + f.sizeBytes, 0) : 0;
  return (
    <section className="panel gcl-source" aria-labelledby="gcl-source-h">
      <h3 id="gcl-source-h">GC log source</h3>
      <div className="gcl-source-row">
        <div className="gcl-source-col">
          <div className="gcl-inline">
            <label className="field">
              <span>Node</span>
              <select value={node} onChange={(e) => { setNode(e.target.value); setDisc(null); }} data-testid="gclog-node">
                {nodes.map((n) => <option key={n.address} value={n.address}>{n.address}{n.datacenter ? ` (${n.datacenter})` : ""}</option>)}
              </select>
            </label>
            <button className="btn" onClick={find} disabled={!node || busy} data-testid="gclog-find">Find GC logs</button>
          </div>
          {disc && <DiscoveryView disc={disc} selected={selected} setSelected={setSelected} />}
          {disc && disc.files.length > 0 && (
            <div className="gcl-inline">
              <label className="field">
                <span>Size cap (MB)</span>
                <input type="number" min={1} max={1024} value={capMB} onChange={(e) => setCapMB(Math.max(1, Math.min(1024, Number(e.target.value) || DEFAULT_CAP_MB)))} />
              </label>
              <button className="btn primary" onClick={load} disabled={selected.size === 0} data-testid="gclog-load">
                Load {selected.size} file{selected.size === 1 ? "" : "s"} ({fmtK(total / 1024)})
              </button>
              {total > capMB * 1024 * 1024 && <span className="muted">Over the cap: the newest {capMB} MB are read.</span>}
            </div>
          )}
          {jobId && <JobProgress jobId={jobId} onDone={onJobDone} />}
        </div>
        <div className="gcl-source-col">
          <label className="field">
            <span>Or upload a GC log (.log, .gz or .zip of rotated files)</span>
            <input type="file" data-testid="gclog-upload" disabled={busy}
              onChange={(e) => { upload(e.target.files?.[0]); e.target.value = ""; }} />
          </label>
          {props.analyses.length > 0 && (
            <div className="gcl-previous">
              <span className="muted">Loaded logs</span>
              <ul>
                {props.analyses.map((a) => (
                  <li key={a.id} className={a.id === props.current ? "current" : ""}>
                    <button className="btn link" onClick={() => props.onOpen(a.id)} aria-current={a.id === props.current ? "true" : undefined}>
                      {a.name}
                    </button>
                    <span className="muted">{a.collector ?? "unknown collector"}, {a.events} events, {new Date(a.createdAtMs).toLocaleTimeString()}</span>
                    <button className="btn link" aria-label={`Remove ${a.name}`} onClick={() => props.onDelete(a.id)}>×</button>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </div>
      {err && <div className="notice error" role="alert">{err}</div>}
    </section>
  );
}

/** Newest files first while they fit under the cap. */
export function defaultSelection(d: Discovery, capMB: number): string[] {
  const out: string[] = [];
  let sum = 0;
  for (const f of d.files) {
    if (out.length > 0 && sum + f.sizeBytes > capMB * 1024 * 1024) break;
    out.push(f.path);
    sum += f.sizeBytes;
  }
  return out;
}

function DiscoveryView(props: { disc: Discovery; selected: Set<string>; setSelected: (s: Set<string>) => void }) {
  const { disc, selected } = props;
  const toggle = (p: string) => {
    const s = new Set(selected);
    if (s.has(p)) s.delete(p); else s.add(p);
    props.setSelected(s);
  };
  return (
    <div className="gcl-discovery" data-testid="gclog-discovery">
      <div className="muted">
        {disc.javaVersion ? `Java ${disc.javaVersion}. ` : ""}
        {disc.configuredPath ? <>Log file from the JVM options: <code>{disc.configuredPath}</code></> : "No GC log option found; searched the usual paths."}
      </div>
      {disc.gcOptions.length > 0 && (
        <details><summary>GC options ({disc.gcOptions.length})</summary><ul className="gcl-mono">{disc.gcOptions.map((o) => <li key={o}>{o}</li>)}</ul></details>
      )}
      {disc.note && <div className="notice warn">{disc.note}</div>}
      {disc.files.length > 0 && (
        <table className="data gcl-files">
          <thead><tr><th scope="col">Load</th><th scope="col">File</th><th scope="col" className="num">Size</th><th scope="col">Modified</th></tr></thead>
          <tbody>
            {disc.files.map((f) => (
              <tr key={f.path}>
                <td><input type="checkbox" checked={selected.has(f.path)} onChange={() => toggle(f.path)} aria-label={`Load ${f.path}`} /></td>
                <td className="gcl-mono">{f.path} {f.current && <span className="status ok">current</span>}</td>
                <td className="num">{fmtK(f.sizeBytes / 1024)}</td>
                <td>{new Date(f.modifiedMs).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

// ---- the report ------------------------------------------------------------------------

function ReportView(props: {
  report: GcReport; dark: boolean; view: View; setView: (v: View) => void;
  zoom: { fromX: number; toX: number } | null; setZoom: (z: { fromX: number; toX: number } | null) => void;
  onRange: (r: { fromX: number; toX: number } | null) => void;
}) {
  const { report: r, view } = props;
  const s = r.summary;
  const onTabKey = (e: KeyboardEvent) => {
    const i = VIEWS.findIndex(([k]) => k === view);
    const next = e.key === "ArrowRight" ? i + 1 : e.key === "ArrowLeft" ? i - 1 : e.key === "Home" ? 0 : e.key === "End" ? VIEWS.length - 1 : null;
    if (next === null) return;
    e.preventDefault();
    const k = VIEWS[(next + VIEWS.length) % VIEWS.length][0];
    props.setView(k);
    document.getElementById(`gcl-tab-${k}`)?.focus();
  };
  const java = r.log.javaMajor ? `Java ${r.log.javaMajor}` : r.log.format === "unified" ? "Java 9+" : "";
  return (
    <section className="gcl-report" aria-labelledby="gcl-report-h" data-testid="gclog-report">
      <div className="gcl-report-head">
        <h3 id="gcl-report-h">{r.name}</h3>
        <span className="muted">
          {[r.log.collector ?? "unknown collector", java, r.log.format === "java8" ? "Java 8 format" : r.log.format === "unified" ? "unified logging" : r.log.format,
            fmtDuration(s.durationSec) + (r.range.whole ? "" : " selected"), `${r.log.eventCount} events`].filter(Boolean).join(" · ")}
        </span>
        <span className="gcl-spacer" />
        {props.zoom && (
          <button className="btn small" onClick={() => props.onRange(props.zoom)} data-testid="gclog-apply-range">
            Analyse {fmtWhen(r.log, props.zoom.fromX)} – {fmtWhen(r.log, props.zoom.toX)}
          </button>
        )}
        {!r.range.whole && <button className="btn small" onClick={() => props.onRange(null)}>Whole log</button>}
        <button className="btn small" onClick={() => download(exportName(r, "json"), JSON.stringify(r, null, 2), "application/json")}>Export JSON</button>
        <button className="btn small" onClick={() => download(exportName(r, "csv"), eventsCsv(r), "text/csv")}>Export CSV</button>
      </div>
      {r.log.warnings.map((w) => <div key={w} className="notice warn">{w}</div>)}
      {r.source.files.some((f) => f.truncated) && <div className="notice info">Only the newest part of the largest file was read (size cap).</div>}
      <div className="gcl-tabs" role="tablist" aria-label="GC report views" onKeyDown={onTabKey}>
        {VIEWS.map(([k, label]) => (
          <button key={k} id={`gcl-tab-${k}`} role="tab" aria-selected={view === k} aria-controls="gcl-view" tabIndex={view === k ? 0 : -1}
            className={"gcl-tab" + (view === k ? " active" : "")} onClick={() => props.setView(k)}>
            {label}
            {k === "summary" && r.findings.some((f) => f.severity === "critical") && <span className="gcl-dot critical" aria-label="critical findings" />}
          </button>
        ))}
      </div>
      <div id="gcl-view" role="tabpanel" aria-labelledby={`gcl-tab-${view}`} className="gcl-view">
        {view === "summary" && <SummaryView report={r} />}
        {view === "charts" && (
          <Suspense fallback={<div className="muted" role="status">Loading charts…</div>}>
            <div className="muted gcl-hint">Zoom with the slider or the mouse wheel; the zoomed window can be analysed on its own.</div>
            <GcCharts report={r} dark={props.dark} onZoom={props.setZoom} />
          </Suspense>
        )}
        {view === "events" && <EventsView report={r} />}
      </div>
    </section>
  );
}

function download(name: string, text: string, type: string) {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function Card(props: { label: string; value: string; sub?: string; tone?: "bad" | "warn"; testId?: string }) {
  return (
    <div className={"card gcl-card" + (props.tone ? " " + props.tone : "")} data-testid={props.testId}>
      <div className="label">{props.label}</div>
      <div className="value">{props.value}</div>
      {props.sub && <div className="muted gcl-card-sub">{props.sub}</div>}
    </div>
  );
}

function SummaryView({ report: r }: { report: GcReport }) {
  const s = r.summary;
  const failures = s.toSpaceExhausted + s.evacuationFailures + s.concurrentModeFailures + s.promotionFailures + s.degenerated;
  const rate = (v?: number) => (v === undefined || v === null ? "n/a" : v.toFixed(v >= 100 ? 0 : 1) + " MB/s");
  return (
    <div className="gcl-summary">
      <div className="cards" data-testid="gclog-summary">
        <Card label="Pauses" value={String(s.pauses.count)} sub={`${fmtPause(s.pauses.totalMs)} in total`} testId="gclog-card-pauses" />
        <Card label="Avg / p99 pause" value={`${fmtPause(s.pauses.avgMs)} / ${fmtPause(s.pauses.p99Ms)}`} sub={`p95 ${fmtPause(s.pauses.p95Ms)}`} />
        <Card label="Max pause" value={fmtPause(s.pauses.maxMs)} tone={s.pauses.maxMs >= 2000 ? "bad" : s.pauses.maxMs > 500 ? "warn" : undefined} />
        <Card label="Time in GC" value={s.gcTimePct.toFixed(2) + " %"} sub={`throughput ${s.throughputPct.toFixed(2)} %`}
          tone={s.gcTimePct >= 10 ? "bad" : s.gcTimePct >= 5 ? "warn" : undefined} />
        <Card label="Full GCs" value={String(s.fullGcCount)} sub={s.fullGcCauses.map((c) => `${c.name} ×${c.count}`).join(", ") || undefined}
          tone={s.fullGcCount > 0 ? "warn" : undefined} />
        <Card label="Heap after GC (peak / max)" value={`${fmtK(s.heap.peakAfterK)} / ${fmtK(s.heap.maxK)}`} sub={`average ${fmtK(s.heap.avgAfterK)}`} />
        <Card label="Allocation rate" value={rate(s.allocation.avgMBs)} sub={`peak ${rate(s.allocation.peakMBs)}`} />
        <Card label="Promotion rate" value={rate(s.promotion.avgMBs)} sub={s.promotion.totalMB !== undefined ? `${s.promotion.totalMB.toFixed(0)} MB promoted` : undefined} />
        <Card label="Safepoints" value={s.safepoints.count ? fmtPause(s.safepoints.totalMs) : "not logged"}
          sub={s.safepoints.count ? `${s.safepoints.count} stops, max ${fmtPause(s.safepoints.maxMs)}` : "add -Xlog:safepoint"} />
        <Card label="Failures" value={String(failures)} tone={failures > 0 ? "bad" : undefined}
          sub={[s.toSpaceExhausted && `to-space exhausted ${s.toSpaceExhausted}`, s.evacuationFailures && `evacuation ${s.evacuationFailures}`,
            s.concurrentModeFailures && `concurrent mode ${s.concurrentModeFailures}`, s.promotionFailures && `promotion ${s.promotionFailures}`,
            s.degenerated && `degenerated ${s.degenerated}`].filter(Boolean).join(", ") || "none"} />
        {s.humongousAllocations > 0 && <Card label="Humongous allocations" value={String(s.humongousAllocations)} sub={`peak ${fmtK(s.humongousPeakK)}`} />}
        {s.stalls.count > 0 && <Card label="Allocation stalls" value={String(s.stalls.count)} sub={`${fmtPause(s.stalls.totalMs)}, max ${fmtPause(s.stalls.maxMs)}`} tone="warn" />}
      </div>
      <Findings findings={r.findings} />
      <div className="gcl-tables">
        <TypeTable title="Pauses by type" rows={s.byType} testId="gclog-bytype" />
        {s.concurrent.length > 0 && <TypeTable title="Concurrent phases" rows={s.concurrent} />}
        {s.safepoints.reasons.length > 0 && <TypeTable title="Safepoint operations" rows={s.safepoints.reasons} />}
        <section className="panel">
          <h3>Pause histogram</h3>
          <table className="data gcl-small">
            <thead><tr><th scope="col">Duration</th><th scope="col" className="num">Pauses</th></tr></thead>
            <tbody>{s.histogram.filter((b) => b.count > 0).map((b) => <tr key={b.label}><td>{b.label}</td><td className="num">{b.count}</td></tr>)}</tbody>
          </table>
        </section>
      </div>
    </div>
  );
}

function Findings({ findings }: { findings: Finding[] }) {
  return (
    <section className="gcl-findings" aria-labelledby="gcl-findings-h" data-testid="gclog-findings">
      <h3 id="gcl-findings-h">Findings and tuning hints</h3>
      <ul>
        {findings.map((f) => (
          <li key={f.id} className={"gcl-finding " + f.severity} data-testid={`gclog-finding-${f.id}`}>
            <div className="gcl-finding-head">
              <span className={"gcl-sev " + f.severity}>{f.severity}</span>
              <b>{f.title}</b>
            </div>
            <div>{f.detail}</div>
            {f.evidence.length > 0 && <ul className="gcl-evidence">{f.evidence.map((e) => <li key={e}>{e}</li>)}</ul>}
            <div className="gcl-hint-text"><span className="muted">Hint: </span>{f.hint}</div>
            {(f.options.length > 0 || f.file) && (
              <div className="muted gcl-small">
                {f.options.length > 0 && <>Options: {f.options.map((o) => <code key={o} className="gcl-opt">{o}</code>)}</>}
                {f.file && <> in <code>{f.file}</code></>}
              </div>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

function TypeTable(props: { title: string; rows: { type: string; count: number; totalMs: number; avgMs: number; maxMs: number }[]; testId?: string }) {
  return (
    <section className="panel" data-testid={props.testId}>
      <h3>{props.title}</h3>
      <table className="data gcl-small">
        <thead><tr><th scope="col">Type</th><th scope="col" className="num">Count</th><th scope="col" className="num">Total</th><th scope="col" className="num">Avg</th><th scope="col" className="num">Max</th></tr></thead>
        <tbody>
          {props.rows.map((t) => (
            <tr key={t.type}><td>{t.type}</td><td className="num">{t.count}</td><td className="num">{fmtPause(t.totalMs)}</td><td className="num">{fmtPause(t.avgMs)}</td><td className="num">{fmtPause(t.maxMs)}</td></tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

type EventFilter = "all" | "pauses" | "full" | "long" | "flagged" | "concurrent";
const FILTERS: [EventFilter, string][] = [["pauses", "Pauses"], ["all", "All events"], ["full", "Full GCs"], ["long", "Pauses over 500 ms"], ["flagged", "With failures/flags"], ["concurrent", "Concurrent phases"]];
const PAGE = 200;

export function filterEvents(events: GcEvent[], f: EventFilter): GcEvent[] {
  switch (f) {
    case "pauses": return events.filter((e) => e.kind === "pause");
    case "full": return events.filter((e) => e.category === "full");
    case "long": return events.filter((e) => e.kind === "pause" && e.durationMs > 500);
    case "flagged": return events.filter((e) => e.flags && e.flags.length > 0);
    case "concurrent": return events.filter((e) => e.kind === "concurrent");
    default: return events;
  }
}

function EventsView({ report: r }: { report: GcReport }) {
  const [filter, setFilter] = useState<EventFilter>("pauses");
  const [limit, setLimit] = useState(PAGE);
  const rows = useMemo(() => filterEvents(r.events, filter), [r.events, filter]);
  return (
    <div className="gcl-events">
      <div className="gcl-inline">
        <label className="field">
          <span>Show</span>
          <select value={filter} onChange={(e) => { setFilter(e.target.value as EventFilter); setLimit(PAGE); }} data-testid="gclog-event-filter">
            {FILTERS.map(([k, l]) => <option key={k} value={k}>{l}</option>)}
          </select>
        </label>
        <span className="muted">{rows.length} event{rows.length === 1 ? "" : "s"}{r.eventsTruncated ? " (sampled: the log has more; the summary uses all)" : ""}</span>
      </div>
      <div className="gcl-table-wrap" tabIndex={0} role="region" aria-label="GC events">
        <table className="data gcl-small" data-testid="gclog-events">
          <thead>
            <tr>
              <th scope="col">Time</th><th scope="col">Type</th><th scope="col">Cause</th><th scope="col" className="num">Duration</th>
              <th scope="col" className="num">Heap before → after (size)</th><th scope="col" className="num">Young after</th>
              <th scope="col" className="num">Old after</th><th scope="col" className="num">Metaspace</th><th scope="col">Flags</th>
            </tr>
          </thead>
          <tbody>
            {rows.slice(0, limit).map((e, i) => (
              <tr key={i} className={e.category === "full" || (e.flags?.length ?? 0) > 0 ? "gcl-row-bad" : e.durationMs > 500 ? "gcl-row-warn" : undefined}>
                <td className="gcl-mono">{fmtWhen(r.log, e.x)}</td>
                <td>{e.type}</td>
                <td>{e.cause ?? ""}</td>
                <td className="num">{fmtPause(e.durationMs)}</td>
                <td className="num">{e.heapAfterK !== undefined ? `${fmtK(e.heapBeforeK)} → ${fmtK(e.heapAfterK)} (${fmtK(e.heapTotalK)})` : ""}</td>
                <td className="num">{e.youngAfterK !== undefined ? fmtK(e.youngAfterK) : ""}</td>
                <td className="num">{e.oldAfterK !== undefined ? fmtK(e.oldAfterK) : ""}</td>
                <td className="num">{e.metaAfterK !== undefined ? fmtK(e.metaAfterK) : ""}</td>
                <td>{(e.flags ?? []).join(", ")}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {rows.length > limit && <button className="btn small" onClick={() => setLimit(limit + PAGE * 5)}>Show more ({rows.length - limit} left)</button>}
    </div>
  );
}
