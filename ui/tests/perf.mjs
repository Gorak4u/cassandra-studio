// UI scale test (NFR-SCALE / NFR-PERF): the built UI against a mocked engine API with 100 saved
// connections in folders and a 500-node cluster (5 DCs, 24 h of history). Measures open time and
// interaction latency in Chromium and fails when a budget is missed (open < 2 s, interaction < 200 ms).
// Usage: npx vite build && node tests/perf.mjs [--json out.json]
import { chromium } from "@playwright/test";
import http from "node:http";
import fs from "node:fs";
import path from "node:path";

const DIST = new URL("../dist/", import.meta.url).pathname;
const NODES = Number(process.env.PERF_NODES ?? 500);
const DCS = 5;
const CONNECTIONS = 100;
const OPEN_BUDGET_MS = 2000;
const INTERACTION_BUDGET_MS = 200;

// ---- mock data ---------------------------------------------------------------------------------

const folders = [];
for (let c = 0; c < 10; c++) {
  folders.push({ id: `f${c}`, parentId: null, name: `customer-${c}`, position: c });
  for (const env of ["prod", "nonprod"]) folders.push({ id: `f${c}-${env}`, parentId: `f${c}`, name: env, position: 0 });
}
const connections = [];
for (let i = 0; i < CONNECTIONS; i++) {
  const c = i % 10;
  const prod = i % 2 === 0;
  connections.push({
    id: `c${i}`, folderId: `f${c}-${prod ? "prod" : "nonprod"}`, name: `cluster-${String(i).padStart(3, "0")}`,
    environment: prod ? "STAGING" : "DEV", color: null, readOnly: false, contactPoints: [`10.${c}.0.${i}:9042`],
    localDatacenter: "dc0", username: null, tls: { enabled: false, hostnameVerification: true }, protocolVersion: "auto",
    defaultConsistency: "LOCAL_ONE", requestTimeoutMs: 12000, pageSize: 500,
    jmx: { method: "DIRECT", port: 7199, ssl: false }, ssh: { port: 22, auth: "AGENT", strictHostKeyChecking: true },
    tags: [prod ? "prod" : "dev", `customer-${c}`], notes: null, secretsSet: {},
  });
}
const nodeAddr = (i) => `10.${10 + (i % DCS)}.${Math.floor(i / 250)}.${(i % 250) + 1}`;
const nodes = Array.from({ length: NODES }, (_, i) => ({
  hostId: `host-${i}`, address: nodeAddr(i), cqlPort: 9042, datacenter: `dc${i % DCS}`, rack: `rack${i % 3}`, version: "4.1.5",
  state: i % 97 === 0 ? "DOWN" : "UP", tokens: 16, schemaVersion: "s1", openConnections: 1,
}));
const clusterInfo = { name: "big", partitioner: "org.apache.cassandra.dht.Murmur3Partitioner", datacenters: [...Array(DCS).keys()].map((d) => `dc${d}`),
  nodes, schemaAgreement: true, versions: ["4.1.5"], protocolVersion: "V5" };
