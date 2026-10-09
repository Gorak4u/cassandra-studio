import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { BackupPanel } from "../BackupPanel";
import { ConfirmProvider, ToastProvider } from "../../../components/feedback";
import { filterBackups, formatBytes, type BackupEntry } from "../backupApi";
import type { ClusterInfo, ConnectionConfig } from "../../../lib/types";

const conn = { id: "c1", name: "demo" } as ConnectionConfig;
const info: ClusterInfo = {
  datacenters: ["dc_east", "dc_west"], schemaAgreement: true, versions: ["4.1.5"], protocolVersion: "V5",
  nodes: [
    { address: "10.0.0.1", cqlPort: 9042, datacenter: "dc_east", state: "UP" },
    { address: "10.0.0.2", cqlPort: 9042, datacenter: "dc_west", state: "UP" },
  ] as ClusterInfo["nodes"],
};

const entry = (over: Partial<BackupEntry>): BackupEntry => ({
  id: "2026-10-09-02-00-05", provider: "estate", node: "10.0.0.1", host: "east1", datacenter: "dc_east", type: "full",
  timeMs: Date.UTC(2026, 9, 9, 2, 4), sizeBytes: 123456, schemaVersion: "0f1e2d3c-aaaa", status: "COMPLETE",
  statusDetail: "manifest present", location: "s3://bucket/east1/2026-10-09-02-00-05/", retention: "bucket lifecycle: expires after 30 days",
  expiresAtMs: null, objectLock: "off", lockedUntilMs: null, tables: 26, objects: 29, notes: null, ...over,
});

const catalogue = {
  provider: "ESTATE", generatedAtMs: Date.now(), notes: ["Schema version is known only for backups started from Studio."],
  nodes: [{ node: "10.0.0.1", datacenter: "dc_east", ok: true, error: null, backups: 2, method: "x" },
    { node: "10.0.0.2", datacenter: "dc_west", ok: false, error: "cannot read /etc/backup/config.json", backups: 0, method: null }],
  backups: [entry({}), entry({ id: "2026-10-09-06-00", type: "unknown", status: "INCOMPLETE", sizeBytes: null, schemaVersion: null, tables: null })],
};

function json(status: number, body: unknown) {
  return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } }));
}

