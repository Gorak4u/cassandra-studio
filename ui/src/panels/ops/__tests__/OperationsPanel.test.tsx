import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { OperationsPanel } from "../OperationsPanel";
import { ConfirmProvider, ToastProvider } from "../../../components/feedback";
import type { ClusterInfo, ConnectionConfig } from "../../../lib/types";

const conn = { id: "c1", name: "demo", jmx: { method: "SSH_TUNNEL" } } as unknown as ConnectionConfig;
const info: ClusterInfo = {
  datacenters: ["dc_east", "dc_west"], schemaAgreement: true, versions: ["4.1.5"], protocolVersion: "V5",
  nodes: [
    { address: "10.0.0.1", cqlPort: 9042, datacenter: "dc_east", rack: "r1", version: "4.1.5", state: "UP", tokens: 16, openConnections: 1 },
    { address: "10.0.0.2", cqlPort: 9042, datacenter: "dc_east", rack: "r2", version: "4.1.5", state: "UP", tokens: 16, openConnections: 1 },
    { address: "10.0.0.3", cqlPort: 9042, datacenter: "dc_west", rack: "r1", version: "4.1.5", state: "UP", tokens: 16, openConnections: 1 },
  ],
};

const job = { id: "j1", connectionId: "c1", kind: "flush", title: "Flush shop on 2 nodes", node: "10.0.0.1,10.0.0.2", state: "SUCCEEDED",
  progress: 1, message: "Done on 2 node(s)", log: ["[10.0.0.1] done"], result: null, error: null, createdAtMs: 1, startedAtMs: 1,
  finishedAtMs: 2, cancellable: true };

type Call = { method: string; url: string; body: unknown };
let calls: Call[];

function json(status: number, body: unknown) {
  return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } }));
}

beforeEach(() => {
  calls = [];
  vi.stubGlobal("fetch", (url: string, init: RequestInit) => {
    const body = init.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ method: init.method ?? "GET", url, body });
    if (url.includes("/schema")) {
      return json(200, { keyspaces: [{ name: "shop", system: false, tables: [{ name: "orders", kind: "table" }], views: [], indexes: [], types: [], functions: [], aggregates: [] }] });
    }
    if (url.includes("/ops/views/")) {
      const node = new URL("http://x" + url).searchParams.get("node");
      return json(200, { view: "status", node, command: `nodetool -h ${node} status`, notes: [],
        sections: [{ title: "Datacenter: dc_east", keyValue: false, columns: ["Status/State", "Address", "Load"], rows: [["UN", "10.0.0.1", "1.5 MiB"]] }] });
    }
    if (url.includes("/ops/actions/flush")) {
      return body.confirmed ? json(202, job) : json(428, { error: "confirmation_required", message: "Confirm",
        details: { connectionName: "demo", environment: "DEV", summary: "Flush shop on 2 nodes", destructive: false, requireTypedName: false,
          warnings: [], preview: ["nodetool -h 10.0.0.1 flush -- shop", "nodetool -h 10.0.0.2 flush -- shop"] } });
    }
    if (url.includes("/api/jobs/j1")) return json(200, job);
    if (url.includes("/api/jobs")) return json(200, [job, { ...job, id: "x", kind: "unload" }]);
    return json(404, { error: "not_found", message: url });
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function renderPanel() {
  return render(<ToastProvider><ConfirmProvider><OperationsPanel conn={conn} info={info} dark={false} /></ConfirmProvider></ToastProvider>);
}

describe("OperationsPanel", () => {
  it("shows the status view of the first node as a table", async () => {
    renderPanel();
    const result = await screen.findByTestId("ops-view-result");
    expect(result.textContent).toContain("$ nodetool -h 10.0.0.1 status");
    expect(within(result).getByRole("table").querySelectorAll("tbody tr")).toHaveLength(1);
    expect(screen.getByText("1 of 3 nodes selected")).toBeTruthy();
  });

  it("selects a whole DC and runs a guarded flush with the nodetool preview", async () => {
    renderPanel();
    fireEvent.click(screen.getByRole("checkbox", { name: "dc_east" }));
    expect(screen.getByText("2 of 3 nodes selected")).toBeTruthy();
    fireEvent.click(screen.getByRole("tab", { name: "Maintenance" }));
    const ks = await screen.findByRole("option", { name: "shop" });
    fireEvent.change(ks.closest("select")!, { target: { value: "shop" } });
    fireEvent.click(screen.getByRole("button", { name: /Run on 2 nodes/ }));
    // 428 → confirm dialog with the exact commands
    expect(await screen.findByText(/nodetool -h 10.0.0.2 flush -- shop/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Run" }));
    const progress = await screen.findByTestId("job-progress");
    await waitFor(() => expect(progress.getAttribute("data-state")).toBe("SUCCEEDED"));
    const posts = calls.filter((c) => c.url.includes("/ops/actions/flush"));
    expect(posts).toHaveLength(2);
    expect(posts[1].body).toMatchObject({ nodes: ["10.0.0.1", "10.0.0.2"], keyspace: "shop", tables: [], confirmed: true });
  });

  it("lists only operations jobs", async () => {
    renderPanel();
    fireEvent.click(screen.getByRole("tab", { name: "Jobs" }));
    const list = await screen.findByTestId("ops-jobs");
    await waitFor(() => expect(list.querySelectorAll("tbody tr")).toHaveLength(1));
  });
});
