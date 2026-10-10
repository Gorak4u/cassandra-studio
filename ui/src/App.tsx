import { Component, useCallback, useEffect, useMemo, useRef, useState, type ErrorInfo, type ReactNode } from "react";
import { api } from "./lib/api";
import type { ConnectionConfig, EngineInfo, Folder } from "./lib/types";
import { ConnectionTree } from "./components/ConnectionTree";
import { ConnectionDialog, newConnection } from "./components/ConnectionDialog";
import { useToast } from "./components/feedback";
import { Workspace } from "./panels/Workspace";
import { AuditPanel } from "./panels/HistoryPanel";
import { download } from "./lib/export";
import {
  autoConnectOnRestore, clampSidebar, debounced, EMPTY_UI_STATE, pruneUiState, SIDEBAR_MAX, SIDEBAR_MIN, studioApi, type UiState,
} from "./lib/studioApi";
import { StudioDataDialog, uiErrors } from "./components/StudioDataDialog";

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

/** Errors in one view stay in that view; they are written to the local crash log (NFR-OBS). */
class ViewBoundary extends Component<{ name: string; children: ReactNode }, { error: Error | null }> {
  state: { error: Error | null } = { error: null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    reportUiError(`${this.props.name}: ${error.message}`, error.stack, info.componentStack ?? undefined);
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="pad" role="alert">
        <div className="notice error">This view stopped because of an error: {this.state.error.message}. It was written to the local crash log.</div>
        <button className="btn" onClick={() => this.setState({ error: null })}>Reload view</button>
      </div>
    );
  }
}

