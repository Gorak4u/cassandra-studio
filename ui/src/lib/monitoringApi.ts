import { ApiError } from "./api";
import { createMockMonitoring } from "./monitoringMock";
import type { AccessStatus, Alert, ClusterSnapshot, Ring, Series, SeriesMetric, TableMetrics } from "./monitoringTypes";

/**
 * ALR-1 thresholds, keyed by rule and bound (e.g. "heap.high.yellowPct").
 * docs/api/monitoring.md only says "thresholds JSON", so this is a flat map of numbers.
 */
export type Thresholds = Record<string, number | null>;

export interface SeriesQuery { node?: string | null; fromMs?: number; toMs?: number }

/** Every route in docs/api/monitoring.md for one connection. */
export interface MonitoringClient {
  start(intervalSec: number): Promise<AccessStatus>;
  stop(): Promise<void>;
  status(): Promise<AccessStatus>;
  /** Latest poll; on 409 not_started it starts monitoring and asks again. */
  snapshot(intervalSec?: number): Promise<ClusterSnapshot>;
  series(metric: SeriesMetric, q?: SeriesQuery): Promise<Series>;
  ring(keyspace?: string | null): Promise<Ring>;
  tables(keyspace?: string | null): Promise<TableMetrics[]>;
  alerts(): Promise<Alert[]>;
  thresholds(): Promise<Thresholds>;
  saveThresholds(t: Thresholds): Promise<Thresholds>;
}

// api.ts keeps its request helper private, so this is a minimal copy that shares the
// token, base URL and ApiError (no change to api.ts).
const TOKEN_KEY = "studio.token";

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const token = sessionStorage.getItem(TOKEN_KEY);
  const base = (import.meta.env.VITE_ENGINE_URL as string | undefined) ?? "";
  const res = await fetch(base + path, {
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

export function isNotStarted(e: unknown): boolean {
  return e instanceof ApiError && e.status === 409 && e.code === "not_started";
}

/** A 404 without an engine error code means the route itself is missing (engine predates Phase 2). */
export function isMissingRoute(e: unknown): boolean {
  return e instanceof ApiError && e.status === 404 && e.code === "http_404";
}

function qs(params: Record<string, string | number | null | undefined>): string {
  const parts = Object.entries(params)
    .filter(([, v]) => v !== null && v !== undefined && v !== "")
    .map(([k, v]) => `${k}=${enc(String(v))}`);
  return parts.length ? "?" + parts.join("&") : "";
}

/** The engine client, without any fallback. */
export function engineMonitoring(connectionId: string): MonitoringClient {
  const p = `/api/clusters/${enc(connectionId)}/monitoring`;
  const client: MonitoringClient = {
    start: (intervalSec) => request<AccessStatus>("POST", `${p}/start`, { intervalSec }),
    stop: () => request<void>("POST", `${p}/stop`),
    status: () => request<AccessStatus>("GET", `${p}/status`),
    snapshot: async (intervalSec = 10) => {
      try {
        return await request<ClusterSnapshot>("GET", `${p}/snapshot`);
      } catch (e) {
        if (!isNotStarted(e)) throw e;
        await client.start(intervalSec);
        return await request<ClusterSnapshot>("GET", `${p}/snapshot`);
      }
    },
    series: (metric, q = {}) =>
      request<Series>("GET", `${p}/series${qs({ metric, node: q.node, fromMs: q.fromMs, toMs: q.toMs })}`),
    ring: (keyspace) => request<Ring>("GET", `${p}/ring${qs({ keyspace })}`),
    tables: (keyspace) => request<TableMetrics[]>("GET", `${p}/tables${qs({ keyspace })}`),
    alerts: () => request<Alert[]>("GET", `${p}/alerts`),
    thresholds: () => request<Thresholds>("GET", `${p}/thresholds`),
    saveThresholds: (t) => request<Thresholds>("PUT", `${p}/thresholds`, t),
  };
  return client;
}

export function mockRequested(): boolean {
  return import.meta.env.VITE_MONITORING_MOCK === "1";
}

/**
 * The client the panel uses: the engine, switching to demo data for good when
 * VITE_MONITORING_MOCK=1 or the engine has no monitoring routes yet (404).
 */
export function monitoringClient(
  connectionId: string,
  onDemo: () => void = () => undefined,
  opts: { forceMock?: boolean; engine?: MonitoringClient; mock?: MonitoringClient } = {},
): MonitoringClient {
  const engine = opts.engine ?? engineMonitoring(connectionId);
  let mock: MonitoringClient | null = opts.mock ?? null;
  let demo = opts.forceMock ?? mockRequested();
  const getMock = () => (mock ??= createMockMonitoring());
  if (demo) queueMicrotask(onDemo);

  const wrap = <A extends unknown[], R>(pick: (c: MonitoringClient) => (...a: A) => Promise<R>) =>
    async (...a: A): Promise<R> => {
      if (demo) return pick(getMock())(...a);
      try {
        return await pick(engine)(...a);
      } catch (e) {
        if (!isMissingRoute(e)) throw e;
        demo = true;
        onDemo();
        return pick(getMock())(...a);
      }
    };

  return {
    start: wrap((c) => c.start),
    stop: wrap((c) => c.stop),
    status: wrap((c) => c.status),
    snapshot: wrap((c) => c.snapshot),
    series: wrap((c) => c.series),
    ring: wrap((c) => c.ring),
    tables: wrap((c) => c.tables),
    alerts: wrap((c) => c.alerts),
    thresholds: wrap((c) => c.thresholds),
    saveThresholds: wrap((c) => c.saveThresholds),
  };
}
