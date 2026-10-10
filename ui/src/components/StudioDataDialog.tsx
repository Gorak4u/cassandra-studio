import { useState } from "react";
import { Modal } from "./Modal";
import { errorText, useToast } from "./feedback";
import { download } from "../lib/export";
import { diagnosticsText, studioApi, type BackupPreview, type Conflict } from "../lib/studioApi";

/** UI errors of this window, newest last (filled by App's error handlers, read by "Copy diagnostics"). */
export const uiErrors: string[] = [];

/**
 * Studio's own data (NFR-DATA, NFR-OBS): back up and restore folders, connections, saved scripts
 * and settings in one file (passwords only encrypted with a passphrase), and copy diagnostics for
 * a support ticket. Nothing is sent anywhere.
 */
export function StudioDataDialog(props: { onClose: () => void; onRestored: () => void }) {
  const toast = useToast();
  const [withSecrets, setWithSecrets] = useState(false);
  const [passphrase, setPassphrase] = useState("");
  const [busy, setBusy] = useState(false);
  const [restoreFile, setRestoreFile] = useState<{ name: string; data: unknown } | null>(null);
  const [preview, setPreview] = useState<BackupPreview | null>(null);
  const [conflict, setConflict] = useState<Conflict>("skip");
  const [restorePass, setRestorePass] = useState("");
  const [error, setError] = useState<string | null>(null);

  const backup = async () => {
    setError(null);
    if (withSecrets && passphrase.length < 8) { setError("The passphrase must have at least 8 characters."); return; }
    setBusy(true);
    try {
      const file = await studioApi.backup(withSecrets ? passphrase : null);
      download(`cassandra-studio-settings-${new Date().toISOString().slice(0, 10)}.json`, JSON.stringify(file, null, 2), "application/json");
      toast.ok(withSecrets ? "Backup saved, with passwords encrypted by your passphrase." : "Backup saved (without passwords).");
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const pick = async (f: File) => {
    setError(null);
    setPreview(null);
    try {
      const data = JSON.parse(await f.text());
      setRestoreFile({ name: f.name, data });
      setPreview(await studioApi.previewRestore(data));
    } catch (e) {
      setRestoreFile(null);
      setError(e instanceof SyntaxError ? "That file is not JSON." : errorText(e));
    }
  };

  const restore = async () => {
    if (!restoreFile) return;
    setBusy(true);
    setError(null);
    try {
      const r = await studioApi.restore(restoreFile.data, conflict, restorePass || null);
      const parts = [`${r.connections} connection(s)`, `${r.folders} folder(s)`, `${r.scripts} script(s)`, `${r.settings} setting(s)`];
      toast.ok(`Restored ${parts.join(", ")}${r.skipped ? `; ${r.skipped} kept as they were` : ""}${r.secrets ? `; ${r.secrets} password(s)` : ""}.`
        + (r.notes.length ? " " + r.notes.join(" ") : ""));
      props.onRestored();
      setRestoreFile(null);
      setPreview(null);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const copyDiagnostics = async () => {
    try {
      const d = await studioApi.diagnostics();
      const text = diagnosticsText(d, { userAgent: navigator.userAgent, uiErrors });
      try {
        await navigator.clipboard.writeText(text);
        toast.ok("Diagnostics copied (versions, platform, recent errors; no passwords or hosts).");
      } catch {
        download("cassandra-studio-diagnostics.txt", text, "text/plain");
      }
    } catch (e) {
      setError(errorText(e));
    }
  };

  const conflicts = preview
    ? preview.connections.conflicting + preview.folders.conflicting + preview.scripts.conflicting + preview.settings.conflicting
    : 0;

  return (
    <Modal title="Studio data" onClose={props.onClose} width={620} footer={<button className="btn" onClick={props.onClose}>Close</button>}>
      <div className="stack">
        {error && <div className="notice error" role="alert">{error}</div>}
        <section className="stack" aria-labelledby="sd-backup">
          <h3 id="sd-backup" style={{ margin: 0 }}>Back up settings</h3>
          <p className="muted" style={{ margin: 0 }}>Connections, folders, saved scripts and settings in one JSON file.</p>
          <label className="row">
            <input type="checkbox" checked={withSecrets} onChange={(e) => setWithSecrets(e.target.checked)} />
            Include passwords, encrypted with a passphrase
          </label>
          {withSecrets && (
            <label className="stack" style={{ gap: 2 }}>
              <span>Passphrase (at least 8 characters; needed to restore the passwords)</span>
              <input type="password" autoComplete="new-password" value={passphrase} onChange={(e) => setPassphrase(e.target.value)} />
            </label>
          )}
          <div><button className="btn primary" disabled={busy} onClick={backup}>Save backup…</button></div>
        </section>
        <section className="stack" aria-labelledby="sd-restore">
          <h3 id="sd-restore" style={{ margin: 0 }}>Restore settings</h3>
          <label className="stack" style={{ gap: 2 }}>
            <span>Backup file</span>
            <input type="file" accept=".json,application/json" onChange={(e) => e.target.files?.[0] && pick(e.target.files[0])} />
          </label>
          {preview && restoreFile && (
            <div className="stack" data-testid="restore-preview">
              <div>
                {restoreFile.name}: {preview.connections.added + preview.connections.conflicting} connection(s),{" "}
                {preview.folders.added + preview.folders.conflicting} folder(s), {preview.scripts.added + preview.scripts.conflicting} script(s),{" "}
                {preview.settings.added + preview.settings.conflicting} setting(s).
              </div>
              {conflicts > 0 && (
                <fieldset className="stack" style={{ gap: 4 }}>
                  <legend>{conflicts} item(s) already exist here. For those:</legend>
                  {([["skip", "Keep mine (skip them)"], ["replace", "Replace mine with the backup's"], ["keep_both", "Keep both (restored copies get “(restored)” in their name)"]] as const).map(([k, label]) => (
                    <label key={k} className="row">
                      <input type="radio" name="sd-conflict" checked={conflict === k} onChange={() => setConflict(k)} /> {label}
                    </label>
                  ))}
                </fieldset>
              )}
              {preview.hasSecrets && (
                <label className="stack" style={{ gap: 2 }}>
                  <span>Passphrase for the encrypted passwords (leave empty to restore without them)</span>
                  <input type="password" autoComplete="off" value={restorePass} onChange={(e) => setRestorePass(e.target.value)} />
                </label>
              )}
              <div><button className="btn primary" disabled={busy} onClick={restore}>Restore</button></div>
            </div>
          )}
        </section>
        <section className="stack" aria-labelledby="sd-diag">
          <h3 id="sd-diag" style={{ margin: 0 }}>Diagnostics</h3>
          <p className="muted" style={{ margin: 0 }}>
            Engine and UI errors are written to a crash log in Studio&apos;s data folder and never sent anywhere.
          </p>
          <div><button className="btn" onClick={copyDiagnostics}>Copy diagnostics</button></div>
        </section>
      </div>
    </Modal>
  );
}
