// Client and shapes of the bulk API (docs/api/bulk.md, engine/.../bulk).
import { request } from "../../lib/api";
import type { Job } from "../../lib/jobsTypes";
import type { Confirmation } from "../../components/feedback";

const enc = encodeURIComponent;

export interface BulkStats {
  kind: "unload" | "load";
  path: string;
  target: string;
  rowsRead: number;
  rowsWritten: number;
  rejected: number;
  bytes: number;
  totalBytes: number | null;
  rangesDone: number;
  rangesFailed: number;
  rangesTotal: number | null;
  estimatedRows: number | null;
  rowsPerSecond: number;
  elapsedMs: number;
  errorFile: string | null;
  rejectFile: string | null;
  dryRun: boolean;
}

export interface BulkJob { job: Job; stats: BulkStats | null }

/** Shared text options (both directions). */
export interface TextOptions {
  delimiter?: string;
  nullString?: string;
  header?: boolean;
  timestampFormat?: string;
  dateFormat?: string;
  timeZone?: string;
  blobFormat?: "hex" | "base64";
}

export interface UnloadRequest extends TextOptions {
  mode: "table" | "query";
  keyspace?: string;
  table?: string;
  columns?: string[];
  query?: string;
  path: string;
  overwrite?: boolean;
  format: "csv" | "json";
  compression: "none" | "gzip";
  consistency?: string;
  pageSize?: number;
  concurrency?: number;
  maxRows?: number;
}

export interface Mapping { column: string; source: string }

export interface LoadRequest extends TextOptions {
  keyspace: string;
  table: string;
  path: string;
  format?: "csv" | "json";
  compression?: "auto" | "none" | "gzip";
  mapping?: Mapping[];
  ttlSeconds?: number | null;
  ttlField?: string | null;
  timestampMicros?: number | null;
  timestampField?: string | null;
  batchSize?: number;
  concurrency?: number;
  rateLimit?: number;
  maxErrors?: number;
  dryRun?: boolean;
  consistency?: string;
}

export interface TableColumn { name: string; type: string; kind: "partition_key" | "clustering" | "regular" }

export interface LoadPreview {
  path: string;
  format: "csv" | "json";
  gzip: boolean;
  sizeBytes: number | null;
  estimatedRows: number | null;
  fileColumns: string[];
  sampleRows: (string | null)[][];
  tableColumns?: TableColumn[];
  mapping?: Mapping[];
  /** Set when the table cannot be loaded (counter table). */
  error?: string | null;
}

export const bulkApi = {
  defaults: (id: string) => request<{ downloadsDir: string; separator: string }>("GET", `/api/clusters/${enc(id)}/bulk/defaults`),
  unload: (id: string, req: UnloadRequest) => request<Job>("POST", `/api/clusters/${enc(id)}/bulk/unload`, req),
  preview: (id: string, req: Partial<LoadRequest> & { path: string }) =>
    request<LoadPreview>("POST", `/api/clusters/${enc(id)}/bulk/load/preview`, req),
  load: (id: string, req: LoadRequest, c: Confirmation = {}) =>
    request<Job>("POST", `/api/clusters/${enc(id)}/bulk/load`, { ...req, ...c }),
  jobs: (id: string) => request<BulkJob[]>("GET", `/api/clusters/${enc(id)}/bulk/jobs`),
};
