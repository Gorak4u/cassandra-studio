import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { ClusterInfo, ConnectionConfig } from "../../../lib/types";
import { sampleReport } from "./sampleReport";

vi.mock("../gclogApi", () => ({
  gclogApi: {
    list: vi.fn(),
    discover: vi.fn(),
    fetch: vi.fn(),
    report: vi.fn(),
    remove: vi.fn(),
    upload: vi.fn(),
  },
}));
vi.mock("../../../lib/jobsApi", () => ({
  jobsApi: { get: vi.fn(), cancel: vi.fn(), list: vi.fn() },
}));

import { gclogApi } from "../gclogApi";
import { jobsApi } from "../../../lib/jobsApi";
import { defaultSelection, filterEvents, GcLogPanel } from "../GcLogPanel";

const conn = { id: "c1", name: "demo" } as ConnectionConfig;
const info: ClusterInfo = {
  datacenters: ["dc1"], schemaAgreement: true, versions: ["3.11.19"], protocolVersion: "V4",
  nodes: [{ address: "10.0.0.1", cqlPort: 9042, datacenter: "dc1", state: "UP", tokens: 16, openConnections: 1 }],
};
const api = vi.mocked(gclogApi);

beforeEach(() => {
  api.list.mockResolvedValue([]);
  api.report.mockResolvedValue(sampleReport());
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("GcLogPanel", () => {
  it("finds files on a node, loads them as a job and shows the report", async () => {
    api.discover.mockResolvedValue({
      node: "10.0.0.1", javaVersion: "1.8", gcOptions: ["-Xloggc:/opt/cassandra/logs/gc.log"], configuredPath: "/opt/cassandra/logs/gc.log",
      searched: [], compressed: true,
      files: [
        { path: "/opt/cassandra/logs/gc.log.0.current", sizeBytes: 54401, modifiedMs: 2000, current: true },
        { path: "/opt/cassandra/logs/gc.log.1", sizeBytes: 10485760, modifiedMs: 1000, current: false },
      ],
    });
    api.fetch.mockResolvedValue({ id: "j1" } as never);
    vi.mocked(jobsApi.get).mockResolvedValue({
      id: "j1", connectionId: "c1", kind: "gclog-fetch", title: "Load GC logs from 10.0.0.1", node: "10.0.0.1", state: "SUCCEEDED",
      progress: 1, message: null, log: [], result: { analysisId: "a1" }, error: null, createdAtMs: 0, startedAtMs: 0, finishedAtMs: 1, cancellable: true,
    });

    render(<GcLogPanel conn={conn} info={info} dark={false} />);
    fireEvent.click(screen.getByTestId("gclog-find"));
    const disc = await screen.findByTestId("gclog-discovery");
    expect(disc.textContent).toContain("/opt/cassandra/logs/gc.log.0.current");
    expect(within(disc).getAllByRole("checkbox").filter((c) => (c as HTMLInputElement).checked)).toHaveLength(2);

    fireEvent.click(screen.getByTestId("gclog-load"));
    await waitFor(() => expect(api.fetch).toHaveBeenCalledWith("c1", "10.0.0.1",
      ["/opt/cassandra/logs/gc.log.0.current", "/opt/cassandra/logs/gc.log.1"], 200, "1.8"));
    const report = await screen.findByTestId("gclog-report");
    expect(api.report).toHaveBeenCalledWith("c1", "a1", undefined);
    expect(report.textContent).toContain("CMS");
    expect(screen.getByTestId("gclog-card-pauses").textContent).toContain("4");
    expect(screen.getByTestId("gclog-finding-concurrent-mode-failure").textContent).toContain("-XX:CMSInitiatingOccupancyFraction");

    fireEvent.click(screen.getByRole("tab", { name: "Events" }));
    const events = screen.getByTestId("gclog-events");
    expect(events.querySelectorAll("tbody tr")).toHaveLength(4); // pauses only
    fireEvent.change(screen.getByTestId("gclog-event-filter"), { target: { value: "full" } });
    expect(screen.getByTestId("gclog-events").querySelectorAll("tbody tr")).toHaveLength(1);
  });

  it("uploads a file and shows errors from the engine", async () => {
    api.upload.mockRejectedValueOnce(new Error("No GC events found in notes.txt"));
    render(<GcLogPanel conn={conn} info={info} dark={false} />);
    const input = screen.getByTestId("gclog-upload") as HTMLInputElement;
    fireEvent.change(input, { target: { files: [new File(["x"], "notes.txt")] } });
    expect((await screen.findByRole("alert")).textContent).toContain("No GC events");

    api.upload.mockResolvedValueOnce({ id: "a1" } as never);
    fireEvent.change(input, { target: { files: [new File(["x"], "gc.log")] } });
    expect(await screen.findByTestId("gclog-findings")).toBeTruthy();
  });

  it("selects the newest files under the cap and filters events", () => {
    const d = { node: "n", gcOptions: [], searched: [], compressed: true, files: [
      { path: "a", sizeBytes: 150 * 1024 * 1024, modifiedMs: 3, current: true },
      { path: "b", sizeBytes: 100 * 1024 * 1024, modifiedMs: 2, current: false },
      { path: "c", sizeBytes: 1, modifiedMs: 1, current: false },
    ] };
    expect(defaultSelection(d, 200)).toEqual(["a"]);
    expect(defaultSelection(d, 10)).toEqual(["a"]);
    expect(defaultSelection(d, 300)).toEqual(["a", "b", "c"]);
    const r = sampleReport();
    expect(filterEvents(r.events, "long")).toHaveLength(1);
    expect(filterEvents(r.events, "concurrent")).toHaveLength(1);
    expect(filterEvents(r.events, "flagged")).toHaveLength(1);
  });
});
