// End-to-end smoke test: drives the real UI (served by a running engine) against real
// Cassandra clusters and saves screenshots.
// Usage: node tests/smoke.mjs <engineUrl> <token> <screenshotDir>
// Expects a multi-DC cluster (dc_east + dc_west) at MULTI_DC (default 127.0.0.1:19042)
// and Cassandra 3.11 at LEGACY (default 127.0.0.1:29042). test-env/ starts both.
import { chromium } from "@playwright/test";
import fs from "node:fs";
import AxeBuilder from "@axe-core/playwright";

const [url, token, outDir] = process.argv.slice(2);
fs.mkdirSync(outDir, { recursive: true });
const api = async (method, path, body) => {
  const r = await fetch(url + path, {
    method,
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!r.ok) throw new Error(`${method} ${path}: ${r.status} ${await r.text()}`);
  return r.status === 204 ? null : r.json();
};

// Seed folders and connections through the API (the UI flow for this is covered below by editing one).
for (const c of await api("GET", "/api/connections")) await api("DELETE", `/api/connections/${c.id}`);
for (const f of await api("GET", "/api/folders")) if (!f.parentId) await api("DELETE", `/api/folders/${f.id}`);
const acme = await api("POST", "/api/folders", { name: "acme" });
const prod = await api("POST", "/api/folders", { name: "prod", parentId: acme.id });
const nonprod = await api("POST", "/api/folders", { name: "nonprod", parentId: acme.id });
const prodConn = await api("POST", "/api/connections", { connection: {
  name: "acme-core-prod", folderId: prod.id, environment: "PROD", contactPoints: [process.env.MULTI_DC ?? "127.0.0.1:19042"],
  localDatacenter: "dc_east", defaultConsistency: "LOCAL_QUORUM", tags: ["core", "multi-dc"] } });
await api("POST", "/api/connections", { connection: {
  name: "legacy-311", folderId: nonprod.id, environment: "DEV", contactPoints: [process.env.LEGACY ?? "127.0.0.1:29042"], tags: ["3.11"] } });

const prodId = prodConn.id;
// Cassandra 5.0 with client TLS + PasswordAuthenticator (test-env profile "secure").
const securePem = process.env.SECURE_PEM ?? new URL("../../test-env/certs/node.pem", import.meta.url).pathname;
await api("POST", "/api/connections", {
  connection: { name: "secure-50", folderId: nonprod.id, environment: "STAGING",
    contactPoints: [process.env.SECURE ?? "127.0.0.1:39042"], localDatacenter: "dc1", username: "cassandra",
    tls: { enabled: true, truststorePath: securePem, truststoreType: "PEM", hostnameVerification: false } },
  secrets: { password: "cassandra" },
});
// Repeatable runs: drop what a previous run created (no confirmation needed: DEV-like cleanup via a temp DEV connection).
const cleanup = await api("POST", "/api/connections", { connection: {
  name: "cleanup", environment: "DEV", contactPoints: prodConn.contactPoints, localDatacenter: "dc_east" } });
await api("POST", `/api/clusters/${cleanup.id}/query`, { confirmed: true, stopOnError: false,
  cql: "DROP TABLE IF EXISTS shop.events_e2e; UPDATE shop.orders SET status = 'paid' WHERE customer = 'alice' AND id = 1;" }).catch(() => undefined);
await api("DELETE", `/api/connections/${cleanup.id}`);

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1500, height: 920 } });
const page = await context.newPage();
const errors = [];
page.on("pageerror", (e) => errors.push(String(e)));
// On any failure, keep a screenshot of the moment it happened.
const onFailure = async (e) => {
  console.error(e);
  await page.screenshot({ path: `${outDir}/FAILURE.png` }).catch(() => undefined);
  process.exit(1);
};
process.on("unhandledRejection", onFailure);
process.on("uncaughtException", onFailure);
const shot = async (name) => page.screenshot({ path: `${outDir}/${name}.png` });
// Accessibility (NFR-A11Y): WCAG 2.1 A/AA rules on each main screen. Third-party widgets
// (Monaco, AG Grid internals) are scanned too; serious and critical findings fail the run.
const a11yProblems = [];
const a11y = async (screen) => {
  const r = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  for (const v of r.violations) {
    a11yProblems.push(`${screen}: [${v.impact}] ${v.id} (${v.nodes.length}) ${v.help} :: ${v.nodes.slice(0, 2).map((n) => n.target.join(" ")).join(" | ")}`);
  }
};
const step = (s) => console.log("•", s);

