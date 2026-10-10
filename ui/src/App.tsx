import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "./lib/api";
import type { ConnectionConfig, EngineInfo, Folder } from "./lib/types";
import { ConnectionTree } from "./components/ConnectionTree";
import { ConnectionDialog, newConnection } from "./components/ConnectionDialog";
import { useToast } from "./components/feedback";
import { Workspace } from "./panels/Workspace";
import { AuditPanel } from "./panels/HistoryPanel";
import { download } from "./lib/export";
import { AppSettings } from "./panels/settings/AppSettings";

type Theme = "light" | "dark";

function initialTheme(): Theme {
  try {
    const saved = localStorage.getItem("studio.theme");
    if (saved === "light" || saved === "dark") return saved;
  } catch {
    // storage unavailable
  }
  return window.matchMedia?.("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

export function App() {
  const toast = useToast();
  const [info, setInfo] = useState<EngineInfo | null>(null);
  const [folders, setFolders] = useState<Folder[]>([]);
  const [connections, setConnections] = useState<ConnectionConfig[]>([]);
  const [open, setOpen] = useState<string[]>([]);
  const [active, setActive] = useState<string | null>(null);
  const [connected, setConnected] = useState<Set<string>>(new Set());
  const [selected, setSelected] = useState<string | null>(null);
  const [editing, setEditing] = useState<ConnectionConfig | null>(null);
  const [theme, setTheme] = useState<Theme>(initialTheme);
  const importInput = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try { localStorage.setItem("studio.theme", theme); } catch { /* ignore */ }
  }, [theme]);

  const reload = useCallback(async () => {
    try {
      const [f, c] = await Promise.all([api.folders(), api.connections()]);
      setFolders(f);
      setConnections(c);
    } catch (e) {
      toast.error(e);
    }
  }, [toast]);

  useEffect(() => {
    api.info().then(setInfo).catch(toast.error);
    reload();
  }, [reload, toast]);

  const openConn = (c: ConnectionConfig) => {
    setOpen((o) => (o.includes(c.id!) ? o : [...o, c.id!]));
    setActive(c.id!);
  };
  const closeConn = (id: string) => {
    api.disconnect(id).catch(() => undefined);
    setConnected((s) => { const n = new Set(s); n.delete(id); return n; });
    setOpen((o) => o.filter((x) => x !== id));
    setActive((a) => (a === id ? open.find((x) => x !== id) ?? "audit" : a));
  };
  const onConnected = useCallback((id: string, ok: boolean) =>
    setConnected((s) => { const n = new Set(s); if (ok) n.add(id); else n.delete(id); return n; }), []);

  const importFile = async (f: File) => {
    try {
      const r = await api.importConnections(JSON.parse(await f.text()));
      toast.ok(`Imported ${r.connections} connection(s) and ${r.folders} folder(s). Passwords are not in export files; add them per connection.`);
      reload();
    } catch (e) {
      toast.error(e);
    }
  };

  const byId = new Map(connections.map((c) => [c.id!, c]));
  const activeConn = active && active !== "audit" ? byId.get(active) : undefined;

  return (
    <div className="app">
      <header className="topbar">
        <span className="brand">Cassandra Studio</span>
        <span className="muted">{info ? `v${info.version}` : ""}</span>
        <span className="spacer" />
        <button className="btn small" onClick={() => setActive("audit")}>Audit log</button>
        <button className="btn small" onClick={() => api.exportConnections().then((d) => download("cassandra-studio-connections.json", JSON.stringify(d, null, 2), "application/json")).catch(toast.error)}>Export connections</button>
        <button className="btn small" onClick={() => importInput.current?.click()}>Import</button>
        <input ref={importInput} type="file" accept=".json" hidden onChange={(e) => e.target.files?.[0] && importFile(e.target.files[0])} />
        <AppSettings />
        <button className="btn small" aria-label="Toggle dark mode" onClick={() => setTheme(theme === "dark" ? "light" : "dark")}>
          {theme === "dark" ? "☀" : "☾"}
        </button>
      </header>
      <aside className="sidebar">
        <ConnectionTree
          folders={folders}
          connections={connections}
          connected={connected}
          selectedId={selected}
          onSelect={(c) => setSelected(c.id!)}
          onOpen={openConn}
          onEdit={(c) => setEditing(c)}
          onClone={(c) => api.cloneConnection(c.id!).then(reload).catch(toast.error)}
          onDelete={(c) => {
            if (!confirm(`Delete connection "${c.name}" and its stored secrets?`)) return;
            closeConn(c.id!);
            api.deleteConnection(c.id!).then(reload).catch(toast.error);
          }}
          onNewConnection={(folderId) => setEditing(newConnection(folderId))}
          onNewFolder={(parentId) => {
            const name = prompt("Folder name (e.g. customer, environment or cluster)");
            if (name?.trim()) api.createFolder(name.trim(), parentId).then(reload).catch(toast.error);
          }}
          onRenameFolder={(f) => {
            const name = prompt("Rename folder", f.name);
            if (name?.trim()) api.updateFolder(f.id, { name: name.trim(), parentId: f.parentId ?? null }).then(reload).catch(toast.error);
          }}
          onDeleteFolder={(f) => {
            if (confirm(`Delete folder "${f.name}"? Its sub-folders go too; connections inside move to the top level.`)) {
              api.deleteFolder(f.id).then(reload).catch(toast.error);
            }
          }}
          onMoveConnection={(c, folderId) => api.updateConnection(c.id!, { ...c, folderId }, {}).then(reload).catch(toast.error)}
        />
        <div className="muted" style={{ marginTop: "auto", padding: 8, fontSize: 11 }}>
          {info && <>Secrets: {info.secretStore} · user {info.actor}</>}
        </div>
      </aside>
      <main className="main">
        <div className="tabs">
          {open.map((id) => {
            const c = byId.get(id);
            if (!c) return null;
            return (
              <button key={id} className={"tab" + (active === id ? " active" : "")} onClick={() => setActive(id)}>
                <span className={"env " + c.environment}>{c.environment}</span> {c.name}
                <span className="close" role="button" aria-label={`Close ${c.name}`} onClick={(e) => { e.stopPropagation(); closeConn(id); }}>×</span>
              </button>
            );
          })}
          {active === "audit" && <button className="tab active">Audit log</button>}
        </div>
        <div style={{ flex: 1, minHeight: 0 }}>
          {open.map((id) => {
            const c = byId.get(id);
            return c ? (
              <div key={id} style={{ display: active === id ? "block" : "none", height: "100%" }}>
                <Workspace conn={c} dark={theme === "dark"} onConnected={onConnected} />
              </div>
            ) : null;
          })}
          {active === "audit" && <AuditPanel />}
          {!activeConn && active !== "audit" && (
            <div className="empty">
              <h2>Cassandra Studio</h2>
              <p>Double-click a connection on the left to open it, or create one with “+ Connection”.</p>
            </div>
          )}
        </div>
      </main>
      {editing && (
        <ConnectionDialog
          initial={editing}
          folders={folders}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); reload(); }}
        />
      )}
    </div>
  );
}
