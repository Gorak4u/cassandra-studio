import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import type { ClusterInfo, ConnectionConfig } from "../../../lib/types";
import type { DriftReport, Snapshot } from "../configTypes";

const snap: Snapshot = {
  collectedAtMs: 1_700_000_000_000,
  nodes: [
    { address: "10.0.0.1", hostId: "h1", datacenter: "dc_east", rack: "r1", version: "4.1.12", sources: { yaml: "system_views.settings" }, notices: [],
      settings: [
        { category: "yaml", name: "compaction_throughput", value: "64MiB/s", rawName: "compaction_throughput", raw: "64MiB/s", source: "vt" },
        { category: "yaml", name: "cluster_name", value: "acme-core", rawName: "cluster_name", raw: "acme-core", source: "vt" },
      ] },
    { address: "10.0.0.2", hostId: "h2", datacenter: "dc_east", rack: "r1", version: "4.1.12", sources: {}, notices: ["OS limits not read over SSH: refused"],
      settings: [
        { category: "yaml", name: "compaction_throughput", value: "17MiB/s", rawName: "compaction_throughput", raw: "17MiB/s", source: "vt" },
        { category: "yaml", name: "cluster_name", value: "acme-core", rawName: "cluster_name", raw: "acme-core", source: "vt" },
        { category: "jvm", name: "-Xmx", value: "8GiB", rawName: "-Xmx", raw: "-Xmx8G", source: "JMX" },
      ] },
  ],
};

const drift: DriftReport = {
  scope: "cluster", onlyDifferences: true, collectedAtMs: snap.collectedAtMs,
  nodes: snap.nodes.map((n) => ({ address: n.address, datacenter: n.datacenter, rack: n.rack, version: n.version })),
  rows: [
    { category: "yaml", name: "cluster_name", perNode: false, values: { "10.0.0.1": "acme-core", "10.0.0.2": "acme-core" }, differsInCluster: false,
      dcsDiffering: [], missingOn: [], expected: { "10.0.0.1": "acme-prod-core", "10.0.0.2": "acme-prod-core" },
      expectedSource: { "10.0.0.1": "core.yaml", "10.0.0.2": "core.yaml" }, mismatches: ["10.0.0.1", "10.0.0.2"] },
    { category: "yaml", name: "compaction_throughput", perNode: false, values: { "10.0.0.1": "64MiB/s", "10.0.0.2": "17MiB/s" }, differsInCluster: true,
      dcsDiffering: ["dc_east"], missingOn: [], expected: null, expectedSource: null, mismatches: [] },
  ],
  summary: { settings: 3, differInCluster: 1, differInDc: 1, hieraCompared: 1, hieraMismatches: 1 },
  hiera: { enabled: true, error: null, layersByNode: {}, mappedKeys: 40 },
};

vi.mock("../configTypes", async (orig) => {
  const mod = await orig<typeof import("../configTypes")>();
  return {
    ...mod,
    configApi: {
      collect: vi.fn(),
      snapshot: vi.fn(async () => snap),
      drift: vi.fn(async () => drift),
      hiera: vi.fn(async () => ({ enabled: true, repoPath: "/repo", facts: { customer: "acme" }, certnames: {} })),
      saveHiera: vi.fn(async (_id: string, s: unknown) => s),
      hieraOptions: vi.fn(async () => ({ repoPath: "/repo", found: true, error: null, values: { customer: ["acme", "amex"] }, variables: [] })),
    },
  };
});

const { ConfigPanel } = await import("../ConfigPanel");
const { configApi, driftCsvRows, settingsGrid, statusText } = await import("../configTypes");

const conn = { id: "c1", name: "acme" } as ConnectionConfig;
const info = { datacenters: ["dc_east"], nodes: [], schemaAgreement: true, versions: ["4.1.12"], protocolVersion: "V5" } as ClusterInfo;

afterEach(cleanup);

describe("ConfigPanel", () => {
  it("shows settings per node with differences highlighted, then the drift report", async () => {
    render(<ConfigPanel conn={conn} info={info} dark={false} />);
    const table = await screen.findByTestId("config-settings-table");
    const rows = table.querySelectorAll("tbody tr");
    expect(rows).toHaveLength(3);
    const ct = within(table).getByText("compaction_throughput").closest("tr")!;
    expect(ct.className).toContain("cfg-diff");
    expect(within(table).getByText("cluster_name").closest("tr")!.className).not.toContain("cfg-diff");
    expect(screen.getByTestId("config-notices").textContent).toContain("1 node could not be read completely");

    fireEvent.change(screen.getByLabelText("Search settings"), { target: { value: "xmx" } });
    expect(table.querySelectorAll("tbody tr")).toHaveLength(1);

    fireEvent.click(screen.getByRole("tab", { name: "Drift report" }));
    const d = await screen.findByTestId("config-drift-table");
    expect(within(d).getByText("compaction_throughput")).toBeTruthy();
    expect(within(d).getAllByText("acme-prod-core").length).toBe(1);
    expect(screen.getByTestId("config-drift-summary").textContent).toContain("1 of 1 not as in Hiera");
    fireEvent.click(screen.getByLabelText("Within each DC"));
    expect(vi.mocked(configApi.drift)).toHaveBeenLastCalledWith("c1", "dc", true, true);
  });

  it("edits and saves Hiera facts", async () => {
    render(<ConfigPanel conn={conn} info={info} dark={false} />);
    fireEvent.click(await screen.findByRole("tab", { name: "Hiera comparison" }));
    const env = await screen.findByLabelText(/^Environment/);
    fireEvent.change(env, { target: { value: "prod" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("status")).toBeTruthy();
    expect(vi.mocked(configApi.saveHiera)).toHaveBeenCalledWith("c1", expect.objectContaining({ facts: { customer: "acme", environment: "prod" } }));
  });

  it("builds the grid and CSV", () => {
    const grid = settingsGrid(snap);
    expect(grid.map((g) => g.name)).toEqual(["cluster_name", "compaction_throughput", "-Xmx"]);
    expect(grid.find((g) => g.name === "-Xmx")!.differs).toBe(false);
    const csv = driftCsvRows(drift);
    expect(csv.columns).toEqual(["category", "setting", "10.0.0.1 (dc_east)", "10.0.0.2 (dc_east)", "expected (Hiera)", "status"]);
    expect(csv.rows[0]).toEqual(["yaml", "cluster_name", "acme-core", "acme-core", "acme-prod-core", "not as in Hiera on 10.0.0.1, 10.0.0.2"]);
    expect(statusText(drift.rows[1])).toBe("differs in cluster; differs in dc_east");
  });
});
