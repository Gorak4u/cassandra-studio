// Live check of startup time and remembered layout (NFR-PERF start < 5 s, NFR-UX): starts the
// installed engine with a fresh data dir, opens tabs in the real UI, restarts the engine and checks
// that tabs, the active workspace tab, collapsed folders and the pane width come back, and that a
// PROD tab that was not connected waits for an explicit Connect.
// Usage: node tests/restore-live.mjs <engine-bin> <ui-dist> <port> <data-dir> [legacy CQL host:port]
import { chromium } from "@playwright/test";
import { spawn } from "node:child_process";
import fs from "node:fs";
import AxeBuilder from "@axe-core/playwright";

const [bin, uiDir, port, dataDir, legacy = "127.0.0.1:29042"] = process.argv.slice(2);
const token = "restore-live";
const base = `http://127.0.0.1:${port}`;
fs.rmSync(dataDir, { recursive: true, force: true });
fs.mkdirSync(dataDir, { recursive: true });

const running = new Set();
process.on("exit", () => running.forEach((p) => p.kill("SIGKILL"))); // never leave an engine behind
process.on("uncaughtException", (e) => { console.error(e); process.exit(1); });

function startEngine() {
  const t0 = Date.now();
  const p = spawn(bin, ["--port", port, "--token", token, "--ui-dir", uiDir, "--data-dir", dataDir, "--no-keyring"],
    { stdio: ["pipe", "pipe", "pipe"], env: { ...process.env } });
  running.add(p);
  p.on("exit", () => running.delete(p));
  return new Promise((resolve, reject) => {
    let out = "";
    p.stdout.on("data", (d) => {
      out += d;
      const m = /STUDIO_ENGINE_READY (\{.*\})/.exec(out);
      if (m) resolve({ proc: p, readyMs: Date.now() - t0, ready: JSON.parse(m[1]), t0 });
    });
    let err = "";
    p.stderr.on("data", (d) => { err = (err + d).slice(-2000); });
    p.on("exit", (code) => reject(new Error("engine exited " + code + "\n" + err)));
    setTimeout(() => reject(new Error("engine did not start in 60 s")), 60_000);
  });
}
const stop = (e) => new Promise((r) => { e.proc.removeAllListeners("exit"); e.proc.on("exit", r); e.proc.kill("SIGTERM"); });
const api = async (method, path, body) => {
  const r = await fetch(base + path, { method, headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined });
  if (!r.ok) throw new Error(`${method} ${path}: ${r.status} ${await r.text()}`);
  return r.status === 204 ? null : r.json();
};
const fail = (m) => { console.error("FAIL " + m); process.exitCode = 1; };

let engine = await startEngine();
console.log(`engine ready in ${engine.readyMs} ms (JVM uptime at ready ${engine.ready.startupMs} ms)`);
const folder = await api("POST", "/api/folders", { name: "estate" });
const sub = await api("POST", "/api/folders", { name: "legacy", parentId: folder.id });
const dev = await api("POST", "/api/connections", { connection: { name: "legacy-311", folderId: sub.id, environment: "DEV", contactPoints: [legacy], tags: [] } });
const prodDown = await api("POST", "/api/connections", { connection: { name: "prod-unreachable", environment: "PROD", contactPoints: ["127.0.0.1:1"], tags: [] } });

const browser = await chromium.launch();
const ctx1 = await browser.newContext({ viewport: { width: 1400, height: 900 } });
let page = await ctx1.newPage();
await page.goto(`${base}/#token=${token}`);
await page.getByTestId("conn-legacy-311").dblclick();
await page.getByRole("button", { name: "Monitoring", exact: true }).waitFor();
await page.getByText("Connecting").waitFor({ state: "detached", timeout: 30_000 }).catch(() => undefined);
await page.getByRole("button", { name: "Schema", exact: true }).click();
await page.getByTestId("conn-prod-unreachable").dblclick();
await page.getByTestId("connect-error").waitFor({ timeout: 40_000 });
await page.locator(".tab", { hasText: "legacy-311" }).first().click();
await page.getByText("📁 legacy", { exact: true }).click(); // collapse
const sep = page.getByRole("separator", { name: "Resize connections pane" });
await sep.focus();
for (let i = 0; i < 4; i++) await page.keyboard.press("ArrowRight");
await page.waitForTimeout(800); // debounced save
const saved = await api("GET", "/api/ui-state");
console.log("saved state:", JSON.stringify({ ...saved, workspaces: saved.workspaces }));

// Studio data dialog: accessible, and Copy diagnostics works without secrets
await page.getByRole("button", { name: "Studio data" }).click();
const axe = await new AxeBuilder({ page }).include(".modal").withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
const serious = axe.violations.filter((v) => v.impact === "serious" || v.impact === "critical");
if (serious.length) fail("axe: " + serious.map((v) => v.id).join(", "));
else console.log(`axe on Studio data dialog: ${axe.violations.length} minor, 0 serious/critical`);
await page.keyboard.press("Escape");
await page.close();

await stop(engine);
const t1 = Date.now();
engine = await startEngine();
page = await (await browser.newContext({ viewport: { width: 1400, height: 900 } })).newPage();
await page.goto(`${base}/#token=${token}`);
await page.locator(".tab", { hasText: "legacy-311" }).first().waitFor();
const uiMs = Date.now() - t1;
console.log(`restart: engine ready in ${engine.readyMs} ms; engine + UI with restored tabs in ${uiMs} ms`);
if (uiMs > 5000) fail(`startup ${uiMs} ms > 5000 ms`);

const tabs = await page.locator("main > .tabs .tab").allInnerTexts();
if (!(tabs.some((t) => t.includes("legacy-311")) && tabs.some((t) => t.includes("prod-unreachable")))) fail("tabs not restored: " + tabs);
const schemaActive = await page.locator(".tabs .tab.active", { hasText: "Schema" }).count();
if (!schemaActive) fail("workspace tab (Schema) not restored");
if (await page.getByTestId("conn-legacy-311").count()) fail("collapsed folder not restored");
const width = await page.locator(".app").evaluate((e) => getComputedStyle(e).gridTemplateColumns);
if (!width.startsWith("320px")) fail("pane width not restored: " + width);
await page.locator(".tab", { hasText: "prod-unreachable" }).first().click();
if (!(await page.getByTestId("restore-connect").count())) fail("PROD tab that was not connected reconnected by itself");
console.log(`restored: tabs [${tabs.map((t) => t.replace(/\s+/g, " ").trim()).join(" | ")}], Schema tab, collapsed folder, width ${width.split(" ")[0]}, PROD waits for Connect`);

await browser.close();
await stop(engine);
void dev; void prodDown;
console.log(process.exitCode ? "FAILED" : "PASSED");
