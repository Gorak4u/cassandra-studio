// Operations API (docs/api/ops.md); shapes mirror engine/.../ops/OpsModel.java.
import { request } from "../../lib/api";
import type { Job } from "../../lib/jobsTypes";
import type { Confirmation } from "../../components/feedback";

export interface Section { title: string; columns: string[]; rows: (string | null)[][]; keyValue: boolean }
export interface OpsView { view: string; node: string; command: string; sections: Section[]; notes: string[] }
export interface Snapshot {
  node: string; tag: string; keyspace: string; table: string; trueSize: string | null; sizeOnDisk: string | null;
  trueSizeBytes: number | null; sizeOnDiskBytes: number | null; createdAt: string | null; expiresAt: string | null;
}
export interface SnapshotList { snapshots: Snapshot[]; errors: { node: string; error: string }[] }

export type ViewName = "status" | "info" | "ring" | "describecluster" | "tpstats" | "tablestats" | "tablehistograms"
  | "proxyhistograms" | "gossipinfo" | "compactionstats" | "netstats" | "getendpoints";

export const VIEWS: { id: ViewName; label: string; cluster?: boolean; needs?: ("keyspace" | "table" | "key")[]; optional?: ("keyspace" | "table")[] }[] = [
  { id: "status", label: "Status", cluster: true, optional: ["keyspace"] },
  { id: "info", label: "Info" },
  { id: "ring", label: "Ring", cluster: true, optional: ["keyspace"] },
  { id: "describecluster", label: "Describe cluster", cluster: true },
  { id: "tpstats", label: "Thread pools" },
  { id: "tablestats", label: "Table stats", optional: ["keyspace", "table"] },
  { id: "tablehistograms", label: "Table histograms", needs: ["keyspace", "table"] },
  { id: "proxyhistograms", label: "Proxy histograms" },
  { id: "gossipinfo", label: "Gossip info" },
  { id: "compactionstats", label: "Compactions" },
  { id: "netstats", label: "Net stats" },
  { id: "getendpoints", label: "Get endpoints", needs: ["keyspace", "table", "key"] },
];

export type MaintenanceKind = "flush" | "compact" | "usercompact" | "cleanup" | "scrub" | "upgradesstables" | "garbagecollect";

export interface MaintenanceBody {
  nodes: string[]; keyspace?: string; tables?: string[]; splitOutput?: boolean; jobs?: number; disableSnapshot?: boolean;
  skipCorrupted?: boolean; noValidate?: boolean; reinsertOverflowedTtl?: boolean; includeAll?: boolean;
  granularity?: "ROW" | "CELL"; files?: string[]; continueOnError?: boolean;
}

export interface RepairBody {
  nodes: string[]; keyspace: string; tables?: string[]; mode: "full" | "incremental"; primaryRange?: boolean;
  dataCenters?: string[]; parallelism?: "parallel" | "sequential" | "dc_parallel"; ranges?: { start: string; end: string }[];
  jobThreads?: number; continueOnError?: boolean;
}

const enc = encodeURIComponent;

export function opsClient(id: string) {
  const base = `/api/clusters/${enc(id)}/ops`;
  return {
    view: (view: ViewName, p: { node?: string; keyspace?: string; table?: string; key?: string }) => {
      const q = Object.entries(p).filter(([, v]) => v !== undefined && v !== "").map(([k, v]) => `${k}=${enc(v as string)}`).join("&");
      return request<OpsView>("GET", `${base}/views/${view}${q ? "?" + q : ""}`);
    },
    maintenance: (kind: MaintenanceKind, body: MaintenanceBody, c: Confirmation) =>
      request<Job>("POST", `${base}/actions/${kind}`, { ...body, ...c }),
    repair: (body: RepairBody, c: Confirmation) => request<Job>("POST", `${base}/repair`, { ...body, ...c }),
    snapshots: (node?: string) => request<SnapshotList>("GET", `${base}/snapshots${node ? "?node=" + enc(node) : ""}`),
    takeSnapshot: (body: { nodes: string[]; tag: string; keyspaces?: string[]; tables?: string[]; skipFlush?: boolean }, c: Confirmation) =>
      request<Job>("POST", `${base}/snapshots`, { ...body, ...c }),
    clearSnapshot: (body: { nodes: string[]; tag?: string; keyspaces?: string[]; all?: boolean }, c: Confirmation) =>
      request<Job>("POST", `${base}/snapshots/clear`, { ...body, ...c }),
  };
}

export type OpsClient = ReturnType<typeof opsClient>;

/** Job kinds this panel starts (for the recent jobs list). */
export const OPS_JOB_KINDS = new Set(["flush", "compact", "usercompact", "cleanup", "scrub", "upgradesstables",
  "garbagecollect", "repair", "snapshot", "clearsnapshot"]);
