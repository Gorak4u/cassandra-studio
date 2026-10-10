// Studio's own state (docs/api/studio.md): remembered layout, settings backup/restore, audit export
// and local diagnostics. Nothing here leaves the machine.
import { request } from "./api";

/** What Studio remembers between launches (NFR-UX). Unknown or stale ids are ignored on restore. */
export interface UiState {
  v: 1;
  /** Open connection tabs, in order. */
  open: string[];
  /** Active tab: a connection id, "audit", or null. */
  active: string | null;
  /** Tabs whose cluster was connected when the state was saved (PROD tabs reconnect only if listed). */
  connected: string[];
  /** Per connection: the workspace tab (overview, monitoring, query ...). */
  workspaces: Record<string, { tab?: string }>;
  /** Folder ids collapsed in the connection tree. */
  collapsed: string[];
  sidebarWidth?: number;
}

export const EMPTY_UI_STATE: UiState = { v: 1, open: [], active: null, connected: [], workspaces: {}, collapsed: [] };

/** Reads a stored state defensively: anything malformed falls back to the empty state. */
export function parseUiState(raw: unknown): UiState {
  if (!raw || typeof raw !== "object") return EMPTY_UI_STATE;
  const r = raw as Partial<UiState>;
  const strings = (x: unknown) => (Array.isArray(x) ? x.filter((s): s is string => typeof s === "string") : []);
  const ws: UiState["workspaces"] = {};
  if (r.workspaces && typeof r.workspaces === "object") {
    for (const [k, w] of Object.entries(r.workspaces)) {
      if (w && typeof w === "object" && typeof (w as { tab?: unknown }).tab === "string") ws[k] = { tab: (w as { tab: string }).tab };
    }
  }
  const width = typeof r.sidebarWidth === "number" && Number.isFinite(r.sidebarWidth) ? r.sidebarWidth : undefined;
  return {
    v: 1,
    open: strings(r.open),
    active: typeof r.active === "string" ? r.active : null,
    connected: strings(r.connected),
    workspaces: ws,
    collapsed: strings(r.collapsed),
    sidebarWidth: width,
  };
}

/** Keeps only what still exists: tabs of deleted connections and deleted folders are dropped. */
export function pruneUiState(s: UiState, connectionIds: Set<string>, folderIds: Set<string>): UiState {
  const open = [...new Set(s.open)].filter((id) => connectionIds.has(id));
  const active = s.active === "audit" || (s.active && open.includes(s.active)) ? s.active : open[0] ?? null;
  const workspaces = Object.fromEntries(Object.entries(s.workspaces).filter(([id]) => connectionIds.has(id)));
  return {
    ...s,
    open,
    active,
    connected: s.connected.filter((id) => open.includes(id)),
    workspaces,
    collapsed: s.collapsed.filter((id) => folderIds.has(id)),
  };
}

/**
 * Whether a restored tab connects by itself. Non-PROD tabs do (as when they were opened); a PROD
 * tab only when its cluster was connected when Studio closed, otherwise it waits for an explicit
 * Connect, so a launch never opens a session to production on its own.
 */
export function autoConnectOnRestore(s: UiState, id: string, environment: string): boolean {
  return environment !== "PROD" || s.connected.includes(id);
}

export const SIDEBAR_MIN = 200;
export const SIDEBAR_MAX = 640;
export const SIDEBAR_DEFAULT = 280;
export function clampSidebar(w: number | undefined): number {
  if (w === undefined || !Number.isFinite(w)) return SIDEBAR_DEFAULT;
  return Math.round(Math.min(SIDEBAR_MAX, Math.max(SIDEBAR_MIN, w)));
}

/** Calls {@code save} with the latest value at most once per {@code ms}; flush() sends a pending one now. */
export function debounced<T>(save: (v: T) => void, ms: number) {
  let timer: ReturnType<typeof setTimeout> | null = null;
  let pending: { v: T } | null = null;
  const flush = () => {
    if (timer) clearTimeout(timer);
    timer = null;
    if (pending) {
      const p = pending;
      pending = null;
      save(p.v);
    }
  };
  return {
    push(v: T) {
      pending = { v };
      if (timer) clearTimeout(timer);
      timer = setTimeout(flush, ms);
    },
    flush,
  };
}

export interface BackupPreview {
  folders: { added: number; conflicting: number };
  connections: { added: number; conflicting: number };
  scripts: { added: number; conflicting: number };
  settings: { added: number; conflicting: number };
  hasSecrets: boolean;
}

export interface RestoreResult {
  folders: number; connections: number; scripts: number; settings: number; skipped: number; secrets: number; notes: string[];
}

export type Conflict = "skip" | "replace" | "keep_both";

export interface Diagnostics {
  studioVersion: string; dbVersion: number; os: string; java: string; uptimeSec: number; heapUsedMb: number; heapMaxMb: number;
  threads: number; processors: number; secretStore: string; savedConnections: number; crashLog: string | null;
  recentErrors: { at: string; source: string; message: string }[];
}

/** Plain-text diagnostics for a support ticket: versions, platform and recent errors; no secrets, no hosts. */
export function diagnosticsText(d: Diagnostics, ui: { userAgent: string; uiErrors: string[] }): string {
  const lines = [
    `Cassandra Studio ${d.studioVersion} (database schema v${d.dbVersion})`,
    `OS: ${d.os}`,
    `Java: ${d.java}`,
    `Engine: up ${d.uptimeSec} s, heap ${d.heapUsedMb}/${d.heapMaxMb} MB, ${d.threads} threads, ${d.processors} CPUs`,
    `Secret store: ${d.secretStore}; saved connections: ${d.savedConnections}`,
    `UI: ${ui.userAgent}`,
    `Crash log: ${d.crashLog ?? "not enabled"}`,
    "",
    `Recent errors (${d.recentErrors.length + ui.uiErrors.length}):`,
    ...d.recentErrors.map((e) => `  ${e.at} [${e.source}] ${e.message}`),
    ...ui.uiErrors.map((e) => `  [ui, this window] ${e}`),
  ];
  return lines.join("\n");
}

const enc = encodeURIComponent;

export const studioApi = {
  uiState: () => request<unknown>("GET", "/api/ui-state").then(parseUiState),
  saveUiState: (s: UiState) => request<void>("PUT", "/api/ui-state", s),
  backup: (passphrase: string | null) =>
    request<unknown>("POST", "/api/studio/backup", passphrase ? { includeSecrets: true, passphrase } : {}),
  previewRestore: (file: unknown) => request<BackupPreview>("POST", "/api/studio/restore", { dryRun: true, file }),
  restore: (file: unknown, conflict: Conflict, passphrase: string | null) =>
    request<RestoreResult>("POST", "/api/studio/restore", { file, conflict, passphrase }),
  /** The audit export as file content (CSV text or JSON text), same filters as the Audit log search. */
  auditExport: async (format: "csv" | "json", q: string): Promise<string> => {
    const path = `/api/audit/export?format=${format}&q=${enc(q)}`;
    if (format === "json") return JSON.stringify(await request<unknown>("GET", path), null, 2);
    const token = sessionStorage.getItem("studio.token");
    const base = (import.meta.env.VITE_ENGINE_URL as string | undefined) ?? "";
    const res = await fetch(base + path, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
    if (!res.ok) throw new Error(`Audit export failed (${res.status})`);
    return res.text();
  },
  diagnostics: () => request<Diagnostics>("GET", "/api/diagnostics"),
  reportUiError: (e: { message: string; stack?: string; componentStack?: string }) =>
    request<void>("POST", "/api/diagnostics/ui-error", e),
};
