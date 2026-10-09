import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { DiagnosticsPanel } from "../DiagnosticsPanel";
import { filterThreads, frameText, type Frame, type ThreadDump, type ThreadEntry } from "../diagApi";
import type { ClusterInfo, ConnectionConfig } from "../../../lib/types";

const conn = { id: "c1", name: "demo" } as ConnectionConfig;
const info: ClusterInfo = {
  datacenters: ["dc1"], schemaAgreement: true, versions: ["4.1.7"], protocolVersion: "V5",
  nodes: [{ address: "10.0.0.1", cqlPort: 9042, datacenter: "dc1", version: "4.1.7" } as ClusterInfo["nodes"][number]],
};

const frame = (method: string, line = 10): Frame => ({ className: "org.example.Worker", method, file: "Worker.java", line, nativeMethod: false, locked: [] });

function thread(id: number, name: string, state: string, extra: Partial<ThreadEntry> = {}): ThreadEntry {
  return {
    id, name, state, daemon: true, priority: 5, lock: null, lockOwnerId: null, lockOwnerName: null, inNative: false,
    suspended: false, blockedCount: 0, waitedCount: 0, stack: [frame("run")], lockedSynchronizers: [], stackKey: "k" + id,
    deadlocked: false, ownerChain: [], ...extra,
  };
}

const dump: ThreadDump = {
  id: "d1", node: "10.0.0.1", takenAtMs: 1_700_000_000_000, jvm: "OpenJDK 11", seriesId: null, threadCount: 3,
  byState: { RUNNABLE: 1, BLOCKED: 2, WAITING: 0, TIMED_WAITING: 0, NEW: 0, TERMINATED: 0 }, blockedCount: 2,
  deadlocks: [{ threadIds: [2, 3], lines: ['"dl-one" #2 waits for <0x1> (a java.lang.Object) held by "dl-two" #3'] }],
  threads: [
    thread(2, "dl-one", "BLOCKED", { deadlocked: true, lock: "<0x1> (a java.lang.Object)", ownerChain: ['"dl-two" #3 BLOCKED', '"dl-one" #2 (cycle)'] }),
    thread(3, "dl-two", "BLOCKED", { deadlocked: true }),
    thread(4, "ReadStage-1", "RUNNABLE"),
  ],
  groups: [{ key: "k2", count: 2, states: { BLOCKED: 2 }, threadIds: [2, 3], threadNames: ["dl-one", "dl-two"], stack: [frame("lockBoth", 42)] }],
};

function json(body: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } }));
}

function stubEngine() {
  const calls: string[] = [];
  vi.stubGlobal("fetch", vi.fn((url: string, init?: RequestInit) => {
    const u = String(url);
    calls.push((init?.method ?? "GET") + " " + u);
    if (u.endsWith("/threads/dumps") && init?.method === "POST") return json(dump);
    if (u.endsWith("/threads/dumps")) return json([]);
    if (u.includes("/threads/top")) {
      return json({
        node: "10.0.0.1", atMs: 1, intervalMs: 3000, firstSample: false, processCpuPct: 12.5, processors: 4, threadsCpuPct: 50,
        threadCount: 80, allocSupported: true, grouped: false, method: "bulk",
        rows: [{ name: "ReadStage-1", id: 4, threads: 1, state: "RUNNABLE", cpuPct: 42.5, userPct: 40, allocBytesPerSec: 2048, cpuTotalMs: 9000 }],
      });
    }
    if (u.includes("/schema")) return json({ keyspaces: [{ name: "shop", system: false, tables: [{ name: "orders", kind: "table" }] }] });
    if (u.includes("/settings")) return json({ logPath: "/var/log/cassandra/system.log", largePartitionMb: 100, tombstonesP99: 1000, tombstoneScanScript: "/usr/local/bin/tombstone-scan.sh" });
    if (u.includes("/partitions/histograms")) {
      const p = (max: number, p99: number) => ({ p50: 1, p75: 1, p95: 1, p98: 1, p99, min: 0, max, count: 10 });
      return json({
        thresholds: { largePartitionBytes: 104857600, tombstonesP99: 1000 }, errors: [], perNode: [],
        merged: [{ keyspace: "shop", table: "orders", node: null, partitionSize: p(300 * 1024 * 1024, 100), cellCount: p(5, 5),
          tombstonesPerRead: p(5000, 2000), sstablesPerRead: p(2, 2), liveCellsPerRead: p(1, 1), flags: ["LARGE_PARTITION", "TOMBSTONES"] }],
      });
    }
    return json({ error: "not_found", message: "no route " + u }, 404);
  }));
  return calls;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("DiagnosticsPanel", () => {
  it("takes a dump and highlights the deadlock and blocked threads", async () => {
    const calls = stubEngine();
    render(<DiagnosticsPanel conn={conn} info={info} dark={false} />);
    fireEvent.click(screen.getByRole("button", { name: "Take thread dump" }));
    const d = await screen.findByTestId("diag-dump");
    expect(calls).toContain("POST /api/clusters/c1/diag/threads/dumps");
    expect(within(d).getByTestId("diag-deadlock").textContent).toContain('held by "dl-two"');
    expect(within(d).getByTestId("diag-blocked").textContent).toContain('"dl-two" #3 BLOCKED → "dl-one" #2 (cycle)');
    expect(d.textContent).toContain("org.example.Worker.lockBoth(Worker.java:42)");

    fireEvent.click(within(d).getByRole("button", { name: /All threads/ }));
    expect(d.textContent).toContain("DEADLOCKED");
    fireEvent.change(within(d).getByLabelText("Filter threads by name or frame"), { target: { value: "ReadStage" } });
    expect(within(d).getByRole("button", { name: /All threads \(1\)/ })).toBeTruthy();
  });

  it("shows live top threads", async () => {
    stubEngine();
    render(<DiagnosticsPanel conn={conn} info={info} dark={false} />);
    fireEvent.click(screen.getByRole("button", { name: "Top threads (live)" }));
    fireEvent.click(screen.getByRole("button", { name: "Start live view" }));
    const table = await screen.findByTestId("diag-top-table");
    expect(table.textContent).toContain("ReadStage-1");
    expect(table.textContent).toContain("42.5");
    expect(screen.getByTestId("diag-top").textContent).toContain("12.5 %");
    fireEvent.click(screen.getByRole("button", { name: "Stop" }));
  });

  it("flags large partitions and tombstones in the histograms", async () => {
    stubEngine();
    render(<DiagnosticsPanel conn={conn} info={info} dark={false} />);
    fireEvent.click(screen.getByRole("tab", { name: "Partitions" }));
    const table = await screen.findByTestId("diag-hist-table");
    expect(table.querySelectorAll(".mon-warn-text")).toHaveLength(2);
    expect(table.textContent).toContain("300 MiB");
    expect(await screen.findByRole("option", { name: "shop.orders" })).toBeTruthy();
  });
});

describe("diag helpers", () => {
  it("formats frames like jstack and filters threads", () => {
    expect(frameText(frame("run", 7))).toBe("org.example.Worker.run(Worker.java:7)");
    expect(frameText({ ...frame("park"), nativeMethod: true })).toBe("org.example.Worker.park(Native Method)");
    expect(filterThreads(dump.threads, "lockboth", "")).toHaveLength(0);
    expect(filterThreads(dump.threads, "worker.run", "BLOCKED")).toHaveLength(2);
    expect(filterThreads(dump.threads, "dl-", "")).toHaveLength(2);
  });
});
