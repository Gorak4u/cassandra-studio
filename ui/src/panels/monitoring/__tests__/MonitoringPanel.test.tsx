import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { MonitoringPanel } from "../MonitoringPanel";
import type { ClusterInfo, ConnectionConfig } from "../../../lib/types";

const conn = { id: "c1", name: "demo" } as ConnectionConfig;
const info: ClusterInfo = { datacenters: ["dc-east", "dc-west"], nodes: [], schemaAgreement: true, versions: ["4.1.7"], protocolVersion: "V5" };

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
});

describe("MonitoringPanel (demo data)", () => {
  it("shows health, the access banner and the nodes table", async () => {
    vi.stubEnv("VITE_MONITORING_MOCK", "1");
    render(<MonitoringPanel conn={conn} info={info} dark={false} />);
    const badge = await screen.findByTestId("monitoring-health-badge", {}, { timeout: 3000 });
    expect(badge.textContent).toMatch(/YELLOW|RED/);
    expect(screen.getByTestId("monitoring-demo")).toBeTruthy();
    expect((await screen.findByTestId("monitoring-access-banner")).textContent).toContain("JMX not reachable on 1 of 6 nodes via ssh tunnel");
    expect(within(screen.getByTestId("monitoring-alerts")).getAllByText("load.imbalance").length).toBe(1);

    fireEvent.click(screen.getByRole("tab", { name: "Nodes" }));
    const table = screen.getByTestId("monitoring-nodes-table");
    expect(table.querySelectorAll("tbody tr")).toHaveLength(6);
    expect(within(table).getAllByText("n/a").length).toBeGreaterThan(5); // the unreachable node

    fireEvent.click(within(table).getByRole("button", { name: "Open details for 10.0.1.12" }));
    expect(screen.getByTestId("monitoring-node-drawer").textContent).toContain("ReadStage");
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByTestId("monitoring-node-drawer")).toBeNull();
  });
});
