import { ApiError, request } from "../../lib/api";
import type { Job } from "../../lib/jobsTypes";
import type { AnalysisInfo, Discovery, GcReport } from "./gclogTypes";

const enc = encodeURIComponent;
const base = (id: string) => `/api/clusters/${enc(id)}/gclog`;

/** GC log API (docs/api/gclog.md). */
export const gclogApi = {
  discover: (id: string, node: string) => request<Discovery>("GET", `${base(id)}/files?node=${enc(node)}`),
  fetch: (id: string, node: string, paths: string[], maxMB?: number, javaVersion?: string) =>
    request<Job>("POST", `${base(id)}/fetch`, { node, paths, ...(maxMB ? { maxMB } : {}), ...(javaVersion ? { javaVersion } : {}) }),
  list: (id: string) => request<AnalysisInfo[]>("GET", `${base(id)}/analyses`),
  report: (id: string, aid: string, range?: { fromX: number; toX: number }) =>
    request<GcReport>("GET", `${base(id)}/analyses/${enc(aid)}` + (range ? `?fromX=${range.fromX}&toX=${range.toX}` : "")),
  remove: (id: string, aid: string) => request<void>("DELETE", `${base(id)}/analyses/${enc(aid)}`),
  /** Sends the file as the raw request body (plain text, .gz or .zip; the engine detects which). */
  upload: async (id: string, file: Blob, name: string): Promise<AnalysisInfo> => {
    const token = sessionStorage.getItem("studio.token");
    const root = (import.meta.env.VITE_ENGINE_URL as string | undefined) ?? "";
    const res = await fetch(`${root}${base(id)}/upload?name=${enc(name)}`, {
      method: "POST",
      headers: { "Content-Type": "application/octet-stream", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: file,
    });
    const text = await res.text();
    let json: unknown;
    try {
      json = text ? JSON.parse(text) : undefined;
    } catch {
      json = undefined;
    }
    if (!res.ok) {
      const j = (json ?? {}) as { error?: string; message?: string };
      throw new ApiError(res.status, j.error ?? "http_" + res.status, j.message ?? (text || res.statusText));
    }
    return json as AnalysisInfo;
  },
};
