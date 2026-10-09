import { request } from "./api";
import type { Job } from "./jobsTypes";

const enc = encodeURIComponent;

export const jobsApi = {
  list: (connectionId?: string) =>
    request<Job[]>("GET", "/api/jobs" + (connectionId ? `?connectionId=${enc(connectionId)}` : "")),
  get: (jobId: string) => request<Job>("GET", `/api/jobs/${enc(jobId)}`),
  cancel: (jobId: string) => request<Job>("POST", `/api/jobs/${enc(jobId)}/cancel`),
};
