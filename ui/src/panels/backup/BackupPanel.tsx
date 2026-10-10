import { useCallback, useEffect, useMemo, useState } from "react";
import type { ClusterInfo, ConnectionConfig } from "../../lib/types";
import { JobProgress } from "../../components/JobProgress";
import { errorText, useGuarded, useToast } from "../../components/feedback";
import { ApiError } from "../../lib/api";
import {
  backupApi, filterBackups, formatBytes, formatTime, MODES, PROVIDER_LABEL,
  type BackupEntry, type BackupSettings, type Catalogue, type Detection, type Provider, type RunStatus, type Scope,
} from "./backupApi";
import "./backup.css";
import { HelpLink } from "../../components/HelpLink";

// Phase 3 Track 5: backup providers, catalogue, run now (BAK-1..3).
export function BackupPanel(props: { conn: ConnectionConfig; info: ClusterInfo; dark: boolean }) {
  const id = props.conn.id!;
  const [settings, setSettings] = useState<BackupSettings | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [catalogue, setCatalogue] = useState<Catalogue | null>(null);
  const [catError, setCatError] = useState<string | null>(null);
  const [loadingCat, setLoadingCat] = useState(false);

  const loadCatalogue = useCallback(() => {
    setLoadingCat(true);
    setCatError(null);
    backupApi.catalogue(id).then(setCatalogue)
      .catch((e) => { setCatalogue(null); setCatError(errorText(e)); })
      .finally(() => setLoadingCat(false));
  }, [id]);

  useEffect(() => {
    setSettings(null);
    setCatalogue(null);
    backupApi.settings(id).then((s) => {
      setSettings(s);
      if (s.provider) loadCatalogue();
    }).catch((e) => setError(errorText(e)));
  }, [id, loadCatalogue]);

  if (error) return <div className="panel"><div className="notice error" role="alert">{error}</div></div>;
  if (!settings) return <div className="panel muted" data-testid="backups-panel">Loading backup settings…</div>;

  return (
    <div className="panel backup-panel" data-testid="backups-panel">
      <HelpLink topic="backups" corner />
      <ProviderSetup id={id} settings={settings}
        onSaved={(s) => { setSettings(s); if (s.provider) loadCatalogue(); }} />
      {settings.provider && (
        <RunNow id={id} info={props.info} provider={settings.provider} onFinished={loadCatalogue} />
      )}
      {settings.provider && (
        <CatalogueView id={id} catalogue={catalogue} error={catError} loading={loadingCat} onRefresh={loadCatalogue} />
      )}
    </div>
  );
}

// ---- BAK-1: provider ---------------------------------------------------------------------------

function ProviderSetup(props: { id: string; settings: BackupSettings; onSaved: (s: BackupSettings) => void }) {
  const toast = useToast();
  const [draft, setDraft] = useState<BackupSettings>(props.settings);
  const [detection, setDetection] = useState<Detection | null>(null);
  const [detecting, setDetecting] = useState(false);
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => setDraft(props.settings), [props.settings]);

  const detect = () => {
    setDetecting(true);
    setErr(null);
    backupApi.detect(props.id).then((d) => {
      setDetection(d);
      if (d.recommended) {
        setDraft((s) => ({ ...s, provider: s.provider ?? d.recommended, privilege: d.recommendedPrivilege,
          scriptDir: d.recommendedScriptDir }));
      }
    }).catch((e) => setErr(errorText(e))).finally(() => setDetecting(false));
  };
  const save = () => {
    setSaving(true);
    setErr(null);
    backupApi.saveSettings(props.id, draft).then((s) => { props.onSaved(s); toast.ok("Backup settings saved"); })
      .catch((e) => setErr(errorText(e))).finally(() => setSaving(false));
  };
  const set = <K extends keyof BackupSettings>(k: K, v: BackupSettings[K]) => setDraft((s) => ({ ...s, [k]: v }));

  return (
    <details className="backup-section" open={!props.settings.provider} data-testid="backup-provider">
      <summary>
        <h3>Provider</h3>
        <span className="muted">{props.settings.provider ? PROVIDER_LABEL[props.settings.provider] : "not chosen yet"}</span>
      </summary>
      <div className="backup-row">
        <button className="btn" onClick={detect} disabled={detecting}>{detecting ? "Detecting…" : "Detect on nodes"}</button>
        <span className="muted">Looks for the estate scripts, Medusa and sudo over SSH, and JMX for snapshots.</span>
      </div>
      {detection && <DetectionView d={detection} />}
      <fieldset className="backup-form">
        <legend>Provider for this cluster</legend>
        {(Object.keys(PROVIDER_LABEL) as Provider[]).map((p) => (
          <label key={p} className="backup-radio">
            <input type="radio" name="backup-provider" checked={draft.provider === p} onChange={() => set("provider", p)} />
            {PROVIDER_LABEL[p]}{detection?.recommended === p && <span className="status ok">recommended</span>}
          </label>
        ))}
        {draft.provider === "ESTATE" && (<>
          <label>Script directory<input value={draft.scriptDir} onChange={(e) => set("scriptDir", e.target.value)} /></label>
          <label>Config file<input value={draft.configFile} onChange={(e) => set("configFile", e.target.value)} /></label>
        </>)}
        {draft.provider === "MEDUSA" && (<>
          <label>medusa command<input value={draft.medusaCommand} onChange={(e) => set("medusaCommand", e.target.value)} /></label>
          <label>medusa.ini (optional)<input value={draft.medusaConfig ?? ""}
            onChange={(e) => set("medusaConfig", e.target.value || null)} /></label>
        </>)}
        {draft.provider !== "SNAPSHOT" && draft.provider != null && (<>
          <label>Run as
            <select value={draft.privilege} onChange={(e) => set("privilege", e.target.value as BackupSettings["privilege"])}>
              <option value="SUDO">root via sudo -n (estate default)</option>
              <option value="NONE">the SSH user</option>
            </select>
          </label>
          <label>Per-node time limit (minutes)<input type="number" min={1} max={1440} value={draft.nodeTimeoutMinutes}
            onChange={(e) => set("nodeTimeoutMinutes", Number(e.target.value))} /></label>
        </>)}
        <div className="backup-row">
          <button className="btn primary" onClick={save} disabled={saving || !draft.provider}>Save</button>
        </div>
      </fieldset>
      {err && <div className="notice error" role="alert">{err}</div>}
    </details>
  );
}

