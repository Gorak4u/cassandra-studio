import { useCallback, useEffect, useMemo, useState } from "react";
import type { ClusterInfo, ConnectionConfig } from "../../lib/types";
import { ApiError } from "../../lib/api";
import { download, toCsv } from "../../lib/export";
import { JobProgress } from "../../components/JobProgress";
import { errorText } from "../../components/feedback";
import type { Job } from "../../lib/jobsTypes";
import {
  CATEGORY_LABEL, HIERA_FACTS, configApi, driftCsvRows, expectedText, settingsGrid, statusText,
  type Category, type DriftReport, type HieraOptions, type HieraSettings, type Snapshot,
} from "./configTypes";
import "./config.css";

type View = "settings" | "drift" | "hiera";

/** Effective config per node and drift (CFG-1/2). */
export function ConfigPanel(props: { conn: ConnectionConfig; info: ClusterInfo; dark: boolean }) {
  const id = props.conn.id ?? "";
  const [view, setView] = useState<View>("settings");
  const [snap, setSnap] = useState<Snapshot | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [jobId, setJobId] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  const [version, setVersion] = useState(0);

  const loadSnapshot = useCallback(() => {
    configApi.snapshot(id).then((s) => { setSnap(s); setLoadError(null); })
      .catch((e) => { if (e instanceof ApiError && e.code === "not_collected") setSnap(null); else setLoadError(errorText(e)); });
  }, [id]);

  useEffect(() => { setSnap(null); setJobId(null); loadSnapshot(); }, [loadSnapshot]);

  const collect = () => {
    setStarting(true);
    configApi.collect(id).then((j) => setJobId(j.id)).catch((e) => setLoadError(errorText(e))).finally(() => setStarting(false));
  };
  const onDone = (j: Job) => {
    if (j.state === "SUCCEEDED") { loadSnapshot(); setVersion((v) => v + 1); }
  };

  const tabs: { key: View; label: string }[] = [
    { key: "settings", label: "Settings per node" }, { key: "drift", label: "Drift report" }, { key: "hiera", label: "Hiera comparison" },
  ];
  return (
    <div className="cfg-panel" data-testid="config-panel">
      <div className="row cfg-toolbar">
        <button className="btn primary small" onClick={collect} disabled={starting} data-testid="config-collect">
          {snap ? "Collect again" : "Collect config"}
        </button>
        <span className="muted" data-testid="config-collected">
          {snap ? `Collected ${new Date(snap.collectedAtMs).toLocaleString()} from ${snap.nodes.length} node${snap.nodes.length === 1 ? "" : "s"}` : "Not collected yet"}
        </span>
        <span className="spacer" />
        <div className="tabs cfg-tabs" role="tablist" aria-label="Config views">
          {tabs.map((t) => (
            <button key={t.key} role="tab" aria-selected={view === t.key} className={"tab" + (view === t.key ? " active" : "")}
              onClick={() => setView(t.key)}>{t.label}</button>
          ))}
        </div>
      </div>
      {jobId && <div className="cfg-job"><JobProgress jobId={jobId} onDone={onDone} /></div>}
      {loadError && <div className="notice error" role="alert">{loadError}</div>}
      <div className="cfg-body" role="tabpanel">
        {view === "hiera" ? <HieraView id={id} snap={snap} />
          : !snap ? (
            <div className="empty">
              Collect reads cassandra.yaml (system_views.settings on 4.0+, the file over SSH on 3.x), JVM flags over JMX and OS limits over SSH from every node.
            </div>
          ) : view === "settings" ? <SettingsView snap={snap} /> : <DriftView id={id} snap={snap} version={version} />}
      </div>
    </div>
  );
}

function CategoryFilter(props: { value: Category | ""; onChange: (c: Category | "") => void }) {
  return (
    <label className="row">
      <span className="muted">Category</span>
      <select value={props.value} onChange={(e) => props.onChange(e.target.value as Category | "")} aria-label="Category">
        <option value="">All</option>
        {(Object.keys(CATEGORY_LABEL) as Category[]).map((c) => <option key={c} value={c}>{CATEGORY_LABEL[c]}</option>)}
      </select>
    </label>
  );
}