const lat = (p99) => ({ p50Micros: p99 / 3, p95Micros: p99 / 1.5, p99Micros: p99, maxMicros: p99 * 3, ratePerSec: 1200, count: 1e6 });
const now = Date.now();
const snapshot = () => ({
  atEpochMs: Date.now(), pollIntervalSec: 10, schemaAgreement: true,
  health: { level: "YELLOW", reasons: ["Some nodes down"] },
  alerts: nodes.filter((n) => n.state === "DOWN").map((n) => ({ id: `node.down:${n.address}`, level: "RED", rule: "node.down", node: n.address,
    message: `${n.address} is down`, sinceEpochMs: now })),
  nodes: nodes.map((n, i) => ({
    hostId: n.hostId, address: n.address, datacenter: n.datacenter, rack: n.rack, state: n.state === "UP" ? "UN" : "DN", route: "direct",
    error: n.state === "UP" ? null : "Connection refused", cassandraVersion: "4.1.5", javaVersion: "11.0.22", javaVendor: "Adoptium",
    uptimeSec: 86400 + i, loadBytes: 100e9 + i * 1e8, tokens: 16, heapUsedBytes: 4e9 + (i % 7) * 1e8, heapMaxBytes: 8e9, offHeapBytes: 1e8,
    gc: [{ name: "G1 Young Generation", count: 100, timeMs: 1000 }], gcTimePct: 1.2, cpuProcessPct: 20 + (i % 30), cpuSystemPct: 40,
    openFds: 900, maxFds: 100000, pendingCompactions: i % 120, activeCompactions: 1, completedCompactions: 100, hintsInProgress: 0, totalHints: 0,
    threadPools: [{ name: "MutationStage", active: 1, pending: 0, blocked: 0, completed: 1000, allTimeBlocked: 0 }], dropped: { MUTATION: 0 },
    clientRequests: { read: lat(900), write: lat(300), rangeSlice: null, casRead: null, casWrite: null, readTimeouts: 0, writeTimeouts: 0,
      readUnavailables: 0, writeUnavailables: 0, readFailures: 0, writeFailures: 0 },
    liveSSTables: 30, dataDirs: [{ path: "/var/lib/cassandra/data", totalBytes: 2e12, freeBytes: 1e12 }],
  })),
});
const series = (metric, fromMs, toMs, maxPoints) => {
  const pointsByNode = {};
  const rawFrom = toMs - 3600_000;
  for (let i = 0; i < NODES; i++) {
    const n = nodes[i];
    if (n.state !== "UP") continue;
    const pts = [];
    for (let t = fromMs; t <= toMs; t += t < rawFrom ? 60_000 : 10_000) pts.push([t, 50 + 40 * Math.sin(t / 3.6e6 + i) + (i % 5)]);
    let out = pts;
    if (maxPoints && pts.length > maxPoints) { // the engine downsamples (LTTB); a stride is close enough for size and render cost
      const step = pts.length / maxPoints;
      out = Array.from({ length: maxPoints }, (_, k) => pts[Math.min(pts.length - 1, Math.floor(k * step))]);
    }
    pointsByNode[n.address] = out;
  }
  return { metric, unit: metric.startsWith("heap") ? "bytes" : "count", pointsByNode };
};
const ring = () => ({ partitioner: clusterInfo.partitioner, keyspace: "shop", datacenters: clusterInfo.datacenters.map((dc) => ({
  name: dc, nodes: nodes.filter((n) => n.datacenter === dc).map((n, k) => ({ hostId: n.hostId, address: n.address, rack: n.rack,
    state: n.state === "UP" ? "UN" : "DN", loadBytes: 100e9, tokens: [String(-9e18 + k * 1e16)], ownershipPct: 100 / NODES, effectiveOwnershipPct: 3 / (NODES / DCS) * 100 })),
})) });
const schema = { clusterName: "big", keyspaces: [{ name: "shop", system: false, tables: [{ name: "orders" }], views: [], indexes: [], types: [], functions: [], aggregates: [] }] };

let seriesBytes = 0;
function api(method, url) {
  const u = new URL(url);
  const p = u.pathname;
  const q = Object.fromEntries(u.searchParams);
  if (p === "/api/info") return { version: "1.0.0-perf", secretStore: "memory", actor: "perf", dbVersion: 3 };
  if (p === "/api/folders") return folders;
  if (p === "/api/connections") return connections;
  if (p === "/api/ui-state") return method === "GET" ? {} : null;
  if (/\/api\/connections\/[^/]+\/connect$/.test(p) || /\/api\/clusters\/[^/]+\/info$/.test(p)) return clusterInfo;
  if (/\/schema$/.test(p)) return schema;
  const mon = /\/api\/clusters\/[^/]+\/monitoring\/(\w+)$/.exec(p);
  if (mon) {
    const status = { method: "DIRECT", polling: true, pollIntervalSec: 10, nodes: nodes.map((n) => ({ address: n.address, ok: n.state === "UP",
      route: "direct", error: n.state === "UP" ? null : "Connection refused", lastPollEpochMs: Date.now() })) };
    switch (mon[1]) {
      case "start": case "status": return status;
      case "snapshot": return snapshot();
      case "series": {
        const s = series(q.metric, Number(q.fromMs), Number(q.toMs), q.maxPoints ? Number(q.maxPoints) : 0);
        return s;
      }
      case "ring": return ring();
      case "alerts": return [];
      case "thresholds": return {};
      case "tables": return [];
      default: return undefined;
    }
  }
  if (/\/api\/jobs/.test(p)) return [];
  return undefined;
}