let reported = 0;
/** Sends a UI error to the engine's local crash log (at most 50 per window) and keeps it for "Copy diagnostics". */
function reportUiError(message: string, stack?: string, componentStack?: string) {
  uiErrors.push(message.slice(0, 500));
  if (uiErrors.length > 20) uiErrors.shift();
  if (reported++ >= 50) return;
  studioApi.reportUiError({ message, stack, componentStack }).catch(() => undefined);
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
  const [studioData, setStudioData] = useState(false);
  const importInput = useRef<HTMLInputElement | null>(null);
  // Remembered layout (NFR-UX): restored once at start, then saved (debounced) on every change.
  const [restored, setRestored] = useState(false);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [sidebarWidth, setSidebarWidth] = useState(clampSidebar(undefined));
  const [tabs, setTabs] = useState<UiState["workspaces"]>({});
  const restoredState = useRef<UiState>(EMPTY_UI_STATE);
  const saver = useMemo(() => debounced((st: UiState) => { studioApi.saveUiState(st).catch(() => undefined); }, 400), []);

  useEffect(() => {
    const onError = (e: ErrorEvent) => reportUiError(e.message || "Script error", e.error instanceof Error ? e.error.stack : undefined);
    const onRejection = (e: PromiseRejectionEvent) => {
      const r = e.reason;
      reportUiError("Unhandled promise rejection: " + (r instanceof Error ? r.message : String(r)), r instanceof Error ? r.stack : undefined);
    };
    window.addEventListener("error", onError);
    window.addEventListener("unhandledrejection", onRejection);
    return () => {
      window.removeEventListener("error", onError);
      window.removeEventListener("unhandledrejection", onRejection);
    };
  }, []);

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
    Promise.all([api.folders(), api.connections(), studioApi.uiState().catch(() => EMPTY_UI_STATE)])
      .then(([f, c, saved]) => {
        setFolders(f);
        setConnections(c);
        const st = pruneUiState(saved, new Set(c.map((x) => x.id!)), new Set(f.map((x) => x.id)));
        restoredState.current = st;
        setOpen(st.open);
        setActive(st.active);
        setTabs(st.workspaces);
        setCollapsed(new Set(st.collapsed));
        setSidebarWidth(clampSidebar(st.sidebarWidth));
      })
      .catch(toast.error)
      .finally(() => setRestored(true));
  }, [toast]);

  useEffect(() => {
    if (!restored) return;
    saver.push({
      v: 1, open, active, connected: [...connected].filter((id) => open.includes(id)), workspaces: tabs,
      collapsed: [...collapsed], sidebarWidth,
    });
  }, [restored, open, active, connected, tabs, collapsed, sidebarWidth, saver]);
  useEffect(() => {
    const flush = () => saver.flush();
    window.addEventListener("pagehide", flush);
    return () => window.removeEventListener("pagehide", flush);
  }, [saver]);

  const onTabChange = useCallback((id: string, tab: string) => setTabs((t) => ({ ...t, [id]: { tab } })), []);

  const openConn = (c: ConnectionConfig) => {
    restoredState.current = { ...restoredState.current, open: restoredState.current.open.filter((x) => x !== c.id) };
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

  // Left pane width: drag the divider or use the arrow keys on it.
  const startResize = (e: React.PointerEvent) => {
    e.preventDefault();
    const move = (ev: PointerEvent) => setSidebarWidth(clampSidebar(ev.clientX));
    const up = () => { window.removeEventListener("pointermove", move); window.removeEventListener("pointerup", up); };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
  };
  const resizeKey = (e: React.KeyboardEvent) => {
    const step = e.shiftKey ? 50 : 10;
    if (e.key === "ArrowLeft") setSidebarWidth((w) => clampSidebar(w - step));
    else if (e.key === "ArrowRight") setSidebarWidth((w) => clampSidebar(w + step));
    else return;
    e.preventDefault();
  };

  return (
    <div className="app" style={{ gridTemplateColumns: `${sidebarWidth}px 1fr` }}>
      <header className="topbar">
        <span className="brand">Cassandra Studio</span>
        <span className="muted">{info ? `v${info.version}` : ""}</span>
        <span className="spacer" />
        <button className="btn small" onClick={() => setActive("audit")}>Audit log</button>
        <button className="btn small" onClick={() => api.exportConnections().then((d) => download("cassandra-studio-connections.json", JSON.stringify(d, null, 2), "application/json")).catch(toast.error)}>Export connections</button>
        <button className="btn small" onClick={() => importInput.current?.click()}>Import</button>
        <button className="btn small" onClick={() => setStudioData(true)} title="Back up or restore Studio's settings, copy diagnostics">Studio data</button>
        <input ref={importInput} type="file" accept=".json" hidden onChange={(e) => e.target.files?.[0] && importFile(e.target.files[0])} />
        <button className="btn small" aria-label="Toggle dark mode" onClick={() => setTheme(theme === "dark" ? "light" : "dark")}>
          {theme === "dark" ? "☀" : "☾"}
        </button>
      </header>
      <aside className="sidebar">
        <ConnectionTree
          collapsed={collapsed}
          onCollapsedChange={setCollapsed}
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
      <div
        className="pane-resizer"
        role="separator"
        aria-orientation="vertical"
        aria-label="Resize connections pane"
        aria-valuemin={SIDEBAR_MIN}
        aria-valuemax={SIDEBAR_MAX}
        aria-valuenow={sidebarWidth}
        tabIndex={0}
        onPointerDown={startResize}
        onKeyDown={resizeKey}
        onDoubleClick={() => setSidebarWidth(clampSidebar(undefined))}
        style={{ position: "fixed", top: 40, bottom: 0, left: sidebarWidth - 3, width: 6, cursor: "col-resize", zIndex: 5 }}
      />
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
                <ViewBoundary name={c.name}>
                  <Workspace
                    conn={c}
                    dark={theme === "dark"}
                    onConnected={onConnected}
                    initialTab={tabs[id]?.tab}
                    onTabChange={onTabChange}
                    autoConnect={!restoredState.current.open.includes(id) || autoConnectOnRestore(restoredState.current, id, c.environment)}
                  />
                </ViewBoundary>
              </div>
            ) : null;
          })}
          {active === "audit" && <ViewBoundary name="Audit log"><AuditPanel /></ViewBoundary>}
          {!activeConn && active !== "audit" && (
            <div className="empty">
              <h2>Cassandra Studio</h2>
              <p>Double-click a connection on the left to open it, or create one with “+ Connection”.</p>
            </div>
          )}
        </div>
      </main>
      {studioData && <StudioDataDialog onClose={() => setStudioData(false)} onRestored={reload} />}
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