function DetectionView({ d }: { d: Detection }) {
  return (
    <div data-testid="backup-detection">
      <table className="data">
        <caption className="sr-only">Detection per node</caption>
        <thead><tr><th>Node</th><th>DC</th><th>SSH</th><th>Estate scripts</th><th>config.json</th><th>Medusa</th><th>sudo -n</th></tr></thead>
        <tbody>
          {d.nodes.map((n) => (
            <tr key={n.node}>
              <td>{n.node}{n.host ? <span className="muted"> ({n.host})</span> : null}</td>
              <td>{n.datacenter ?? ""}</td>
              <td>{n.reachable ? <span className="status ok">ok</span> : <span className="status error" title={n.error ?? ""}>no</span>}</td>
              <td>{n.scripts.length ? n.scripts.length + " found" : n.reachable ? "none" : "?"}</td>
              <td>{n.configPresent ? (n.configReadable ? "readable" : "present (root only)") : n.reachable ? "missing" : "?"}</td>
              <td>{n.medusa ?? (n.reachable ? "no" : "?")}</td>
              <td>{n.reachable ? (n.sudo ? "yes" : "no") : "?"}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted">Snapshots over JMX: {d.jmxSnapshots ? "available" : "not available (" + (d.jmxError ?? "unknown") + ")"}</p>
      {d.nodes.filter((n) => !n.reachable).map((n) => (
        <div key={n.node} className="notice warn">{n.node}: {n.error}</div>
      ))}
      {d.notes.map((n) => <div key={n} className="notice info">{n}</div>)}
    </div>
  );
}

// ---- BAK-3: run now ------------------------------------------------------------------------------

function RunNow(props: { id: string; info: ClusterInfo; provider: Provider; onFinished: () => void }) {
  const guarded = useGuarded();
  const [scope, setScope] = useState<Scope>("CLUSTER");
  const [dc, setDc] = useState(props.info.datacenters[0] ?? "");
  const [node, setNode] = useState(props.info.nodes[0]?.address ?? "");
  const [mode, setMode] = useState(MODES[props.provider][0].value);
  const [concurrency, setConcurrency] = useState(1);
  const [throttle, setThrottle] = useState("");
  const [name, setName] = useState("");
  const [keyspaces, setKeyspaces] = useState("");
  const [jobId, setJobId] = useState<string | null>(null);
  const [runStatus, setRunStatus] = useState<RunStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => setMode(MODES[props.provider][0].value), [props.provider]);

  // Per-node state while the job runs (the job itself carries the overall progress and log).
  useEffect(() => {
    if (!jobId) return;
    let stop = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = () => backupApi.runStatus(props.id, jobId).then((s) => {
      if (stop) return;
      setRunStatus(s);
      const active = s.nodes.some((n) => n.state === "QUEUED" || n.state === "RUNNING");
      if (active) timer = setTimeout(tick, 1500);
    }).catch(() => { if (!stop) timer = setTimeout(tick, 3000); });
    tick();
    return () => { stop = true; if (timer) clearTimeout(timer); };
  }, [jobId, props.id]);

  const run = async () => {
    setBusy(true);
    setErr(null);
    try {
      const req = {
        scope, datacenter: scope === "DC" ? dc : null, node: scope === "NODE" ? node : null, mode, concurrency,
        throttle: props.provider === "ESTATE" && throttle.trim() ? throttle.trim() : null,
        name: props.provider !== "ESTATE" && name.trim() ? name.trim() : null,
        keyspaces: props.provider === "SNAPSHOT" ? keyspaces.split(/[\s,]+/).filter(Boolean) : [],
      };
      const job = await guarded((c) => backupApi.run(props.id, req, c));
      if (job) { setRunStatus(null); setJobId(job.id); }
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : errorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="backup-section" aria-labelledby="backup-run-title" data-testid="backup-run">
      <h3 id="backup-run-title">Run a backup now</h3>
      <div className="backup-form backup-inline">
        <label>Scope
          <select value={scope} onChange={(e) => setScope(e.target.value as Scope)}>
            <option value="CLUSTER">Whole cluster</option>
            <option value="DC">One datacenter</option>
            <option value="NODE">One node</option>
          </select>
        </label>
        {scope === "DC" && (
          <label>Datacenter
            <select value={dc} onChange={(e) => setDc(e.target.value)}>
              {props.info.datacenters.map((d) => <option key={d}>{d}</option>)}
            </select>
          </label>
        )}
        {scope === "NODE" && (
          <label>Node
            <select value={node} onChange={(e) => setNode(e.target.value)}>
              {props.info.nodes.map((n) => <option key={n.address} value={n.address}>{n.address} ({n.datacenter})</option>)}
            </select>
          </label>
        )}
        <label>Type
          <select value={mode} onChange={(e) => setMode(e.target.value)}>
            {MODES[props.provider].map((m) => <option key={m.value} value={m.value}>{m.label}</option>)}
          </select>
        </label>
        {scope !== "NODE" && (
          <label>Nodes at a time<input type="number" min={1} max={16} value={concurrency}
            onChange={(e) => setConcurrency(Math.max(1, Number(e.target.value) || 1))} /></label>
        )}
        {props.provider === "ESTATE" && (
          <label>Throttle (optional)<input placeholder="50M/s" value={throttle} onChange={(e) => setThrottle(e.target.value)} /></label>
        )}
        {props.provider === "MEDUSA" && (
          <label>Backup name (optional)<input placeholder="studio-yyyyMMdd-HHmmss" value={name} onChange={(e) => setName(e.target.value)} /></label>
        )}
        {props.provider === "SNAPSHOT" && (<>
          <label>Tag (optional)<input placeholder="studio-yyyyMMdd-HHmmss" value={name} onChange={(e) => setName(e.target.value)} /></label>
          <label>Keyspaces (optional)<input placeholder="all" value={keyspaces} onChange={(e) => setKeyspaces(e.target.value)} /></label>
        </>)}
        <button className="btn primary" onClick={run} disabled={busy}>Run backup now…</button>
      </div>
      {err && <div className="notice error" role="alert">{err}</div>}
      {jobId && <JobProgress jobId={jobId} onDone={() => { props.onFinished(); }} />}
      {runStatus && <NodeResults status={runStatus} />}
    </section>
  );
}

const STATE_CLASS: Record<string, string> = { SUCCEEDED: "ok", FAILED: "error", CANCELLED: "skipped", SKIPPED: "skipped" };

function NodeResults({ status }: { status: RunStatus }) {
  return (
    <table className="data backup-nodes" data-testid="backup-node-results">
      <caption className="sr-only">Result per node</caption>
      <thead><tr><th>Node</th><th>DC</th><th>State</th><th>Progress</th><th>Backup id</th><th>Result</th></tr></thead>
      <tbody>
        {status.nodes.map((n) => (
          <tr key={n.node}>
            <td>{n.node}</td>
            <td>{n.datacenter ?? ""}</td>
            <td><span className={"status " + (STATE_CLASS[n.state] ?? "")}>{n.state}</span></td>
            <td>{n.progress == null ? "" : Math.round(n.progress * 100) + "%"}</td>
            <td><code>{n.backupId ?? ""}</code></td>
            <td>
              <div>{n.summary ?? n.message ?? ""}</div>
              {n.command && <div className="muted backup-cmd"><code>{n.command}</code></div>}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

// ---- BAK-2: catalogue ----------------------------------------------------------------------------

function CatalogueView(props: { id: string; catalogue: Catalogue | null; error: string | null; loading: boolean; onRefresh: () => void }) {
  const guarded = useGuarded();
  const toast = useToast();
  const [filter, setFilter] = useState({ node: "", type: "", status: "" });
  const c = props.catalogue;
  const nodes = useMemo(() => [...new Set((c?.backups ?? []).map((b) => b.node ?? "cluster"))].sort(), [c]);
  const types = useMemo(() => [...new Set((c?.backups ?? []).map((b) => b.type))].sort(), [c]);
  const shown = c ? filterBackups(c.backups, filter) : [];

  const clear = async (b: BackupEntry) => {
    try {
      const r = await guarded((conf) => backupApi.clearSnapshot(props.id, b.node!, b.id, conf));
      if (r) { toast.ok("Snapshot " + b.id + " cleared on " + b.node); props.onRefresh(); }
    } catch (e) {
      toast.error(e);
    }
  };

  return (
    <section className="backup-section" aria-labelledby="backup-cat-title" data-testid="backup-catalogue">
      <div className="backup-row">
        <h3 id="backup-cat-title">Catalogue</h3>
        <button className="btn small" onClick={props.onRefresh} disabled={props.loading}>{props.loading ? "Loading…" : "Refresh"}</button>
        {c && <span className="muted">{c.backups.length} backups, listed {formatTime(c.generatedAtMs)}</span>}
      </div>
      {props.error && <div className="notice error" role="alert">{props.error}</div>}
      {c && c.nodes.filter((n) => !n.ok).map((n) => (
        <div key={n.node} className="notice warn">{n.node}: could not list backups: {n.error}</div>
      ))}
      {c && (
        <div className="backup-form backup-inline" role="group" aria-label="Filters">
          <label>Node<select value={filter.node} onChange={(e) => setFilter({ ...filter, node: e.target.value })}>
            <option value="">all</option>{nodes.map((n) => <option key={n}>{n}</option>)}</select></label>
          <label>Type<select value={filter.type} onChange={(e) => setFilter({ ...filter, type: e.target.value })}>
            <option value="">all</option>{types.map((t) => <option key={t}>{t}</option>)}</select></label>
          <label>Status<select value={filter.status} onChange={(e) => setFilter({ ...filter, status: e.target.value })}>
            <option value="">all</option><option>COMPLETE</option><option>INCOMPLETE</option><option>UNKNOWN</option></select></label>
        </div>
      )}
      {c && shown.length === 0 && <div className="empty">No backups{c.backups.length ? " match the filters" : " found"}.</div>}
      {shown.length > 0 && (
        <div className="backup-table-wrap">
          <table className="data" data-testid="backup-catalogue-table">
            <caption className="sr-only">Backups</caption>
            <thead><tr>
              <th>Time</th><th>Node</th><th>DC</th><th>Id</th><th>Type</th><th>Status</th><th>Size</th><th>Tables</th>
              <th>Schema version</th><th>Location</th><th>Retention</th><th>Object lock</th><th><span className="sr-only">Actions</span></th>
            </tr></thead>
            <tbody>
              {shown.map((b) => (
                <tr key={b.provider + b.node + b.id} title={b.notes ?? undefined}>
                  <td>{formatTime(b.timeMs)}</td>
                  <td>{b.node ?? "cluster"}{b.host && <span className="muted"> ({b.host})</span>}</td>
                  <td>{b.datacenter ?? <Unknown />}</td>
                  <td><code>{b.id}</code></td>
                  <td>{b.type}</td>
                  <td><span className={"status " + (b.status === "COMPLETE" ? "ok" : b.status === "INCOMPLETE" ? "error" : "UNKNOWN")}
                    title={b.statusDetail ?? undefined}>{b.status}</span></td>
                  <td>{b.sizeBytes == null ? <Unknown /> : formatBytes(b.sizeBytes)}</td>
                  <td>{b.tables ?? <Unknown />}</td>
                  <td>{b.schemaVersion ? <code title={b.schemaVersion}>{b.schemaVersion.slice(0, 8)}</code> : <Unknown />}</td>
                  <td className="backup-loc">{b.location ?? <Unknown />}</td>
                  <td>{b.retention ?? <Unknown />}{b.expiresAtMs != null && <div className="muted">until {formatTime(b.expiresAtMs)}</div>}</td>
                  <td>{b.objectLock ?? <Unknown />}{b.lockedUntilMs != null && <div className="muted">until {formatTime(b.lockedUntilMs)}</div>}</td>
                  <td>{b.provider === "snapshot" && b.node && (
                    <button className="btn small danger" onClick={() => clear(b)} aria-label={`Clear snapshot ${b.id} on ${b.node}`}>Clear…</button>
                  )}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {c && c.notes.map((n) => <p key={n} className="muted">{n}</p>)}
    </section>
  );
}

function Unknown() {
  return <span className="muted">unknown</span>;
}
