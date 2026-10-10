// v1.0 acceptance run (requirement.txt §7, criteria 6, 8, 9, 10, 11) through the real UI against the
// test-env. Run after tests/smoke.mjs, which creates the "acme-core-prod" connection (PROD, JMX over SSH).
// Usage: node tests/acceptance.mjs <engineUrl> <token> <screenshotDir>
import { chromium } from "@playwright/test";
import fs from "node:fs";
import AxeBuilder from "@axe-core/playwright";

const [url, token, outDir] = process.argv.slice(2);
fs.mkdirSync(outDir, { recursive: true });
const CONN = "acme-core-prod";
const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1500, height: 1000 } });
const page = await context.newPage();
const errors = [];
page.on("pageerror", (e) => errors.push(String(e)));
const fail = async (e) => {
  console.error(e);
  await page.screenshot({ path: `${outDir}/ACCEPTANCE-FAILURE.png`, fullPage: true }).catch(() => undefined);
  process.exit(1);
};
process.on("unhandledRejection", fail);
process.on("uncaughtException", fail);
const shot = (name) => page.screenshot({ path: `${outDir}/${name}.png`, fullPage: true });
const step = (s) => console.log("•", s);
const vis = (l) => l.filter({ visible: true });
const a11yProblems = [];
const a11y = async (screen) => {
  const r = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  for (const v of r.violations) a11yProblems.push(`${screen}: [${v.impact}] ${v.id} ${v.help}`);
};
const tab = async (name) => vis(page.getByRole("button", { name, exact: true })).first().click();
const subTab = async (name) => vis(page.getByRole("tab", { name, exact: true })).first().click();
// PROD: the engine asks for the connection name typed (428); the dialog retries with it.
const confirmProd = async () => {
  const input = page.getByPlaceholder(`Type "${CONN}" to confirm`);
  await input.waitFor();
  await input.fill(CONN);
  await page.getByRole("button", { name: "Run", exact: true }).click();
};
const jobDone = async (scope, timeout = 180_000) => {
  const job = vis(scope.getByTestId("job-progress")).last();
  await job.waitFor();
  await page.waitForFunction((el) => ["SUCCEEDED", "FAILED", "CANCELLED"].includes(el.dataset.state),
    await job.elementHandle(), { timeout });
  const state = await job.getAttribute("data-state");
  if (state !== "SUCCEEDED") throw new Error(`job ended ${state}: ${await job.innerText()}`);
  return job;
};

await page.goto(`${url}/#token=${token}`);
await page.getByTestId("conn-" + CONN).dblclick();
await page.getByTestId("prod-banner").waitFor();

// 6. Flush + repair on one node with progress; the audit log records both.
await tab("Operations");
const ops = vis(page.getByTestId("operations-panel"));
await subTab("Maintenance");
await ops.getByRole("combobox", { name: "Operation" }).selectOption({ label: "Flush" });
await ops.getByRole("combobox", { name: "Keyspace" }).selectOption("shop");
await ops.getByRole("button", { name: /^Run on 1 node/ }).click();
await confirmProd();
await jobDone(ops);
step("6: flush on one node succeeded");
await subTab("Repair");
await ops.getByRole("combobox", { name: "Keyspace" }).selectOption("shop");
await ops.getByRole("button", { name: /^Repair on 1 node/ }).click();
await confirmProd();
const repair = await jobDone(ops);
step("6: repair on one node succeeded: " + (await repair.innerText()).split("\n").slice(0, 2).join(" "));
await shot("ac6-repair");
await a11y("operations");
await page.getByRole("button", { name: "Audit log" }).first().click();
await vis(page.getByRole("cell", { name: /^ops · Full repair/ })).first().waitFor();
await vis(page.getByRole("cell", { name: /^ops · Flush shop/ })).first().waitFor();
step("6: audit log has the flush and the repair");
await shot("ac6-audit");
await page.getByTestId("conn-" + CONN).dblclick(); // back to the cluster's workspace
await page.getByTestId("prod-banner").waitFor();

// 9. Thread dump and top threads over JMX.
await tab("Diagnostics");
const diag = vis(page.getByTestId("diagnostics-panel"));
await diag.getByRole("button", { name: "Take thread dump" }).click();
await vis(diag.getByTestId("diag-dump")).waitFor({ timeout: 60_000 });
step("9: thread dump taken");
await diag.getByRole("button", { name: "Top threads (live)" }).click();
await vis(diag.getByTestId("diag-top")).waitFor({ timeout: 60_000 });
await page.waitForTimeout(4000);
step("9: top threads shown");
await shot("ac9-top-threads");
await a11y("diagnostics");

// 8. GC log loaded from a node over SSH: pauses, charts, at least one finding.
await tab("GC logs");
const gc = vis(page.getByTestId("gclogs-panel"));
await gc.getByRole("button", { name: "Find GC logs" }).click();
await vis(gc.getByTestId("gclog-discovery")).waitFor({ timeout: 60_000 });
await gc.getByTestId("gclog-load").click();
await vis(gc.getByTestId("gclog-report")).waitFor({ timeout: 180_000 });
const findings = await vis(gc.locator('[data-testid^="gclog-finding-"]')).count();
if (findings < 1) throw new Error("GC report has no findings");
step(`8: GC log loaded over SSH, ${findings} finding(s)`);
await shot("ac8-gclog");
await a11y("gc logs");

// 11. Drift report.
await tab("Config");
const cfg = vis(page.getByTestId("config-panel"));
await cfg.getByRole("button", { name: "Collect config" }).click();
await vis(cfg.getByTestId("config-collected")).waitFor({ timeout: 120_000 });
await subTab("Drift report");
await vis(cfg.getByTestId("config-drift-summary")).waitFor();
step("11: drift report: " + (await vis(cfg.getByTestId("config-drift-summary")).innerText()).replace(/\s+/g, " ").slice(0, 160));
await shot("ac11-drift");
await a11y("config drift");

// 10. Backup through the estate scripts, then in the catalogue.
await tab("Backups");
const bak = vis(page.getByTestId("backups-panel"));
const runNow = bak.getByRole("button", { name: "Run backup now…" });
await page.waitForTimeout(2000);
if (!(await runNow.isVisible())) { // first run: choose the estate scripts, run as the SSH user (test-env stub)
  await bak.getByLabel("Estate backup scripts").check();
  await bak.getByRole("combobox", { name: /run as/i }).selectOption({ label: "the SSH user" });
  await bak.getByRole("button", { name: "Save" }).click();
}
await vis(bak.getByTestId("backup-run")).waitFor();
await runNow.click();
await confirmProd();
await jobDone(bak, 600_000);
await vis(bak.getByTestId("backup-catalogue-table")).waitFor({ timeout: 60_000 });
step("10: estate backup ran; catalogue rows: " + (await vis(bak.getByTestId("backup-catalogue-table")).locator("tbody tr").count()));
await shot("ac10-backup");
await a11y("backups");

await browser.close();
console.log(a11yProblems.length ? "ACCESSIBILITY:\n" + a11yProblems.join("\n") : "accessibility: no WCAG A/AA violations");
if (a11yProblems.some((p) => /\[(serious|critical)\]/.test(p))) process.exit(1);
if (errors.length) { console.error("page errors:", errors); process.exit(1); }
console.log("acceptance 6, 8, 9, 10, 11: passed");
