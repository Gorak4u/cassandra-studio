// Backups API (docs/api/backup.md); shapes mirror engine/.../backup/*.java.
import { request } from "../../lib/api";
import type { Job } from "../../lib/jobsTypes";

export type Provider = "ESTATE" | "MEDUSA" | "SNAPSHOT";
export type Privilege = "SUDO" | "NONE";
export type Scope = "CLUSTER" | "DC" | "NODE";

export interface BackupSettings {
  provider: Provider | null; scriptDir: string; configFile: string; privilege: Privilege;
  medusaCommand: string; medusaConfig: string | null; nodeTimeoutMinutes: number;
}

export interface NodeDetection {
  node: string; datacenter: string | null; state: string; reachable: boolean; error: string | null; host: string | null;
  scripts: string[]; scriptsOnPath: string | null; configPresent: boolean; configReadable: boolean;
  medusa: string | null; medusaConfig: string | null; sudo: boolean;
}

export interface Detection {
  nodes: NodeDetection[]; jmxSnapshots: boolean; jmxError: string | null; recommended: Provider | null;
  recommendedPrivilege: Privilege; recommendedScriptDir: string; notes: string[];
}

/** One catalogue row; null = the provider does not tell (shown as "unknown"). */
export interface BackupEntry {
  id: string; provider: string; node: string | null; host: string | null; datacenter: string | null; type: string;
  timeMs: number | null; sizeBytes: number | null; schemaVersion: string | null;
  status: "COMPLETE" | "INCOMPLETE" | "UNKNOWN"; statusDetail: string | null; location: string | null;
  retention: string | null; expiresAtMs: number | null; objectLock: string | null; lockedUntilMs: number | null;
  tables: number | null; objects: number | null; notes: string | null;
}

export interface NodeListing { node: string; datacenter: string | null; ok: boolean; error: string | null; backups: number; method: string | null }

export interface Catalogue { provider: Provider; generatedAtMs: number; backups: BackupEntry[]; nodes: NodeListing[]; notes: string[] }

export interface RunRequest {
  scope: Scope; datacenter?: string | null; node?: string | null; mode: string; concurrency: number;
  throttle?: string | null; name?: string | null; keyspaces?: string[];
}

export interface NodeResult {
  node: string; datacenter: string | null; state: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED" | "SKIPPED";
  progress: number | null; message: string | null; command: string | null; backupId: string | null;
  exitCode: number | null; summary: string | null; startedAtMs: number | null; finishedAtMs: number | null;
}

export interface RunStatus { jobId: string; provider: Provider; mode: string; concurrency: number; nodes: NodeResult[] }

type Confirmation = { confirmed?: boolean; confirmName?: string | null };

const enc = encodeURIComponent;
const base = (id: string) => `/api/clusters/${enc(id)}/backup`;

export const backupApi = {
  settings: (id: string) => request<BackupSettings>("GET", base(id) + "/settings"),
  saveSettings: (id: string, s: BackupSettings) => request<BackupSettings>("PUT", base(id) + "/settings", s),
  detect: (id: string) => request<Detection>("POST", base(id) + "/detect"),
  catalogue: (id: string) => request<Catalogue>("GET", base(id) + "/catalogue"),
  run: (id: string, r: RunRequest, c: Confirmation) => request<Job>("POST", base(id) + "/run", { ...r, ...c }),
  runStatus: (id: string, jobId: string) => request<RunStatus>("GET", base(id) + "/runs/" + enc(jobId)),
  clearSnapshot: (id: string, node: string, tag: string, c: Confirmation) =>
    request<{ cleared: boolean }>("POST", base(id) + "/snapshots/clear", { node, tag, ...c }),
};

export const PROVIDER_LABEL: Record<Provider, string> = {
  ESTATE: "Estate backup scripts",
  MEDUSA: "Cassandra Medusa",
  SNAPSHOT: "Snapshots only (JMX)",
};

/** Run modes each provider offers, first = default. */
export const MODES: Record<Provider, { value: string; label: string }[]> = {
  ESTATE: [{ value: "full", label: "Full (full-backup-to-s3.sh)" }, { value: "incremental", label: "Incremental (incremental-backup-to-s3.sh)" }],
  MEDUSA: [{ value: "full", label: "Full (backup-node --mode full)" }, { value: "differential", label: "Differential" }],
  SNAPSHOT: [{ value: "snapshot", label: "Snapshot" }],
};

export function formatBytes(b: number | null): string {
  if (b === null || b === undefined) return "unknown";
  const units = ["B", "KiB", "MiB", "GiB", "TiB"];
  let v = b;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return (i === 0 ? String(v) : v.toFixed(v >= 100 ? 0 : 1)) + " " + units[i];
}

export function formatTime(ms: number | null): string {
  if (ms === null || ms === undefined) return "unknown";
  const d = new Date(ms);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** Catalogue filter: "" = any. */
export function filterBackups(list: BackupEntry[], f: { node: string; type: string; status: string }): BackupEntry[] {
  return list.filter((b) => (!f.node || (b.node ?? "cluster") === f.node)
    && (!f.type || b.type === f.type) && (!f.status || b.status === f.status));
}