// ---- static server for dist/ -------------------------------------------------------------------

const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml", ".json": "application/json", ".ttf": "font/ttf", ".woff2": "font/woff2" };
const server = http.createServer((req, res) => {
  const rel = decodeURIComponent(new URL(req.url, "http://x").pathname).replace(/^\/+/, "") || "index.html";
  const file = path.join(DIST, rel);
  if (!file.startsWith(DIST) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404).end(); return; }
  res.writeHead(200, { "Content-Type": types[path.extname(file)] ?? "application/octet-stream" });
  fs.createReadStream(file).pipe(res);
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const origin = `http://127.0.0.1:${server.address().port}`;

// ---- run ---------------------------------------------------------------------------------------

const results = [];
const failures = [];
function record(name, ms, budget) {
  results.push({ name, ms: Math.round(ms), budget });
  const ok = ms <= budget;
  if (!ok) failures.push(`${name}: ${Math.round(ms)} ms > ${budget} ms`);
  console.log(`${ok ? "ok  " : "SLOW"} ${name.padEnd(58)} ${String(Math.round(ms)).padStart(6)} ms  (budget ${budget})`);
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1500, height: 920 } });
const pageErrors = [];
page.on("pageerror", (e) => pageErrors.push(String(e)));
await page.route("**/api/**", async (route) => {
  const req = route.request();
  const body = api(req.method(), req.url());
  if (body === undefined) return route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ error: "not_found", message: "not mocked" }) });
  if (body === null) return route.fulfill({ status: 204 });
  const text = JSON.stringify(body);
  if (req.url().includes("/series")) seriesBytes += text.length;
  return route.fulfill({ status: 200, contentType: "application/json", body: text });
});

/** Two frames, then the main thread idle: deferred renders and chart drawing (rAF, lazy updates) have finished. */
const settle = () => page.evaluate(() => new Promise((r) =>
  requestAnimationFrame(() => requestAnimationFrame(() => requestIdleCallback(() => r(), { timeout: 5000 })))));

/**
 * Time from starting {@code action} until {@code done} holds in the page and the page has settled,
 * so chart drawing and deferred renders count.
 */
async function timed(name, budget, action, done) {
  await settle();
  const t0 = Date.now();
  await action();
  await page.waitForFunction(done.fn, done.arg, { polling: "raf", timeout: 60_000 });
  await settle();
  record(name, Date.now() - t0, budget);
}
const count = (sel) => ({ fn: ([s, n]) => document.querySelectorAll(s).length >= n, arg: [sel, 0] });
const atLeast = (sel, n) => ({ fn: ([s, k]) => document.querySelectorAll(s).length >= k, arg: [sel, n] });
const text = (s) => ({ fn: (t) => document.body.innerText.includes(t), arg: s });
void count;

await timed(`open app: tree with ${CONNECTIONS} connections in ${folders.length} folders`, OPEN_BUDGET_MS,
  () => page.goto(`${origin}/#token=perf`), atLeast("[data-testid^='conn-cluster-']", CONNECTIONS));
await timed("tree: collapse a folder", INTERACTION_BUDGET_MS,
  () => page.getByText("📁 customer-3", { exact: true }).click(), {
    fn: () => !document.querySelector("[data-testid='conn-cluster-003']"), arg: null });
