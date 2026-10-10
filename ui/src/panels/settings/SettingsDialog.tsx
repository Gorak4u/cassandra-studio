import { useEffect, useState } from "react";
import { Modal } from "../../components/Modal";
import { useToast } from "../../components/feedback";
import { parseNoProxy, settingsApi, type NetworkSettings, type NetworkView, type ProxyMode, type UpdateStatus } from "./settingsApi";
import "./settings.css";

/** Settings > Network (NFR-NET, NFR-UPD, NFR-SEC): offline mode, update check, proxy, extra CA certificates. */
export function SettingsDialog(props: { onClose: () => void; onSaved?: (v: NetworkView) => void }) {
  const toast = useToast();
  const [view, setView] = useState<NetworkView | null>(null);
  const [s, setS] = useState<NetworkSettings | null>(null);
  const [noProxy, setNoProxy] = useState("");
  const [password, setPassword] = useState<string | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [update, setUpdate] = useState<UpdateStatus | null>(null);

  useEffect(() => {
    settingsApi.network().then((v) => {
      setView(v);
      setS(v.settings);
      setNoProxy(v.settings.noProxy.join(", "));
    }).catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, []);

  const set = <K extends keyof NetworkSettings>(k: K, v: NetworkSettings[K]) => setS((x) => (x ? { ...x, [k]: v } : x));

  const save = async () => {
    if (!s) return;
    setBusy(true);
    setError(null);
    try {
      const v = await settingsApi.saveNetwork({ ...s, noProxy: parseNoProxy(noProxy) }, password);
      setView(v);
      toast.ok("Network settings saved. Open clusters use new CA settings after reconnecting.");
      props.onSaved?.(v);
      props.onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const checkNow = async () => {
    setUpdate(null);
    try {
      setUpdate(await settingsApi.updates(true));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <Modal
      title="Settings"
      onClose={props.onClose}
      width={720}
      footer={
        <>
          {error && <span className="error-text" role="alert" style={{ flex: 1 }}>{error}</span>}
          <button className="btn" onClick={props.onClose}>Cancel</button>
          <button className="btn primary" onClick={save} disabled={busy || !s}>Save</button>
        </>
      }
    >
      {!s || !view ? (
        !error && <p className="muted">Loading…</p>
      ) : (
        <div className="settings-net" data-testid="settings-network">
          <h3>Network</h3>
          {view.warning && <div className="notice warn">{view.warning}</div>}

          <fieldset>
            <legend>Offline and updates</legend>
            <label className="check">
              <input type="checkbox" checked={s.offline} onChange={(e) => set("offline", e.target.checked)} />
              Offline mode (air-gapped): no network calls except to your clusters
            </label>
            <label className="check">
              <input type="checkbox" checked={s.checkForUpdates && !s.offline} disabled={s.offline}
                onChange={(e) => set("checkForUpdates", e.target.checked)} />
              Check for updates at start (asks GitHub for the latest release; never downloads)
            </label>
            {!s.offline && s.checkForUpdates && (
              <div className="row">
                <button className="btn small" type="button" onClick={checkNow}>Check now</button>
                {update && (
                  <span className={update.state === "error" ? "error-text" : "muted"} data-testid="update-result">
                    {update.message}
                    {update.updateAvailable && update.url && (
                      <> <a href={update.url} target="_blank" rel="noopener noreferrer">Release page</a></>
                    )}
                  </span>
                )}
              </div>
            )}
          </fieldset>

          <fieldset disabled={s.offline}>
            <legend>Proxy for Studio's own HTTPS calls (update check)</legend>
            <div role="radiogroup" aria-label="Proxy" className="row">
              {([["NONE", "No proxy"], ["SYSTEM", "System proxy"], ["MANUAL", "Manual"]] as [ProxyMode, string][]).map(([m, label]) => (
                <label key={m} className="check">
                  <input type="radio" name="proxyMode" value={m} checked={s.proxyMode === m} onChange={() => set("proxyMode", m)} />
                  {label}
                </label>
              ))}
            </div>
            {s.proxyMode === "SYSTEM" && (
              <p className="muted small-text">Detected: {view.systemProxy}. From HTTPS_PROXY / HTTP_PROXY / NO_PROXY or the Java proxy properties.</p>
            )}
            {s.proxyMode !== "NONE" && (
              <div className="grid2">
                {s.proxyMode === "MANUAL" && (
                  <>
                    <label className="field">
                      <span>Proxy host</span>
                      <input value={s.proxyHost ?? ""} onChange={(e) => set("proxyHost", e.target.value || null)} placeholder="proxy.corp.example" />
                    </label>
                    <label className="field">
                      <span>Proxy port</span>
                      <input type="number" min={1} max={65535} value={s.proxyPort ?? ""}
                        onChange={(e) => set("proxyPort", e.target.value ? Number(e.target.value) : null)} placeholder="8080" />
                    </label>
                  </>
                )}
                <label className="field">
                  <span>Proxy user (optional)</span>
                  <input value={s.proxyUsername ?? ""} onChange={(e) => set("proxyUsername", e.target.value || null)} autoComplete="off" />
                </label>
                <label className="field">
                  <span>
                    Proxy password {view.proxyPasswordSet && <i className="muted">(stored in keychain — leave blank to keep)</i>}
                  </span>
                  <div className="row" style={{ flexWrap: "nowrap" }}>
                    <input type="password" autoComplete="new-password" style={{ flex: 1 }} value={password ?? ""}
                      onChange={(e) => setPassword(e.target.value === "" && !view.proxyPasswordSet ? undefined : e.target.value)} />
                    {view.proxyPasswordSet && (
                      <button className="btn small" type="button" onClick={() => setPassword("")} title="Remove the stored password when saving">
                        Clear
                      </button>
                    )}
                  </div>
                </label>
                {s.proxyMode === "MANUAL" && (
                  <label className="field span2">
                    <span>No proxy for (hosts, .domains, 10.0.0.0/8 ranges; comma separated)</span>
                    <input value={noProxy} onChange={(e) => setNoProxy(e.target.value)} placeholder=".corp.example, 10.0.0.0/8" />
                  </label>
                )}
              </div>
            )}
            <label className="check">
              <input type="checkbox" checked={s.proxyNodeHttp} onChange={(e) => set("proxyNodeHttp", e.target.checked)} />
              Also use this proxy for HTTP to cluster nodes (jmx_exporter). Usually off: node addresses are internal.
            </label>
          </fieldset>

          <fieldset>
            <legend>Extra CA certificates (CQL TLS, JMX TLS, HTTPS)</legend>
            <label className="field">
              <span>CA bundle (PEM file), trusted in addition to each connection's truststore</span>
              <input value={s.caBundlePath ?? ""} onChange={(e) => set("caBundlePath", e.target.value || null)}
                placeholder="/etc/pki/corp/ca-bundle.pem" />
            </label>
            {view.caBundle && (
              <p className="muted small-text" data-testid="ca-info">
                {view.caBundle.certificates} certificate(s) in use: {view.caBundle.subjects.slice(0, 3).join("; ")}
                {view.caBundle.subjects.length > 3 ? " …" : ""}
              </p>
            )}
            <label className="check">
              <input type="checkbox" checked={s.trustOsStore} disabled={!view.osTrustAvailable && !s.trustOsStore}
                onChange={(e) => set("trustOsStore", e.target.checked)} />
              Also trust the operating system's CA certificates{!view.osTrustAvailable && " (none found on this system)"}
            </label>
          </fieldset>
        </div>
      )}
    </Modal>
  );
}