function wrap() {
  return render(<ToastProvider><ConfirmProvider><BackupPanel conn={conn} info={info} dark={false} /></ConfirmProvider></ToastProvider>);
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("BackupPanel", () => {
  it("lists the catalogue with unknowns, node errors and filters", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      if (url.endsWith("/backup/settings")) return json(200, { provider: "ESTATE", scriptDir: "/usr/local/bin", configFile: "/etc/backup/config.json", privilege: "SUDO", medusaCommand: "medusa", medusaConfig: null, nodeTimeoutMinutes: 360 });
      if (url.endsWith("/backup/catalogue")) return json(200, catalogue);
      return json(404, { error: "not_found", message: "no" });
    }));
    wrap();
    const table = await screen.findByTestId("backup-catalogue-table");
    expect(table.querySelectorAll("tbody tr")).toHaveLength(2);
    expect(within(table).getAllByText("unknown").length).toBeGreaterThanOrEqual(3);
    expect(screen.getByText(/10.0.0.2: could not list backups: cannot read/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "INCOMPLETE" } });
    expect(screen.getByTestId("backup-catalogue-table").querySelectorAll("tbody tr")).toHaveLength(1);
  });

  it("asks for confirmation with the exact command, then follows the job per node", async () => {
    const calls: { url: string; body: unknown }[] = [];
    let ran = false;
    vi.stubGlobal("fetch", vi.fn((url: string, init?: RequestInit) => {
      const body = init?.body ? JSON.parse(String(init.body)) : undefined;
      calls.push({ url, body });
      if (url.endsWith("/backup/settings")) return json(200, { provider: "ESTATE", scriptDir: "/usr/local/bin", configFile: "/etc/backup/config.json", privilege: "SUDO", medusaCommand: "medusa", medusaConfig: null, nodeTimeoutMinutes: 360 });
      if (url.endsWith("/backup/catalogue")) return json(200, { ...catalogue, backups: [], nodes: [] });
      if (url.endsWith("/backup/run")) {
        if (!body.confirmed) {
          return json(428, { error: "confirmation_required", message: "Confirm", details: {
            connectionName: "demo", environment: "DEV", summary: "Full backup (estate scripts) on the cluster (2 nodes)",
            preview: ["10.0.0.1: sudo -n '/usr/local/bin/full-backup-to-s3.sh'", "10.0.0.2: sudo -n '/usr/local/bin/full-backup-to-s3.sh'"],
            warnings: [], destructive: false, requireTypedName: false } });
        }
        ran = true;
        return json(202, { id: "j1", state: "RUNNING", title: "Full backup", log: [], progress: 0.2, message: "0 of 2 nodes done", cancellable: true, kind: "backup", connectionId: "c1", node: null, result: null, error: null, createdAtMs: 0, startedAtMs: 0, finishedAtMs: null });
      }
      if (url.endsWith("/api/jobs/j1")) return json(200, { id: "j1", state: "SUCCEEDED", title: "Full backup", log: ["[10.0.0.1] Summary: all 26 table(s)"], progress: 1, message: "2 of 2 nodes done", cancellable: true, kind: "backup", connectionId: "c1", node: null, result: null, error: null, createdAtMs: 0, startedAtMs: 0, finishedAtMs: 1 });
      if (url.endsWith("/backup/runs/j1")) return json(200, { jobId: "j1", provider: "ESTATE", mode: "full", concurrency: 1, nodes: [
        { node: "10.0.0.1", datacenter: "dc_east", state: "SUCCEEDED", progress: 1, message: "finished", command: "sudo -n '/usr/local/bin/full-backup-to-s3.sh'", backupId: "2026-10-09-13-00-00", exitCode: 0, summary: "Summary: all 26 table(s) selected", startedAtMs: 0, finishedAtMs: 1 },
        { node: "10.0.0.2", datacenter: "dc_west", state: "FAILED", progress: 0.3, message: "exited with 1: s3 credentials not found or invalid.", command: "x", backupId: null, exitCode: 1, summary: null, startedAtMs: 0, finishedAtMs: 1 }] });
      return json(404, { error: "not_found", message: url });
    }));
    wrap();
    fireEvent.click(await screen.findByRole("button", { name: "Run backup now…" }));
    expect(await screen.findByText(/10\.0\.0\.1: sudo -n '\/usr\/local\/bin\/full-backup-to-s3\.sh'/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Run" }));
    const results = await screen.findByTestId("backup-node-results");
    expect(ran).toBe(true);
    expect(within(results).getByText("2026-10-09-13-00-00")).toBeTruthy();
    expect(within(results).getByText(/s3 credentials not found/)).toBeTruthy();
    const runCall = calls.find((c) => c.url.endsWith("/backup/run") && (c.body as { confirmed?: boolean }).confirmed);
    expect(runCall?.body).toMatchObject({ scope: "CLUSTER", mode: "full", concurrency: 1, confirmed: true });
    await waitFor(() => expect(screen.getByTestId("job-progress").getAttribute("data-state")).toBe("SUCCEEDED"));
  });

  it("detects providers when none is chosen", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      if (url.endsWith("/backup/settings")) return json(200, { provider: null, scriptDir: "/usr/local/bin", configFile: "/etc/backup/config.json", privilege: "SUDO", medusaCommand: "medusa", medusaConfig: null, nodeTimeoutMinutes: 360 });
      if (url.endsWith("/backup/detect")) return json(200, { recommended: "ESTATE", recommendedPrivilege: "NONE", recommendedScriptDir: "/usr/local/bin", jmxSnapshots: true, jmxError: null,
        notes: ["sudo -n does not work for this SSH user on every node"],
        nodes: [{ node: "10.0.0.1", datacenter: "dc_east", state: "UP", reachable: true, error: null, host: "east1", scripts: ["full-backup-to-s3.sh", "backup-storage-lib.sh"], scriptsOnPath: null, configPresent: true, configReadable: false, medusa: null, medusaConfig: null, sudo: false }] });
      return json(404, { error: "not_found", message: url });
    }));
    wrap();
    expect(screen.queryByTestId("backup-run")).toBeNull();
    fireEvent.click(await screen.findByRole("button", { name: "Detect on nodes" }));
    const det = await screen.findByTestId("backup-detection");
    expect(within(det).getByText("present (root only)")).toBeTruthy();
    expect((screen.getByRole("radio", { name: /Estate backup scripts/ }) as HTMLInputElement).checked).toBe(true);
    expect((screen.getByLabelText("Run as") as HTMLSelectElement).value).toBe("NONE");
  });

  it("formats and filters", () => {
    expect(formatBytes(null)).toBe("unknown");
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(1536)).toBe("1.5 KiB");
    const list = [entry({}), entry({ node: null, type: "unknown", status: "INCOMPLETE" })];
    expect(filterBackups(list, { node: "cluster", type: "", status: "" })).toHaveLength(1);
    expect(filterBackups(list, { node: "", type: "full", status: "COMPLETE" })).toHaveLength(1);
  });
});
