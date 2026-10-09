import { useEffect, useState, type FormEvent } from "react";
import type { MonitoringClient, Thresholds } from "../../lib/monitoringApi";
import { errorText } from "../../components/feedback";
import { ErrorState, Loading } from "./common";

/** Labels for the ALR-1 threshold keys; unknown keys from the engine are shown as-is. */
export const THRESHOLD_LABELS: Record<string, { label: string; unit: string }> = {
  "heap.high.yellowPct": { label: "Heap used, warning", unit: "% of max" },
  "heap.high.redPct": { label: "Heap used, critical", unit: "% of max" },
  "gc.pressure.yellowPct": { label: "GC time, warning", unit: "% of wall clock" },
  "gc.pressure.redPct": { label: "GC time, critical", unit: "% of wall clock" },
  "compaction.backlog.pending": { label: "Pending compactions", unit: "tasks" },
  "disk.usage.yellowPct": { label: "Data directory usage, warning", unit: "% full" },
  "disk.usage.redPct": { label: "Data directory usage, critical", unit: "% full" },
  "load.imbalance.factor": { label: "Load imbalance", unit: "× DC average" },
  "hints.backlog.polls": { label: "Hints in progress for", unit: "consecutive polls" },
};

/** Per-cluster overrides of the health rule thresholds (ALR-1). */
export function ThresholdsView(props: { client: MonitoringClient }) {
  const [loaded, setLoaded] = useState<Thresholds | null>(null);
  const [form, setForm] = useState<Record<string, string>>({});
  const [error, setError] = useState<unknown>(null);
  const [saveMsg, setSaveMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const [saving, setSaving] = useState(false);
  const [reload, setReload] = useState(0);
  const { client } = props;

  const apply = (t: Thresholds) => {
    setLoaded(t);
    setForm(Object.fromEntries(Object.entries(t).map(([k, v]) => [k, v === null ? "" : String(v)])));
  };

  useEffect(() => {
    let alive = true;
    setError(null);
    client.thresholds().then((t) => alive && apply(t)).catch((e) => alive && setError(e));
    return () => { alive = false; };
  }, [client, reload]);

  if (error) return <ErrorState error={error} onRetry={() => setReload((r) => r + 1)} />;
  if (!loaded) return <Loading what="thresholds" />;

  const invalid = Object.entries(form).filter(([, v]) => v.trim() !== "" && !Number.isFinite(Number(v))).map(([k]) => k);
  const dirty = Object.entries(form).some(([k, v]) => v !== (loaded[k] === null ? "" : String(loaded[k])));
  const keys = Object.keys(form).sort((a, b) => (THRESHOLD_LABELS[a] ? 0 : 1) - (THRESHOLD_LABELS[b] ? 0 : 1) || a.localeCompare(b));

  const save = (e: FormEvent) => {
    e.preventDefault();
    if (invalid.length) return;
    const body: Thresholds = Object.fromEntries(Object.entries(form).map(([k, v]) => [k, v.trim() === "" ? null : Number(v)]));
    setSaving(true);
    setSaveMsg(null);
    client.saveThresholds(body)
      .then((t) => { apply(t); setSaveMsg({ ok: true, text: "Thresholds saved. They apply from the next poll." }); })
      .catch((err) => setSaveMsg({ ok: false, text: errorText(err) }))
      .finally(() => setSaving(false));
  };

  return (
    <form className="pad stack mon-thresholds" onSubmit={save} aria-label="Health rule thresholds">
      <p className="muted" style={{ margin: 0 }}>
        Thresholds for the built-in health rules of this cluster. Values shown are the effective ones (defaults plus this cluster's overrides).
      </p>
      <div className="mon-threshold-grid">
        {keys.map((k) => {
          const meta = THRESHOLD_LABELS[k];
          const bad = invalid.includes(k);
          return (
            <label key={k} className="field">
              <span>{meta?.label ?? k} <span className="mono">({k})</span></span>
              <span className="row">
                <input
                  type="number"
                  step="any"
                  value={form[k]}
                  aria-invalid={bad}
                  onChange={(e) => setForm((f) => ({ ...f, [k]: e.target.value }))}
                  style={{ width: 110 }}
                />
                <span className="muted">{meta?.unit ?? ""}</span>
              </span>
              {bad && <span className="error-text">Enter a number</span>}
            </label>
          );
        })}
      </div>
      <div className="row">
        <button type="submit" className="btn primary" disabled={!dirty || saving || invalid.length > 0}>{saving ? "Saving…" : "Save"}</button>
        <button type="button" className="btn" disabled={!dirty || saving} onClick={() => apply(loaded)}>Discard changes</button>
        {saveMsg && <span role="status" className={saveMsg.ok ? "ok-text" : "error-text"}>{saveMsg.text}</span>}
      </div>
    </form>
  );
}
