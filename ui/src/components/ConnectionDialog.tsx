import { useState } from "react";
import { api, type Secrets } from "../lib/api";
import type { ConnectionConfig, Environment, Folder, JmxMethod, SecretName, SshAuth, TestResult } from "../lib/types";
import { errorText, useToast } from "./feedback";
import { Modal } from "./Modal";

export const CONSISTENCY_LEVELS = [
  "ANY", "ONE", "TWO", "THREE", "QUORUM", "ALL", "LOCAL_ONE", "LOCAL_QUORUM", "EACH_QUORUM",
];

export function newConnection(folderId?: string | null): ConnectionConfig {
  return {
    folderId: folderId ?? null,
    name: "",
    environment: "DEV",
    readOnly: false,
    contactPoints: [],
    localDatacenter: "",
    username: "",
    tls: { enabled: false, hostnameVerification: true },
    protocolVersion: "auto",
    defaultConsistency: "LOCAL_ONE",
    requestTimeoutMs: 12000,
    pageSize: 100,
    jmx: { method: "SSH_TUNNEL", port: 7199, ssl: false, exporterPort: 7071, sidecarPort: 9043 },
    ssh: { port: 22, auth: "AGENT", jumpPort: 22, strictHostKeyChecking: true },
    tags: [],
  };
}

type Section = "general" | "cql" | "tls" | "jmx" | "ssh";

