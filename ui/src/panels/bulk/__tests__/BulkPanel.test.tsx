import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { BulkPanel } from "../BulkPanel";
import { ConfirmProvider, ToastProvider } from "../../../components/feedback";
import type { ClusterInfo, ConnectionConfig } from "../../../lib/types";

const conn = { id: "c1", name: "demo", environment: "DEV", readOnly: false } as ConnectionConfig;
const info: ClusterInfo = { datacenters: ["dc1"], nodes: [], schemaAgreement: true, versions: ["4.1.7"], protocolVersion: "V5" };

type Call = { method: string; url: string; body: Record<string, unknown> | undefined };
let calls: Call[] = [];

function json(status: number, body: unknown) {
  return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } }));
}

const job = { id: "j1", connectionId: "c1", kind: "bulk-load", title: "Load data.csv into shop.orders", node: null, state: "RUNNING",
  progress: 0.5, message: "10 read", log: [], result: null, error: null, createdAtMs: 1, startedAtMs: 1, finishedAtMs: null, cancellable: true };

beforeEach(() => {
  calls = [];
  vi.stubGlobal("fetch", (url: string, init: RequestInit) => {
    const body = init.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ method: init.method ?? "GET", url, body });
    if (url.endsWith("/schema")) return json(200, { keyspaces: [{ name: "shop", system: false, tables: [{ name: "orders", kind: "table" }], views: [], indexes: [], types: [], functions: [], aggregates: [] }] });
    if (url.endsWith("/bulk/defaults")) return json(200, { downloadsDir: "/home/me/Downloads", separator: "/" });
    if (url.endsWith("/bulk/jobs")) return json(200, []);
    if (url.includes("/schema/keyspaces/shop/tables/orders")) {
      return json(200, { keyspace: "shop", name: "orders", columns: [{ name: "id", type: "int" }, { name: "note", type: "text" }], partitionKey: ["id"], clusteringColumns: [] });
    }
    if (url.endsWith("/bulk/unload")) return json(409, { error: "file_exists", message: "/home/me/Downloads/shop.orders.csv already exists. Choose another name or allow overwriting." });
    if (url.endsWith("/bulk/load/preview")) {
      return json(200, { path: "/d/data.csv", format: "csv", gzip: false, sizeBytes: 2048, estimatedRows: 2, fileColumns: ["ID", "Note"],
        sampleRows: [["1", "hello"], ["2", null]],
        tableColumns: [{ name: "id", type: "int", kind: "partition_key" }, { name: "note", type: "text", kind: "regular" }],
        mapping: [{ column: "id", source: "ID" }] });
    }
    if (url.endsWith("/bulk/load")) {
      if (!body?.confirmed) {
        return json(428, { error: "confirmation_required", message: "Confirm", details: { connectionName: "demo", environment: "DEV",
          summary: "Load data.csv into shop.orders", preview: ["INSERT INTO shop.orders (id) VALUES (?)"], warnings: [], destructive: false, requireTypedName: false } });
      }
      return json(202, job);
    }
    if (url.includes("/api/jobs/")) return json(200, job);
    return json(404, { error: "not_found", message: url });
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const renderPanel = () => render(<ToastProvider><ConfirmProvider><BulkPanel conn={conn} info={info} dark={false} /></ConfirmProvider></ToastProvider>);

describe("BulkPanel", () => {
  it("unloads a table to the default Downloads path and shows the overwrite refusal", async () => {
    renderPanel();
    const ks = await screen.findByTestId("bulk-unload-keyspace");
    await waitFor(() => expect(within(ks).getAllByRole("option")).toHaveLength(2));
    fireEvent.change(ks, { target: { value: "shop" } });
    fireEvent.change(screen.getByTestId("bulk-unload-table"), { target: { value: "orders" } });
    await waitFor(() => expect((screen.getByTestId("bulk-unload-path") as HTMLInputElement).value).toBe("/home/me/Downloads/shop.orders.csv"));
    await screen.findByText("Columns (2 of 2)");
    fireEvent.change(screen.getByTestId("bulk-unload-format"), { target: { value: "json" } });
    await waitFor(() => expect((screen.getByTestId("bulk-unload-path") as HTMLInputElement).value).toBe("/home/me/Downloads/shop.orders.jsonl"));
    fireEvent.change(screen.getByTestId("bulk-unload-format"), { target: { value: "csv" } });
    fireEvent.click(screen.getByTestId("bulk-unload-start"));
    expect((await screen.findByTestId("bulk-unload-error")).textContent).toContain("already exists");
    const sent = calls.find((c) => c.url.endsWith("/bulk/unload"))!;
    expect(sent.body).toMatchObject({ mode: "table", keyspace: "shop", table: "orders", columns: [], format: "csv", compression: "none", overwrite: false });
  });

  it("previews a file, maps columns and loads after confirmation", async () => {
    renderPanel();
    fireEvent.click(await screen.findByRole("tab", { name: "Load (import)" }));
    await waitFor(() => expect(within(screen.getByTestId("bulk-load-keyspace")).getAllByRole("option")).toHaveLength(2));
    fireEvent.change(screen.getByTestId("bulk-load-path"), { target: { value: "/d/data.csv" } });
    fireEvent.change(screen.getByTestId("bulk-load-keyspace"), { target: { value: "shop" } });
    fireEvent.change(screen.getByTestId("bulk-load-table"), { target: { value: "orders" } });
    fireEvent.click(screen.getByTestId("bulk-load-preview"));
    const sample = await screen.findByTestId("bulk-load-sample");
    expect(sample.querySelectorAll("tbody tr")).toHaveLength(2);
    const grid = screen.getByTestId("bulk-load-mapping");
    expect((within(grid).getByLabelText("File column for id") as HTMLSelectElement).value).toBe("ID");
    fireEvent.change(within(grid).getByLabelText("File column for note"), { target: { value: "Note" } });

    fireEvent.click(screen.getByTestId("bulk-load-start"));
    const dialog = await screen.findByRole("dialog");
    expect(dialog.textContent).toContain("INSERT INTO shop.orders");
    fireEvent.click(within(dialog).getByRole("button", { name: "Run" }));
    await screen.findByTestId("job-progress");
    const loads = calls.filter((c) => c.url.endsWith("/bulk/load"));
    expect(loads).toHaveLength(2);
    expect(loads[1].body).toMatchObject({ keyspace: "shop", table: "orders", path: "/d/data.csv", confirmed: true, dryRun: false,
      mapping: [{ column: "id", source: "ID" }, { column: "note", source: "Note" }] });
  });
});
