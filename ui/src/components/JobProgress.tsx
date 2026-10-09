import { useEffect, useState } from "react";
import { jobsApi } from "../lib/jobsApi";
import { jobDone, type Job } from "../lib/jobsTypes";
import { errorText } from "./feedback";

/** Polls a job every second until it is done. */
export function useJob(jobId: string | null, onDone?: (j: Job) => void): { job: Job | null; error: string | null } {
  const [job, setJob] = useState<Job | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    setJob(null);
    setError(null);
    if (!jobId) return;
    let stop = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = () => {
      jobsApi.get(jobId).then((j) => {
        if (stop) return;
        setJob(j);
        if (jobDone(j)) onDone?.(j);
        else timer = setTimeout(tick, 1000);
      }).catch((e) => { if (!stop) setError(errorText(e)); });
    };
    tick();
    return () => { stop = true; if (timer) clearTimeout(timer); };
  }, [jobId]); // eslint-disable-line react-hooks/exhaustive-deps
  return { job, error };
}

/** Shared progress view for any long-running job: state, bar, message, cancel, log. */
export function JobProgress(props: { jobId: string | null; onDone?: (j: Job) => void; showLog?: boolean }) {
  const { job, error } = useJob(props.jobId, props.onDone);
  const [cancelling, setCancelling] = useState(false);
  if (error) return <div className="notice error" role="alert">{error}</div>;
  if (!job) return null;
  const pct = job.progress === null ? null : Math.round(job.progress * 100);
  const cls = job.state === "SUCCEEDED" ? "ok" : job.state === "FAILED" ? "error" : job.state === "CANCELLED" ? "skipped" : "";
  return (
    <div className="job" data-testid="job-progress" data-state={job.state}>
      <div className="job-head">
        <span className={"status " + cls}>{job.state}</span>
        <span className="job-title">{job.title}</span>
        {!jobDone(job) && job.cancellable && (
          <button className="btn small" disabled={cancelling}
            onClick={() => { setCancelling(true); jobsApi.cancel(job.id).catch(() => setCancelling(false)); }}>Cancel</button>
        )}
      </div>
      <div className="job-bar" role="progressbar" aria-label={job.title} aria-valuemin={0} aria-valuemax={100}
        aria-valuenow={pct ?? undefined} aria-busy={!jobDone(job)}>
        <span className={"job-bar-fill" + (pct === null && !jobDone(job) ? " indeterminate" : "")}
          style={{ width: (pct ?? (jobDone(job) ? 100 : 30)) + "%" }} />
      </div>
      {(job.message || job.error) && <div className={job.error ? "job-error" : "muted"}>{job.error ?? job.message}</div>}
      {props.showLog !== false && job.log.length > 0 && (
        <details className="job-log"><summary>Log ({job.log.length} lines)</summary>
          {/* focusable so keyboard users can scroll it */}
          <pre tabIndex={0} aria-label={`Log of ${job.title}`}>{job.log.join("\n")}</pre></details>
      )}
    </div>
  );
}