function Notices(props: { snap: Snapshot }) {
  const withNotices = props.snap.nodes.filter((n) => n.notices.length);
  if (!withNotices.length) return null;
  return (
    <details className="notice warn cfg-notices" data-testid="config-notices">
      <summary>{withNotices.length} node{withNotices.length === 1 ? "" : "s"} could not be read completely</summary>
      <ul>
        {withNotices.map((n) => n.notices.map((x, i) => <li key={n.address + i}><b>{n.address}</b>: {x}</li>))}
      </ul>
    </details>
  );
}

function SettingsView(props: { snap: Snapshot }) {
  const { snap } = props;
  const [search, setSearch] = useState("");
  const [category, setCategory] = useState<Category | "">("");
  const [onlyDiff, setOnlyDiff] = useState(false);
  const grid = useMemo(() => settingsGrid(snap), [snap]);
  const q = search.trim().toLowerCase();
  const shown = grid.filter((r) => (!category || r.category === category) && (!onlyDiff || r.differs)
    && (!q || r.name.toLowerCase().includes(q) || Object.values(r.values).some((s) => (s?.value ?? "").toLowerCase().includes(q))));
  const exportCsv = () => {
    const cols = ["category", "setting", ...snap.nodes.map((n) => n.address), "differs"];
    const rows = shown.map((r) => [r.category, r.name, ...snap.nodes.map((n) => r.values[n.address]?.value ?? null), r.differs ? "yes" : ""]);
    download("config-settings.csv", toCsv(cols.map((name) => ({ name, type: "text" })), rows), "text/csv");
  };
  return (
    <div className="stack">
      <div className="row cfg-filters">
        <input type="search" placeholder="Search settings or values" value={search} onChange={(e) => setSearch(e.target.value)}
          aria-label="Search settings" />
        <CategoryFilter value={category} onChange={setCategory} />
        <label className="row"><input type="checkbox" checked={onlyDiff} onChange={(e) => setOnlyDiff(e.target.checked)} /> Only differences</label>
        <span className="muted">{shown.length} of {grid.length}</span>
        <span className="spacer" />
        <button className="btn small" onClick={exportCsv} disabled={!shown.length}>Export CSV</button>
      </div>
      <Notices snap={snap} />
      <div className="cfg-scroll">
        <table className="cfg-table" data-testid="config-settings-table" aria-label="Settings per node">
          <thead>
            <tr>
              <th scope="col">Setting</th>
              {snap.nodes.map((n) => (
                <th scope="col" key={n.address} title={Object.entries(n.sources).map(([k, v]) => `${k}: ${v}`).join("\n")}>
                  {n.address}<div className="muted cfg-sub">{n.datacenter} · {n.version}</div>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {shown.map((r) => (
              <tr key={r.key} className={r.differs ? "cfg-diff" : undefined}>
                <th scope="row"><span className={"cfg-cat cfg-cat-" + r.category}>{CATEGORY_LABEL[r.category]}</span> {r.name}</th>
                {snap.nodes.map((n) => {
                  const s = r.values[n.address];
                  return (
                    <td key={n.address} title={s ? `${s.rawName} = ${s.raw ?? "null"}\nsource: ${s.source}` : "not reported"}>
                      {s ? (s.value ?? <span className="muted">null</span>) : <span className="muted">—</span>}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
        {!shown.length && <div className="empty">No settings match.</div>}
      </div>
    </div>
  );
}

function DriftView(props: { id: string; snap: Snapshot; version: number }) {
  const [scope, setScope] = useState<"cluster" | "dc">("cluster");
  const [onlyDiff, setOnlyDiff] = useState(true);
  const [hiera, setHiera] = useState(true);
  const [category, setCategory] = useState<Category | "">("");
  const [report, setReport] = useState<DriftReport | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    setError(null);
    configApi.drift(props.id, scope, onlyDiff, hiera).then((r) => alive && setReport(r)).catch((e) => alive && setError(errorText(e)));
    return () => { alive = false; };
  }, [props.id, scope, onlyDiff, hiera, props.version, props.snap]);

  if (error) return <div className="notice error" role="alert">{error}</div>;
  if (!report) return <div className="muted cfg-pad">Computing drift…</div>;
  const rows = report.rows.filter((r) => !category || r.category === category);
  const showExpected = report.hiera.enabled && !report.hiera.error;
  const exportCsv = () => {
    const { columns, rows: data } = driftCsvRows({ ...report, rows });
    download(`config-drift-${scope}.csv`, toCsv(columns.map((name) => ({ name, type: "text" })), data), "text/csv");
  };
  const s = report.summary;
  return (
    <div className="stack">
      <div className="row cfg-filters">
        <fieldset className="row cfg-scope">
          <legend className="muted">Scope</legend>
          <label className="row"><input type="radio" name="cfg-scope" checked={scope === "cluster"} onChange={() => setScope("cluster")} /> Cluster</label>
          <label className="row"><input type="radio" name="cfg-scope" checked={scope === "dc"} onChange={() => setScope("dc")} /> Within each DC</label>
        </fieldset>
        <label className="row"><input type="checkbox" checked={onlyDiff} onChange={(e) => setOnlyDiff(e.target.checked)} /> Only differences</label>
        <label className="row"><input type="checkbox" checked={hiera} onChange={(e) => setHiera(e.target.checked)} /> Compare with Hiera</label>
        <CategoryFilter value={category} onChange={setCategory} />
        <span className="spacer" />
        <button className="btn small" onClick={exportCsv} disabled={!rows.length}>Export CSV</button>
      </div>
      <div className="row cfg-summary" data-testid="config-drift-summary">
        <span className={"status " + (s.differInCluster ? "skipped" : "ok")}>{s.differInCluster} differ in cluster</span>
        <span className={"status " + (s.differInDc ? "skipped" : "ok")}>{s.differInDc} differ within a DC</span>
        {showExpected && <span className={"status " + (s.hieraMismatches ? "error" : "ok")}>{s.hieraMismatches} of {s.hieraCompared} not as in Hiera</span>}
        <span className="muted">{s.settings} settings compared</span>
      </div>
      {hiera && report.hiera.enabled && report.hiera.error && <div className="notice warn" role="status">Hiera comparison unavailable: {report.hiera.error}</div>}
      {hiera && !report.hiera.enabled && <div className="notice info">Hiera comparison is off: set it up under Hiera comparison.</div>}
      <Notices snap={props.snap} />
      <div className="cfg-scroll">
        <table className="cfg-table" data-testid="config-drift-table" aria-label="Drift report">
          <thead>
            <tr>
              <th scope="col">Setting</th>
              {report.nodes.map((n) => <th scope="col" key={n.address}>{n.address}<div className="muted cfg-sub">{n.datacenter} · {n.version}</div></th>)}
              {showExpected && <th scope="col">Expected (Hiera)</th>}
              <th scope="col">Status</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => {
              const counts = new Map<string, number>();
              Object.values(r.values).forEach((v) => counts.set(v ?? "", (counts.get(v ?? "") ?? 0) + 1));
              const majority = [...counts.entries()].sort((a, b) => b[1] - a[1])[0]?.[0];
              return (
                <tr key={r.category + r.name} className={r.differsInCluster || r.mismatches.length ? "cfg-diff" : undefined}>
                  <th scope="row"><span className={"cfg-cat cfg-cat-" + r.category}>{CATEGORY_LABEL[r.category]}</span> {r.name}</th>
                  {report.nodes.map((n) => {
                    const missing = r.missingOn.includes(n.address);
                    const v = r.values[n.address];
                    const odd = !r.perNode && r.differsInCluster && (v ?? "") !== majority;
                    const off = r.mismatches.includes(n.address);
                    return (
                      <td key={n.address} className={(odd ? "cfg-odd " : "") + (off ? "cfg-off" : "")}
                        title={r.expected?.[n.address] !== undefined ? `expected ${r.expected[n.address]} from ${r.expectedSource?.[n.address]}` : undefined}>
                        {missing ? <span className="muted">not reported</span> : v ?? <span className="muted">null</span>}
                        {off && <span className="sr-only"> (not as in Hiera)</span>}
                      </td>
                    );
                  })}
                  {showExpected && <td title={r.expectedSource ? [...new Set(Object.values(r.expectedSource))].join("\n") : undefined}>{expectedText(r)}</td>}
                  <td className="cfg-status">{statusText(r)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {!rows.length && <div className="empty" data-testid="config-drift-empty">No differences{onlyDiff ? "" : " found"}.</div>}
      </div>
    </div>
  );
}

function HieraView(props: { id: string; snap: Snapshot | null }) {
  const [s, setS] = useState<HieraSettings | null>(null);
  const [opts, setOpts] = useState<HieraOptions | null>(null);
  const [msg, setMsg] = useState<{ kind: "ok" | "error"; text: string } | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    configApi.hiera(props.id).then((h) => { setS(h); return configApi.hieraOptions(props.id, h.repoPath); }).then(setOpts)
      .catch((e) => setMsg({ kind: "error", text: errorText(e) }));
  }, [props.id]);

  if (!s) return msg ? <div className="notice error" role="alert">{msg.text}</div> : <div className="muted cfg-pad">Loading…</div>;
  const setFact = (k: string, v: string) => setS({ ...s, facts: { ...s.facts, [k]: v } });
  const setCert = (a: string, v: string) => setS({ ...s, certnames: { ...s.certnames, [a]: v } });
  const save = () => {
    setSaving(true);
    setMsg(null);
    configApi.saveHiera(props.id, s).then((h) => { setS(h); setMsg({ kind: "ok", text: "Saved. The drift report now compares with these facts." }); })
      .catch((e) => setMsg({ kind: "error", text: errorText(e) })).finally(() => setSaving(false));
  };
  const rescan = () => configApi.hieraOptions(props.id, s.repoPath).then(setOpts).catch((e) => setMsg({ kind: "error", text: errorText(e) }));
  return (
    <div className="stack cfg-hiera" data-testid="config-hiera">
      <p className="muted">
        Expected cassandra.yaml values come from the Puppet control repo: the hierarchy in hiera.yaml is resolved with these facts,
        and Hiera keys are mapped to settings through the module's cassandra.yaml.erb template (profile defaults included). Encrypted data is never read.
      </p>
      <label className="row"><input type="checkbox" checked={s.enabled} onChange={(e) => setS({ ...s, enabled: e.target.checked })} /> Compare the drift report with Hiera</label>
      <div className="row">
        <label className="field cfg-grow"><span>Control repo (local checkout)</span>
          <input value={s.repoPath} onChange={(e) => setS({ ...s, repoPath: e.target.value })} onBlur={rescan} spellCheck={false} />
        </label>
        <button className="btn small" onClick={rescan}>Scan</button>
      </div>
      {opts && !opts.found && <div className="notice warn" role="status">{opts.error}</div>}
      <div className="grid3">
        {HIERA_FACTS.map((f) => {
          const listId = "cfg-fact-" + f.key.replace(/\./g, "-");
          return (
            <label className="field" key={f.key}><span>{f.label}{f.hint ? ` (${f.hint})` : ""}</span>
              <input list={listId} value={s.facts[f.key] ?? ""} onChange={(e) => setFact(f.key, e.target.value)} spellCheck={false} />
              <datalist id={listId}>{(opts?.values[f.key] ?? []).map((v) => <option key={v} value={v} />)}</datalist>
            </label>
          );
        })}
      </div>
      {props.snap && (
        <fieldset className="cfg-certs">
          <legend>Node certnames (for the single-node layer)</legend>
          <div className="grid3">
            {props.snap.nodes.map((n) => (
              <label className="field" key={n.address}><span>{n.address} ({n.datacenter})</span>
                <input list="cfg-fact-certname" value={s.certnames[n.address] ?? ""} onChange={(e) => setCert(n.address, e.target.value)} spellCheck={false} />
              </label>
            ))}
          </div>
          <datalist id="cfg-fact-certname">{(opts?.values.certname ?? []).map((v) => <option key={v} value={v} />)}</datalist>
        </fieldset>
      )}
      <div className="row">
        <button className="btn primary small" onClick={save} disabled={saving}>Save</button>
        {msg && <span className={msg.kind === "ok" ? "ok-text" : "error-text"} role={msg.kind === "ok" ? "status" : "alert"}>{msg.text}</span>}
      </div>
    </div>
  );
}