await timed("tree: filter by name", INTERACTION_BUDGET_MS,
  () => page.getByLabel("Search connections").fill("cluster-077"), {
    fn: () => document.querySelectorAll("[data-testid^='conn-cluster-']").length === 1, arg: null });
await page.getByLabel("Search connections").fill("");
await timed(`open a ${NODES}-node cluster (connect + overview)`, OPEN_BUDGET_MS,
  () => page.getByTestId("conn-cluster-000").dblclick(), text("Monitoring"));
await timed("open Monitoring (health view)", OPEN_BUDGET_MS,
  () => page.getByRole("button", { name: "Monitoring", exact: true }).click(), atLeast("[data-testid='monitoring-health-badge']", 1));
await timed(`Monitoring nodes table: ${NODES} rows`, OPEN_BUDGET_MS,
  () => page.getByRole("tab", { name: "Nodes" }).click(), atLeast(`[data-testid='monitoring-nodes-table'][aria-rowcount='${NODES + 1}'] tbody tr[aria-rowindex]`, 20));
await timed("nodes table: sort by load", INTERACTION_BUDGET_MS,
  () => page.getByRole("button", { name: /^Load/ }).click(), {
    fn: () => document.querySelector("[data-testid='monitoring-nodes-table'] th[aria-sort]")?.textContent?.startsWith("Load") ?? false, arg: null });
await timed(`Monitoring charts (15 min, ${NODES} nodes)`, OPEN_BUDGET_MS,
  () => page.getByRole("tab", { name: "Charts" }).click(), atLeast("[data-testid^='monitoring-chart-'] canvas", 13));
seriesBytes = 0;
await timed(`Monitoring charts: switch to 24 h (${NODES} nodes x 24 h)`, OPEN_BUDGET_MS,
  () => page.getByRole("button", { name: "24 h" }).click(), {
    fn: () => [...document.querySelectorAll("[data-testid^='monitoring-chart-']")].some((e) => (e.getAttribute("aria-label") ?? "").includes("24 h")), arg: null });
const chartBytes = seriesBytes;
await timed("charts: hide one node", INTERACTION_BUDGET_MS,
  () => page.locator(".mon-chip").first().click(), atLeast(".mon-chip.off", 1));
await timed(`Monitoring ring (${DCS} DCs)`, OPEN_BUDGET_MS,
  () => page.getByRole("tab", { name: "Ring" }).click(), atLeast("[data-testid^='monitoring-ring-'] canvas", DCS));
await timed(`open Operations: node picker with ${NODES} nodes`, OPEN_BUDGET_MS,
  () => page.getByRole("button", { name: "Operations", exact: true }).click(), atLeast("[data-testid='ops-node-picker'] .ops-node", NODES));
await timed("node picker: select all", INTERACTION_BUDGET_MS,
  () => page.getByTestId("ops-node-picker").getByRole("button", { name: "All" }).click(), text(`${NODES} of ${NODES} nodes selected`));
await timed("node picker: toggle one node", INTERACTION_BUDGET_MS,
  () => page.getByTestId("ops-node-picker").locator(".ops-node input").nth(7).click(), text(`${NODES - 1} of ${NODES} nodes selected`));
await timed("switch workspace tab back to Monitoring", INTERACTION_BUDGET_MS,
  () => page.getByRole("button", { name: "Monitoring", exact: true }).click(), atLeast("[data-testid='monitoring-panel']", 1));

console.log(`24 h chart data transferred: ${(chartBytes / 1e6).toFixed(1)} MB`);
const out = process.argv.indexOf("--json");
if (out > 0) fs.writeFileSync(process.argv[out + 1], JSON.stringify({ nodes: NODES, connections: CONNECTIONS, chartBytes, results, pageErrors }, null, 2));
await browser.close();
server.close();
if (pageErrors.length) console.log("page errors:\n  " + pageErrors.join("\n  "));
if (failures.length) {
  console.log("Over budget:\n  " + failures.join("\n  "));
  process.exit(1);
}