await page.goto(`${url}/#token=${token}`);
await page.getByTestId("conn-acme-core-prod").waitFor();
step("connection tree loaded");
await shot("01-connections");
await a11y("connections");

// Open the multi-DC PROD cluster: overview shows both DCs.
await page.getByTestId("conn-acme-core-prod").dblclick();
await page.getByTestId("prod-banner").waitFor();
await page.getByRole("button", { name: "Overview" }).click();
await page.getByTestId("health").waitFor();
await page.getByText("dc_west").first().waitFor();
step("overview: " + (await page.getByTestId("health").textContent()));
await shot("02-overview-multi-dc");
await a11y("overview");

// Create a keyspace replicated to both DCs: the PROD guard asks for the cluster name.
await page.getByRole("button", { name: "Query", exact: true }).click();
const editor = page.locator(".monaco-editor").first();
await editor.click();
await page.keyboard.press("ControlOrMeta+A");
await page.keyboard.type(
  "CREATE KEYSPACE IF NOT EXISTS shop WITH replication = {'class': 'NetworkTopologyStrategy', 'dc_east': 2, 'dc_west': 1};\n" +
  "CREATE TABLE IF NOT EXISTS shop.orders (customer text, id int, total decimal, status text, PRIMARY KEY (customer, id));\n" +
  "INSERT INTO shop.orders (customer, id, total, status) VALUES ('alice', 1, 42.50, 'paid');\n" +
  "INSERT INTO shop.orders (customer, id, total, status) VALUES ('alice', 2, 10.00, 'new');\n" +
  "INSERT INTO shop.orders (customer, id, total, status) VALUES ('bob', 1, 99.90, 'paid');\n",
);
await page.getByRole("button", { name: "▶▶ Run script" }).click();
await page.getByPlaceholder('Type "acme-core-prod" to confirm').waitFor();
step("PROD confirmation dialog shown");
await shot("03-prod-confirm");
await a11y("confirm dialog");
await page.getByPlaceholder('Type "acme-core-prod" to confirm').fill("acme-core-prod");
await page.getByRole("button", { name: "Run", exact: true }).click();
await page.locator(".status.ok").first().waitFor();
step("DDL + inserts ran");

// Query pinned to the dc_west node (coordinator in the remote DC).
const nodeSelect = page.getByLabel("Coordinator node");
const westOption = await nodeSelect.locator("option", { hasText: "dc_west" }).first().getAttribute("value");
await nodeSelect.selectOption(westOption);
await page.getByLabel("Consistency level").selectOption("ONE");
await page.getByLabel("Tracing").check().catch(() => page.getByText("Tracing").click());
await editor.click();
await page.keyboard.press("ControlOrMeta+A");
await page.keyboard.type("SELECT * FROM shop.orders WHERE customer = 'alice';");
await page.getByTestId("run").click();
await page.getByTestId("result-grid").waitFor();
await page.getByText(`coordinator ${westOption}`).waitFor();
step("query pinned to dc_west node " + westOption);
await shot("04-query-pinned-dc-west");
await a11y("query + results");

// Several workspaces stay mounted (hidden) so their state survives tab switches; act on the visible one.
const visible = (locator) => locator.filter({ visible: true });
const confirmProd = async () => {
  const input = page.getByPlaceholder('Type "acme-core-prod" to confirm');
  await input.waitFor();
  await input.fill("acme-core-prod");
  await page.getByRole("button", { name: "Run", exact: true }).click();
};
const queryRows = async (cql) =>
  (await api("POST", `/api/clusters/${prodId}/query`, { cql, consistency: "LOCAL_QUORUM" })).results[0].rows;

