import { useCallback, useEffect, useRef, useState } from "react";
import type { MonitoringClient } from "../../lib/monitoringApi";
import { isNotStarted } from "../../lib/monitoringApi";
import type { AccessStatus, ClusterSnapshot } from "../../lib/monitoringTypes";

export interface MonitoringState {
  snapshot: ClusterSnapshot | null;
  status: AccessStatus | null;
  error: unknown;
  paused: boolean;
  /** Increments on every successful poll; views refresh their data on it. */
  tick: number;
  pause: () => void;
  resume: () => void;
  refresh: () => void;
}

/**
 * Starts monitoring (idempotent) and polls the snapshot every interval while the document
 * is visible. Unmounting only stops polling here: other panels may share the engine poller,
 * so stop is called only by the explicit Pause.
 */
export function useMonitoring(client: MonitoringClient, intervalSec: number): MonitoringState {
  const [snapshot, setSnapshot] = useState<ClusterSnapshot | null>(null);
  const [status, setStatus] = useState<AccessStatus | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [paused, setPaused] = useState(false);
  const [tick, setTick] = useState(0);
  const poll = useRef<() => void>(() => undefined);

  useEffect(() => {
    if (paused) return;
    let alive = true;
    let inFlight = false;
    const run = () => {
      if (!alive || inFlight || document.visibilityState !== "visible") return;
      inFlight = true;
      Promise.all([
        client.snapshot(intervalSec).then((s) => { if (alive) { setSnapshot(s); setError(null); setTick((t) => t + 1); } }),
        client.status().then((s) => alive && setStatus(s)).catch(() => undefined),
      ])
        .catch((e) => { if (alive && !isNotStarted(e)) setError(e); }) // not_started right after start: wait for the first poll
        .finally(() => { inFlight = false; });
    };
    poll.current = run;
    client.start(intervalSec)
      .then((s) => alive && setStatus(s))
      .catch((e) => alive && setError(e))
      .finally(run);
    const timer = setInterval(run, intervalSec * 1000);
    const onVisible = () => document.visibilityState === "visible" && run();
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      alive = false;
      clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
      poll.current = () => undefined;
    };
  }, [client, intervalSec, paused]);

  const pause = useCallback(() => {
    setPaused(true);
    client.stop().then(() => client.status()).then(setStatus).catch(setError);
  }, [client]);
  const resume = useCallback(() => setPaused(false), []);
  const refresh = useCallback(() => poll.current(), []);

  return { snapshot, status, error, paused, tick, pause, resume, refresh };
}
