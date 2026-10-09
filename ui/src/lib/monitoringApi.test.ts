import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "./api";
import { engineMonitoring, monitoringClient, type MonitoringClient } from "./monitoringApi";
import { createMockMonitoring, mockSnapshot } from "./monitoringMock";

type Call = { method: string; url: string; body?: unknown };

function fakeFetch(responses: { status: number; body?: unknown; text?: string }[]) {
  const calls: Call[] = [];
  const fn = vi.fn(async (url: string, init: RequestInit) => {
    calls.push({ method: init.method!, url, body: init.body ? JSON.parse(String(init.body)) : undefined });
    const r = responses.shift() ?? { status: 500, body: { error: "unexpected" } };
    const text = r.text ?? (r.body === undefined ? "" : JSON.stringify(r.body));
    return { ok: r.status >= 200 && r.status < 300, status: r.status, statusText: "x", text: async () => text } as Response;
  });
  vi.stubGlobal("fetch", fn);
  return calls;
}

const snap = mockSnapshot(Date.UTC(2026, 9, 9, 12));
const status = { method: "DIRECT", polling: true, pollIntervalSec: 10, nodes: [] };

afterEach(() => {
  vi.unstubAllGlobals();
  sessionStorage.clear();
});

describe("engine monitoring client", () => {
  it("builds routes, query strings and sends the token", async () => {
    sessionStorage.setItem("studio.token", "t0k");
    const calls = fakeFetch([{ status: 200, body: { metric: "heap.used", unit: "bytes", pointsByNode: {} } }, { status: 200, body: [] }, { status: 204 }]);
    const c = engineMonitoring("conn/1");
    await c.series("heap.used", { node: "10.0.0.1", fromMs: 1, toMs: 2 });
    await c.tables(null);
    await c.stop();
    expect(calls.map((x) => `${x.method} ${x.url}`)).toEqual([
      "GET /api/clusters/conn%2F1/monitoring/series?metric=heap.used&node=10.0.0.1&fromMs=1&toMs=2",
      "GET /api/clusters/conn%2F1/monitoring/tables",
      "POST /api/clusters/conn%2F1/monitoring/stop",
    ]);
    const fetchMock = vi.mocked(fetch);
    expect((fetchMock.mock.calls[0][1]!.headers as Record<string, string>).Authorization).toBe("Bearer t0k");
  });

  it("starts monitoring on 409 not_started and retries the snapshot", async () => {
    const calls = fakeFetch([
      { status: 409, body: { error: "not_started", message: "Monitoring is not started" } },
      { status: 200, body: status },
      { status: 200, body: snap },
    ]);
    const s = await engineMonitoring("c1").snapshot(30);
    expect(s.atEpochMs).toBe(snap.atEpochMs);
    expect(calls.map((x) => `${x.method} ${x.url.split("/monitoring")[1]}`)).toEqual(["GET /snapshot", "POST /start", "GET /snapshot"]);
    expect(calls[1].body).toEqual({ intervalSec: 30 });
  });

  it("does not start on other errors", async () => {
    const calls = fakeFetch([{ status: 409, body: { error: "conflict", message: "busy" } }]);
    await expect(engineMonitoring("c1").snapshot()).rejects.toMatchObject({ status: 409, code: "conflict" });
    expect(calls).toHaveLength(1);
  });
});

describe("monitoring client with demo fallback", () => {
  const mock = (): MonitoringClient => createMockMonitoring({ latencyMs: 0 });

  it("switches to demo data when the engine has no monitoring routes (404 without error code)", async () => {
    fakeFetch([{ status: 404, text: "Not found" }]);
    const onDemo = vi.fn();
    const c = monitoringClient("c1", onDemo, { forceMock: false, mock: mock() });
    const st = await c.start(10);
    expect(st.method).toBe("SSH_TUNNEL");
    expect(onDemo).toHaveBeenCalledTimes(1);
    const s = await c.snapshot(); // stays on the mock, no further fetch
    expect(s.nodes).toHaveLength(6);
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
  });

  it("surfaces engine 404s that carry an error code", async () => {
    fakeFetch([{ status: 404, body: { error: "not_found", message: "Connection c1 not found" } }]);
    const onDemo = vi.fn();
    const c = monitoringClient("c1", onDemo, { forceMock: false, mock: mock() });
    const e = await c.status().catch((x) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect(e.message).toBe("Connection c1 not found");
    expect(onDemo).not.toHaveBeenCalled();
  });

  it("uses the mock from the start when forced", async () => {
    const calls = fakeFetch([]);
    const onDemo = vi.fn();
    const c = monitoringClient("c1", onDemo, { forceMock: true, mock: mock() });
    await c.thresholds();
    await Promise.resolve();
    expect(calls).toHaveLength(0);
    expect(onDemo).toHaveBeenCalled();
  });
});