// Grid editing (CQL-9): change a cell, apply, confirm on PROD, verify in Cassandra.
await nodeSelect.selectOption("");
await editor.click();
await page.keyboard.press("ControlOrMeta+A");
await page.keyboard.type("SELECT customer, id, status, total FROM shop.orders WHERE customer = 'alice';");
// Wait for this query's response so "Edit data" belongs to the new result, not the previous one.
await Promise.all([
  page.waitForResponse((r) => r.url().includes("/query") && r.request().method() === "POST"),
  page.getByTestId("run").click(),
]);
await page.getByRole("button", { name: "✎ Edit data" }).click();
await page.getByRole("button", { name: "+ Row" }).waitFor();
const statusCell = page.locator('.ag-row[row-index="0"] [col-id="c2"]');
await statusCell.dblclick();
await page.keyboard.press("ControlOrMeta+A");
await page.keyboard.type("refunded");
await page.keyboard.press("Enter");
await page.getByRole("button", { name: "Apply 1 change(s)" }).click();
await confirmProd();
await page.getByText("Applied 1 change(s)").waitFor();
const edited = await queryRows("SELECT status FROM shop.orders WHERE customer = 'alice' AND id = 1");
if (edited[0][0] !== "refunded") throw new Error("grid edit not applied: " + JSON.stringify(edited));
step("grid edit applied and verified in Cassandra");
await shot("05a-grid-edit");

// Schema browser and the create-table form (SCH-4).
await page.getByRole("button", { name: "Schema" }).click();
await page.getByText("🗄 shop").click();
await page.getByText("▦ orders").click();
await page.getByText("partition key #1").waitFor();
step("schema browser");
await shot("05-schema");
await a11y("schema");
await page.getByText("🗄 shop").click();
await page.getByText("🗄 shop").click();
await page.getByRole("button", { name: "+ Table" }).click();
await page.getByLabel("Table name").fill("events_e2e");
await page.getByLabel("Column name").nth(1).fill("note");
await page.getByRole("button", { name: "Review CQL…" }).click();
await confirmProd();
await page.getByText("▦ events_e2e").waitFor();
step("create-table form created shop.events_e2e");

// Users & roles on a multi-DC cluster without login: roles are readable (at ONE) and the
// unsafe system_auth replication is reported (SEC-3).
await page.getByRole("button", { name: "Users & roles" }).click();
await page.getByText("👤 cassandra").waitFor();
await page.getByTestId("auth-warning").first().waitFor();
step("roles readable on multi-DC cluster; system_auth replication warning shown");

// Users & roles (SEC-1, SEC-2, SEC-4) on the TLS + login cluster: create, grant, effective permissions, drop.
await page.getByTestId("conn-secure-50").dblclick();
await page.getByRole("button", { name: "Users & roles" }).click();
await visible(page.getByText("👤 cassandra")).waitFor();
await page.getByRole("button", { name: "+ Role" }).click();
await page.getByLabel("Role name").fill("studio_e2e");
await page.getByLabel("Password").fill("E2e-Passw0rd!");
await page.getByRole("button", { name: "Review CQL…" }).click();
await page.getByRole("button", { name: "Run", exact: true }).click();
await visible(page.getByText("👤 studio_e2e")).click();
await page.getByRole("button", { name: "Grant…" }).click();
await page.getByRole("dialog").locator("select").nth(1).selectOption("ALL_KEYSPACES");
await page.getByRole("button", { name: "Review CQL…" }).click();
await page.getByRole("button", { name: "Run", exact: true }).click();
await visible(page.getByRole("cell", { name: "<all keyspaces>" })).waitFor();
step("role created over TLS + login, SELECT granted, effective permissions shown");
await shot("05b-roles");
await a11y("roles");
await page.getByRole("button", { name: "Drop", exact: true }).click();
await page.getByRole("button", { name: "Run", exact: true }).click();
await visible(page.getByText("👤 studio_e2e")).waitFor({ state: "detached" });
step("role dropped");

// Audit log has the PROD changes.
await page.getByRole("button", { name: "Audit log" }).first().click();
await page.getByText("CREATE KEYSPACE").first().waitFor();
await shot("06-audit");

// Cassandra 3.11 (Java 8) cluster in the same session.
await page.getByTestId("conn-legacy-311").dblclick();
await page.getByRole("button", { name: "Overview" }).last().click();
await page.getByText("3.11").first().waitFor();
step("3.11 overview");
await shot("07-legacy-311");

await browser.close();
console.log(a11yProblems.length ? "ACCESSIBILITY:\n" + a11yProblems.join("\n") : "accessibility: no WCAG A/AA violations");
if (a11yProblems.some((p) => /\[(serious|critical)\]/.test(p)) && process.env.A11Y_REPORT_ONLY !== "1") {
  console.error("serious/critical accessibility violations");
  process.exit(1);
}
if (errors.length) {
  console.error("PAGE ERRORS:\n" + errors.join("\n"));
  process.exit(1);
}
console.log("no page errors");