/** Create / edit a connection (CON-1, CON-3, CON-4, CON-6, CON-7). */
export function ConnectionDialog(props: {
  initial: ConnectionConfig;
  folders: Folder[];
  onClose: () => void;
  onSaved: (c: ConnectionConfig) => void;
}) {
  const toast = useToast();
  const [c, setC] = useState<ConnectionConfig>(props.initial);
  const [points, setPoints] = useState(props.initial.contactPoints.join(", "));
  const [secrets, setSecrets] = useState<Secrets>({});
  const [section, setSection] = useState<Section>("general");
  const [test, setTest] = useState<TestResult | null>(null);
  const [busy, setBusy] = useState(false);
  const isNew = !props.initial.id;

  const set = <K extends keyof ConnectionConfig>(k: K, v: ConnectionConfig[K]) => setC((x) => ({ ...x, [k]: v }));
  const setTls = (patch: Partial<ConnectionConfig["tls"]>) => setC((x) => ({ ...x, tls: { ...x.tls, ...patch } }));
  const setJmx = (patch: Partial<ConnectionConfig["jmx"]>) => setC((x) => ({ ...x, jmx: { ...x.jmx, ...patch } }));
  const setSsh = (patch: Partial<ConnectionConfig["ssh"]>) => setC((x) => ({ ...x, ssh: { ...x.ssh, ...patch } }));

  const full = (): ConnectionConfig => ({
    ...c,
    contactPoints: points.split(/[,\s]+/).map((p) => p.trim()).filter(Boolean),
    localDatacenter: c.localDatacenter?.trim() || null,
    username: c.username?.trim() || null,
  });

  const secretField = (name: SecretName, label: string) => {
    const stored = props.initial.secretsSet?.[name];
    return (
      <label className="field">
        <span>
          {label} {stored && <i className="muted">(stored in keychain — leave blank to keep)</i>}
        </span>
        <div className="row" style={{ flexWrap: "nowrap" }}>
          <input
            type="password"
            autoComplete="new-password"
            style={{ flex: 1 }}
            value={secrets[name] ?? ""}
            onChange={(e) => setSecrets((s) => ({ ...s, [name]: e.target.value }))}
          />
          {stored && (
            <button className="btn small" type="button" onClick={() => setSecrets((s) => ({ ...s, [name]: "" }))}
              title="Remove the stored secret when saving">
              Clear
            </button>
          )}
        </div>
      </label>
    );
  };

  // Blank secret inputs mean "unchanged" unless the user pressed Clear (then "").
  const secretsToSend = (): Secrets => {
    const out: Secrets = {};
    for (const [k, v] of Object.entries(secrets)) {
      if (v === "" && !props.initial.secretsSet?.[k]) continue;
      out[k as SecretName] = v;
    }
    return out;
  };

  const runTest = async () => {
    setBusy(true);
    setTest(null);
    try {
      setTest(await api.testConnection({ ...full(), id: props.initial.id ?? null }, secretsToSend()));
    } catch (e) {
      setTest({ ok: false, error: errorText(e), nodes: 0, elapsedMs: 0 });
    } finally {
      setBusy(false);
    }
  };

  const save = async () => {
    setBusy(true);
    try {
      const saved = isNew
        ? await api.createConnection(full(), secretsToSend())
        : await api.updateConnection(props.initial.id!, full(), secretsToSend());
      toast.ok(`Saved ${saved.name}`);
      props.onSaved(saved);
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(false);
    }
  };

  const sections: [Section, string][] = [
    ["general", "General"],
    ["cql", "CQL"],
    ["tls", "TLS"],
    ["jmx", "JMX / metrics"],
    ["ssh", "SSH"],
  ];

  return (
    <Modal
      title={isNew ? "New connection" : `Edit ${props.initial.name}`}
      onClose={props.onClose}
      width={820}
      footer={
        <>
          {test && (
            <span className={test.ok ? "ok-text" : "error-text"} style={{ flex: 1 }} data-testid="test-result">
              {test.ok
                ? `✓ ${test.clusterName} · Cassandra ${test.version} · ${test.nodes} node(s) · ${test.protocolVersion} · ${test.elapsedMs} ms`
                : `✗ ${test.error}`}
            </span>
          )}
          <button className="btn" onClick={runTest} disabled={busy}>
            Test
          </button>
          <button className="btn" onClick={props.onClose}>
            Cancel
          </button>
          <button className="btn primary" onClick={save} disabled={busy || !c.name.trim() || !points.trim()}>
            Save
          </button>
        </>
      }
    >
      <div className="tabs" style={{ marginBottom: 14 }}>
        {sections.map(([k, label]) => (
          <button key={k} className={"tab" + (section === k ? " active" : "")} onClick={() => setSection(k)}>
            {label}
          </button>
        ))}
      </div>

      {section === "general" && (
        <div className="grid2">
          <label className="field">
            <span>Name *</span>
            <input autoFocus value={c.name} onChange={(e) => set("name", e.target.value)} placeholder="acme-prod-core" />
          </label>
          <label className="field">
            <span>Folder</span>
            <select value={c.folderId ?? ""} onChange={(e) => set("folderId", e.target.value || null)}>
              <option value="">(root)</option>
              {folderOptions(props.folders)}
            </select>
          </label>
          <label className="field">
            <span>Environment</span>
            <select value={c.environment} onChange={(e) => set("environment", e.target.value as Environment)}>
              {(["DEV", "TEST", "STAGING", "PROD"] as Environment[]).map((env) => (
                <option key={env}>{env}</option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>Tags (comma separated)</span>
            <input value={c.tags.join(", ")}
              onChange={(e) => set("tags", e.target.value.split(",").map((t) => t.trim()).filter(Boolean))} />
          </label>
          <label className="check">
            <input type="checkbox" checked={c.readOnly} onChange={(e) => set("readOnly", e.target.checked)} />
            Read-only (block every write, DDL and operation)
          </label>
          <div />
          <label className="field" style={{ gridColumn: "1 / 3" }}>
            <span>Notes</span>
            <textarea rows={3} value={c.notes ?? ""} onChange={(e) => set("notes", e.target.value)} />
          </label>
          {c.environment === "PROD" && (
            <div className="notice warn" style={{ gridColumn: "1 / 3" }}>
              PROD connections show a red banner, and every change asks you to type the connection name.
            </div>
          )}
        </div>
      )}

      {section === "cql" && (
        <div className="grid2">
          <label className="field" style={{ gridColumn: "1 / 3" }}>
            <span>Contact points * (host or host:port, comma separated)</span>
            <input value={points} onChange={(e) => setPoints(e.target.value)} placeholder="10.11.0.11, 10.11.0.12:9042" />
          </label>
          <label className="field">
            <span>Local datacenter (blank = detect from contact points)</span>
            <input value={c.localDatacenter ?? ""} onChange={(e) => set("localDatacenter", e.target.value)} placeholder="dc_east" />
          </label>
          <label className="field">
            <span>Protocol version</span>
            <select value={c.protocolVersion} onChange={(e) => set("protocolVersion", e.target.value)}>
              <option value="auto">Auto-negotiate</option>
              <option value="V3">v3</option>
              <option value="V4">v4</option>
              <option value="V5">v5</option>
            </select>
          </label>
          <label className="field">
            <span>Username (PasswordAuthenticator)</span>
            <input value={c.username ?? ""} onChange={(e) => set("username", e.target.value)} autoComplete="off" />
          </label>
          {secretField("password", "Password")}
          <label className="field">
            <span>Default consistency</span>
            <select value={c.defaultConsistency} onChange={(e) => set("defaultConsistency", e.target.value)}>
              {CONSISTENCY_LEVELS.map((l) => (
                <option key={l}>{l}</option>
              ))}
            </select>
          </label>
          <div className="grid2">
            <label className="field">
              <span>Timeout (ms)</span>
              <input type="number" min={100} value={c.requestTimeoutMs}
                onChange={(e) => set("requestTimeoutMs", Number(e.target.value))} />
            </label>
            <label className="field">
              <span>Page size</span>
              <input type="number" min={1} value={c.pageSize} onChange={(e) => set("pageSize", Number(e.target.value))} />
            </label>
          </div>
        </div>
      )}

      {section === "tls" && (
        <div className="grid2">
          <label className="check" style={{ gridColumn: "1 / 3" }}>
            <input type="checkbox" checked={c.tls.enabled} onChange={(e) => setTls({ enabled: e.target.checked })} />
            Use TLS for CQL (client_encryption_options)
          </label>
          {c.tls.enabled && (
            <>
              <label className="field">
                <span>Truststore path (JKS, PKCS12 or PEM CA bundle)</span>
                <input value={c.tls.truststorePath ?? ""} onChange={(e) => setTls({ truststorePath: e.target.value })} />
              </label>
              <label className="field">
                <span>Truststore type</span>
                <select value={c.tls.truststoreType ?? ""} onChange={(e) => setTls({ truststoreType: e.target.value || null })}>
                  <option value="">From file extension</option>
                  <option>JKS</option>
                  <option>PKCS12</option>
                  <option>PEM</option>
                </select>
              </label>
              {secretField("truststorePassword", "Truststore password")}
              <label className="check">
                <input type="checkbox" checked={c.tls.hostnameVerification}
                  onChange={(e) => setTls({ hostnameVerification: e.target.checked })} />
                Verify node hostnames
              </label>
              <label className="field">
                <span>Client keystore for mTLS (JKS or PKCS12, optional)</span>
                <input value={c.tls.keystorePath ?? ""} onChange={(e) => setTls({ keystorePath: e.target.value })} />
              </label>
              {secretField("keystorePassword", "Keystore password")}
            </>
          )}
        </div>
      )}

      {section === "jmx" && (
        <div className="grid2">
          <label className="field" style={{ gridColumn: "1 / 3" }}>
            <span>How to reach JMX on the nodes</span>
            <select value={c.jmx.method} onChange={(e) => setJmx({ method: e.target.value as JmxMethod })}>
              <option value="SSH_TUNNEL">SSH tunnel to localhost:7199 (JMX bound to localhost — the estate default)</option>
              <option value="DIRECT">Direct JMX/RMI over the network</option>
              <option value="EXPORTER">jmx_exporter HTTP endpoint (metrics only, no operations)</option>
              <option value="SIDECAR">Apache Cassandra Sidecar</option>
              <option value="NONE">None (CQL only)</option>
            </select>
          </label>
          {(c.jmx.method === "SSH_TUNNEL" || c.jmx.method === "DIRECT") && (
            <>
              <label className="field">
                <span>JMX port</span>
                <input type="number" value={c.jmx.port} onChange={(e) => setJmx({ port: Number(e.target.value) })} />
              </label>
              <label className="check">
                <input type="checkbox" checked={c.jmx.ssl} onChange={(e) => setJmx({ ssl: e.target.checked })} />
                JMX over SSL
              </label>
              <label className="field">
                <span>JMX username (if jmxremote auth is on)</span>
                <input value={c.jmx.username ?? ""} onChange={(e) => setJmx({ username: e.target.value || null })} />
              </label>
              {secretField("jmxPassword", "JMX password")}
            </>
          )}
          {c.jmx.method === "EXPORTER" && (
            <label className="field">
              <span>jmx_exporter port</span>
              <input type="number" value={c.jmx.exporterPort} onChange={(e) => setJmx({ exporterPort: Number(e.target.value) })} />
            </label>
          )}
          {c.jmx.method === "SIDECAR" && (
            <label className="field">
              <span>Sidecar port</span>
              <input type="number" value={c.jmx.sidecarPort} onChange={(e) => setJmx({ sidecarPort: Number(e.target.value) })} />
            </label>
          )}
          {c.jmx.method === "SSH_TUNNEL" && (
            <div className="notice info" style={{ gridColumn: "1 / 3" }}>
              Uses the SSH settings in the SSH tab to open a tunnel to each node.
            </div>
          )}
        </div>
      )}

      {section === "ssh" && (
        <div className="grid2">
          <label className="field">
            <span>SSH user</span>
            <input value={c.ssh.username ?? ""} onChange={(e) => setSsh({ username: e.target.value || null })} />
          </label>
          <label className="field">
            <span>SSH port</span>
            <input type="number" value={c.ssh.port} onChange={(e) => setSsh({ port: Number(e.target.value) })} />
          </label>
          <label className="field">
            <span>Authentication</span>
            <select value={c.ssh.auth} onChange={(e) => setSsh({ auth: e.target.value as SshAuth })}>
              <option value="AGENT">SSH agent</option>
              <option value="KEY">Private key file</option>
              <option value="PASSWORD">Password</option>
            </select>
          </label>
          {c.ssh.auth === "KEY" && (
            <label className="field">
              <span>Private key path</span>
              <input value={c.ssh.keyPath ?? ""} onChange={(e) => setSsh({ keyPath: e.target.value || null })}
                placeholder="~/.ssh/id_ed25519" />
            </label>
          )}
          {c.ssh.auth === "KEY" && secretField("sshPassphrase", "Key passphrase")}
          {c.ssh.auth === "PASSWORD" && secretField("sshPassword", "SSH password")}
          <label className="field">
            <span>Jump host / bastion (optional)</span>
            <input value={c.ssh.jumpHost ?? ""} onChange={(e) => setSsh({ jumpHost: e.target.value || null })} />
          </label>
          <label className="field">
            <span>Jump user</span>
            <input value={c.ssh.jumpUser ?? ""} onChange={(e) => setSsh({ jumpUser: e.target.value || null })} />
          </label>
          <label className="check">
            <input type="checkbox" checked={c.ssh.strictHostKeyChecking}
              onChange={(e) => setSsh({ strictHostKeyChecking: e.target.checked })} />
            Check host keys against known_hosts
          </label>
          <label className="field">
            <span>known_hosts path (blank = ~/.ssh/known_hosts)</span>
            <input value={c.ssh.knownHostsPath ?? ""} onChange={(e) => setSsh({ knownHostsPath: e.target.value || null })} />
          </label>
          <label className="field">
            <span>SSH through a proxy (jump host, else node)</span>
            <select value={c.ssh.proxy?.type ?? ""}
              onChange={(e) => setSsh({ proxy: e.target.value ? { ...(c.ssh.proxy ?? { host: "" }), type: e.target.value as "HTTP" | "SOCKS5" } : null })}>
              <option value="">No proxy</option>
              <option value="HTTP">HTTP CONNECT proxy</option>
              <option value="SOCKS5">SOCKS5 proxy</option>
            </select>
          </label>
          {c.ssh.proxy && (
            <>
              <label className="field">
                <span>Proxy host and port</span>
                <div className="row" style={{ flexWrap: "nowrap" }}>
                  <input aria-label="Proxy host" style={{ flex: 1 }} value={c.ssh.proxy.host}
                    onChange={(e) => setSsh({ proxy: { ...c.ssh.proxy!, host: e.target.value } })} placeholder="proxy.corp.example" />
                  <input aria-label="Proxy port" type="number" style={{ width: 90 }} value={c.ssh.proxy.port ?? ""}
                    placeholder={c.ssh.proxy.type === "SOCKS5" ? "1080" : "3128"}
                    onChange={(e) => setSsh({ proxy: { ...c.ssh.proxy!, port: e.target.value ? Number(e.target.value) : null } })} />
                </div>
              </label>
              <label className="field">
                <span>Proxy user (optional)</span>
                <input value={c.ssh.proxy.username ?? ""}
                  onChange={(e) => setSsh({ proxy: { ...c.ssh.proxy!, username: e.target.value || null } })} />
              </label>
              {secretField("sshProxyPassword", "Proxy password")}
            </>
          )}
        </div>
      )}
    </Modal>
  );
}

function folderOptions(folders: Folder[], parent: string | null = null, depth = 0): React.ReactNode[] {
  return folders
    .filter((f) => (f.parentId ?? null) === parent)
    .flatMap((f) => [
      <option key={f.id} value={f.id}>
        {"  ".repeat(depth) + f.name}
      </option>,
      ...folderOptions(folders, f.id, depth + 1),
    ]);
}
