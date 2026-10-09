import type {
  AuditEntry,
  ClusterInfo,
  ConfirmDetails,
  ConnectionConfig,
  EngineInfo,
  Folder,
  HistoryEntry,
  KeyspaceDetails,
  Permission,
  QueryRequest,
  RolesView,
  SavedScript,
  SchemaTree,
  ScriptResult,
  SecretName,
  TableDetails,
  TestResult,
} from "./types";

/** An error response from the engine: {error, message, details}. */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly details?: Record<string, unknown>,
  ) {
    super(message);
  }

  get confirmation(): ConfirmDetails | null {
    return this.status === 428 && this.details ? (this.details as unknown as ConfirmDetails) : null;
  }
}

const TOKEN_KEY = "studio.token";

/**
 * The engine's per-launch token arrives in the URL fragment (#token=...), which
 * is never sent to a server or written to history by the browser. It is moved
 * to sessionStorage and removed from the address bar.
 */
export function initToken(): string | null {
  const m = /[#&]token=([^&]+)/.exec(window.location.hash);
  if (m) {
    sessionStorage.setItem(TOKEN_KEY, decodeURIComponent(m[1]));
    history.replaceState(null, "", window.location.pathname + window.location.search);
  }
  return sessionStorage.getItem(TOKEN_KEY);
}

function base(): string {
  return (import.meta.env.VITE_ENGINE_URL as string | undefined) ?? "";
}

/** Authenticated JSON call to the engine; throws ApiError (with 428 confirmation details). */
export async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const token = sessionStorage.getItem(TOKEN_KEY);
  const res = await fetch(base() + path, {
    method,
    headers: {
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  let json: unknown = undefined;
  try {
    json = text ? JSON.parse(text) : undefined;
  } catch {
    // not JSON
  }
  if (!res.ok) {
    const j = (json ?? {}) as { error?: string; message?: string; details?: Record<string, unknown> };
    throw new ApiError(res.status, j.error ?? "http_" + res.status, j.message ?? (text || res.statusText), j.details);
  }
  return json as T;
}

const enc = encodeURIComponent;

export type Secrets = Partial<Record<SecretName, string | null>>;

export const api = {
  info: () => request<EngineInfo>("GET", "/api/info"),

  folders: () => request<Folder[]>("GET", "/api/folders"),
  createFolder: (name: string, parentId?: string | null) => request<Folder>("POST", "/api/folders", { name, parentId }),
  updateFolder: (id: string, patch: { name?: string; parentId?: string | null; position?: number }) =>
    request<Folder>("PUT", `/api/folders/${enc(id)}`, patch),
  deleteFolder: (id: string) => request<void>("DELETE", `/api/folders/${enc(id)}`),

  connections: () => request<ConnectionConfig[]>("GET", "/api/connections"),
  createConnection: (connection: ConnectionConfig, secrets: Secrets) =>
    request<ConnectionConfig>("POST", "/api/connections", { connection, secrets }),
  updateConnection: (id: string, connection: ConnectionConfig, secrets: Secrets) =>
    request<ConnectionConfig>("PUT", `/api/connections/${enc(id)}`, { connection, secrets }),
  deleteConnection: (id: string) => request<void>("DELETE", `/api/connections/${enc(id)}`),
  cloneConnection: (id: string) => request<ConnectionConfig>("POST", `/api/connections/${enc(id)}/clone`),
  testConnection: (connection: ConnectionConfig, secrets: Secrets) =>
    request<TestResult>("POST", "/api/connections/test", { connection, secrets }),
  exportConnections: () => request<unknown>("GET", "/api/connections/export"),
  importConnections: (file: unknown) =>
    request<{ folders: number; connections: number }>("POST", "/api/connections/import", file),
  connect: (id: string) => request<ClusterInfo>("POST", `/api/connections/${enc(id)}/connect`),
  disconnect: (id: string) => request<void>("POST", `/api/connections/${enc(id)}/disconnect`),

  clusterInfo: (id: string) => request<ClusterInfo>("GET", `/api/clusters/${enc(id)}/info`),
  query: (id: string, req: QueryRequest) => request<ScriptResult>("POST", `/api/clusters/${enc(id)}/query`, req),
  history: (id: string, q = "", limit = 200) =>
    request<HistoryEntry[]>("GET", `/api/clusters/${enc(id)}/history?q=${enc(q)}&limit=${limit}`),
  clearHistory: (id: string) => request<void>("DELETE", `/api/clusters/${enc(id)}/history`),
  rowCql: (
    id: string,
    edit: { keyspace: string; table: string; op: "INSERT" | "UPDATE" | "DELETE"; key: Record<string, string>; values?: Record<string, string | null> },
  ) => request<{ cql: string }>("POST", `/api/clusters/${enc(id)}/rows/cql`, edit),

  schema: (id: string, refresh = false) =>
    request<SchemaTree>("GET", `/api/clusters/${enc(id)}/schema${refresh ? "?refresh=true" : ""}`),
  completions: (id: string) =>
    request<Record<string, Record<string, string[]>>>("GET", `/api/clusters/${enc(id)}/schema/completions`),
  keyspace: (id: string, ks: string) =>
    request<KeyspaceDetails>("GET", `/api/clusters/${enc(id)}/schema/keyspaces/${enc(ks)}`),
  table: (id: string, ks: string, table: string) =>
    request<TableDetails>("GET", `/api/clusters/${enc(id)}/schema/keyspaces/${enc(ks)}/tables/${enc(table)}`),
  ddl: (op: string, body: unknown) => request<{ cql: string }>("POST", `/api/ddl/${enc(op)}`, body),

  roles: (id: string) => request<RolesView>("GET", `/api/clusters/${enc(id)}/roles`),
  rolePermissions: (id: string, role: string) =>
    request<Permission[]>("GET", `/api/clusters/${enc(id)}/roles/${enc(role)}/permissions`),
  rolesCql: (op: string, body: unknown) => request<{ cql: string }>("POST", `/api/roles-cql/${enc(op)}`, body),

  scripts: () => request<SavedScript[]>("GET", "/api/scripts"),
  script: (id: string) => request<SavedScript>("GET", `/api/scripts/${enc(id)}`),
  saveScript: (s: { id?: string | null; folder: string; name: string; content: string }) =>
    s.id
      ? request<SavedScript>("PUT", `/api/scripts/${enc(s.id)}`, s)
      : request<SavedScript>("POST", "/api/scripts", s),
  deleteScript: (id: string) => request<void>("DELETE", `/api/scripts/${enc(id)}`),

  audit: (params: { connectionId?: string; q?: string; limit?: number } = {}) =>
    request<AuditEntry[]>(
      "GET",
      `/api/audit?connectionId=${enc(params.connectionId ?? "")}&q=${enc(params.q ?? "")}&limit=${params.limit ?? 500}`,
    ),
};
