// Mirrors engine/.../config/ConfigModel.java (docs/api/config.md).
import { request } from "../../lib/api";
import type { Job } from "../../lib/jobsTypes";

export type Category = "yaml" | "jvm" | "os";

export interface Setting { category: Category; name: string; value: string | null; rawName: string; raw: string | null; source: string }
export interface NodeConfig {
  address: string; hostId: string | null; datacenter: string | null; rack: string | null; version: string | null;
  sources: Record<string, string>; settings: Setting[]; notices: string[];
}
export interface Snapshot { collectedAtMs: number; nodes: NodeConfig[] }
export interface NodeRef { address: string; datacenter: string | null; rack: string | null; version: string | null }
export interface DriftRow {
  category: Category; name: string; perNode: boolean; values: Record<string, string | null>;
  differsInCluster: boolean; dcsDiffering: string[]; missingOn: string[];
  expected: Record<string, string> | null; expectedSource: Record<string, string> | null; mismatches: string[];
}
export interface DriftSummary { settings: number; differInCluster: number; differInDc: number; hieraCompared: number; hieraMismatches: number }
export interface HieraStatus { enabled: boolean; error: string | null; layersByNode: Record<string, string[]>; mappedKeys: number }
export interface DriftReport {
  scope: "cluster" | "dc"; onlyDifferences: boolean; collectedAtMs: number; nodes: NodeRef[]; rows: DriftRow[];
  summary: DriftSummary; hiera: HieraStatus;
}
export interface HieraSettings { enabled: boolean; repoPath: string; facts: Record<string, string>; certnames: Record<string, string> }
export interface HieraOptions { repoPath: string; found: boolean; error: string | null; values: Record<string, string[]>; variables: string[] }

export const CATEGORY_LABEL: Record<Category, string> = { yaml: "cassandra.yaml", jvm: "JVM", os: "OS" };

/** Friendly Hiera facts offered in the settings form (the engine maps them to hierarchy variables). */
export const HIERA_FACTS: { key: string; label: string; hint?: string }[] = [
  { key: "customer", label: "Customer" },
  { key: "environment", label: "Environment" },
  { key: "product", label: "Product" },
  { key: "cluster", label: "Cluster id" },
  { key: "datacenter", label: "Datacenter", hint: "blank = each node's own DC" },
  { key: "role", label: "Role" },
  { key: "os.name", label: "OS name" },
  { key: "os.family", label: "OS family" },
  { key: "os.release.major", label: "OS major release" },
];

const enc = encodeURIComponent;

export const configApi = {
  collect: (id: string) => request<Job>("POST", `/api/clusters/${enc(id)}/config/collect`, {}),
  snapshot: (id: string) => request<Snapshot>("GET", `/api/clusters/${enc(id)}/config/snapshot`),
  drift: (id: string, scope: "cluster" | "dc", onlyDifferences: boolean, hiera: boolean) =>
    request<DriftReport>("GET", `/api/clusters/${enc(id)}/config/drift?scope=${scope}&onlyDifferences=${onlyDifferences}&hiera=${hiera}`),
  hiera: (id: string) => request<HieraSettings>("GET", `/api/clusters/${enc(id)}/config/hiera`),
  saveHiera: (id: string, s: HieraSettings) => request<HieraSettings>("PUT", `/api/clusters/${enc(id)}/config/hiera`, s),
  hieraOptions: (id: string, repoPath?: string) =>
    request<HieraOptions>("GET", `/api/clusters/${enc(id)}/config/hiera/options${repoPath ? "?repoPath=" + enc(repoPath) : ""}`),
};

/** One row per setting across nodes, for the settings table: values by node, and whether they differ. */
export interface GridRow { key: string; category: Category; name: string; values: Record<string, Setting | undefined>; differs: boolean }

export function settingsGrid(snap: Snapshot): GridRow[] {
  const rows = new Map<string, GridRow>();
  for (const n of snap.nodes) {
    for (const s of n.settings) {
      const key = s.category + "|" + s.name;
      let r = rows.get(key);
      if (!r) { r = { key, category: s.category, name: s.name, values: {}, differs: false }; rows.set(key, r); }
      r.values[n.address] = s;
    }
  }
  const order: Record<Category, number> = { yaml: 0, jvm: 1, os: 2 };
  const out = [...rows.values()];
  for (const r of out) {
    const present = snap.nodes.map((n) => r.values[n.address]).filter((s): s is Setting => !!s);
    r.differs = new Set(present.map((s) => s.value ?? "\u0000")).size > 1;
  }
  return out.sort((a, b) => order[a.category] - order[b.category] || a.name.localeCompare(b.name));
}

/** CSV columns for the drift report: setting, category, one column per node, expected, status. */
export function driftCsvRows(r: DriftReport): { columns: string[]; rows: (string | null)[][] } {
  const columns = ["category", "setting", ...r.nodes.map((n) => `${n.address} (${n.datacenter ?? "?"})`), "expected (Hiera)", "status"];
  const rows = r.rows.map((row) => [
    row.category, row.name,
    ...r.nodes.map((n) => (row.missingOn.includes(n.address) ? "(not reported)" : row.values[n.address] ?? "null")),
    expectedText(row),
    statusText(row),
  ]);
  return { columns, rows };
}

export function expectedText(row: DriftRow): string {
  if (!row.expected) return "";
  const distinct = [...new Set(Object.values(row.expected))];
  return distinct.length === 1 ? distinct[0] : Object.entries(row.expected).map(([a, v]) => `${a}=${v}`).join("; ");
}

export function statusText(row: DriftRow): string {
  const parts: string[] = [];
  if (row.perNode) parts.push("per node (ignored)");
  if (row.differsInCluster) parts.push("differs in cluster");
  if (row.dcsDiffering.length) parts.push("differs in " + row.dcsDiffering.join(", "));
  if (row.mismatches.length) parts.push("not as in Hiera on " + row.mismatches.join(", "));
  return parts.join("; ") || "same";
}
