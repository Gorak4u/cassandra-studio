// Phase 3 contract: mirrors engine/.../jobs/Job.java (docs/api/jobs.md).
export type JobState = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";

export interface Job {
  id: string; connectionId: string | null; kind: string; title: string; node: string | null; state: JobState;
  /** 0..1, or null when the task cannot tell. */
  progress: number | null; message: string | null;
  /** Last 500 lines, oldest first. */
  log: string[];
  result: unknown; error: string | null;
  createdAtMs: number; startedAtMs: number | null; finishedAtMs: number | null; cancellable: boolean;
}

export function jobDone(j: Job): boolean {
  return j.state === "SUCCEEDED" || j.state === "FAILED" || j.state === "CANCELLED";
}
