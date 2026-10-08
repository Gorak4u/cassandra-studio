// End-to-end smoke test: drives the real UI (served by a running engine) against real
// Cassandra clusters and saves screenshots.
// Usage: node tests/smoke.mjs <engineUrl> <token> <screenshotDir>
// Expects: a multi-DC cluster on 127.0.0.1:19042 (dc_east + dc_west) and Cassandra 3.11 on 127.0.0.1:29042.
import { chromium } from "playwright";
import fs from "node:fs";

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
await api("POST", "/api/connections", { connection: {
  name: "acme-core-prod", folderId: prod.id, environment: "PROD", contactPoints: ["127.0.0.1:19042"],
  localDatacenter: "dc_east", defaultConsistency: "LOCAL_QUORUM", tags: ["core", "multi-dc"] } });
await api("POST", "/api/connections", { connection: {
  name: "legacy-311", folderId: nonprod.id, environment: "DEV", contactPoints: ["127.0.0.1:29042"], tags: ["3.11"] } });

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1500, height: 920 } });
const errors = [];
page.on("pageerror", (e) => errors.push(String(e)));
const shot = async (name) => page.screenshot({ path: `${outDir}/${name}.png` });
const step = (s) => console.log("•", s);

await page.goto(`${url}/#token=${token}`);
await page.getByTestId("conn-acme-core-prod").waitFor();
step("connection tree loaded");
await shot("01-connections");

// Open the multi-DC PROD cluster: overview shows both DCs.
await page.getByTestId("conn-acme-core-prod").dblclick();
await page.getByTestId("prod-banner").waitFor();
await page.getByRole("button", { name: "Overview" }).click();
await page.getByTestId("health").waitFor();
await page.getByText("dc_west").first().waitFor();
step("overview: " + (await page.getByTestId("health").textContent()));
await shot("02-overview-multi-dc");

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

// Schema browser.
await page.getByRole("button", { name: "Schema" }).click();
await page.getByText("🗄 shop").click();
await page.getByText("▦ orders").click();
await page.getByText("partition key #1").waitFor();
step("schema browser");
await shot("05-schema");

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

console.log(errors.length ? "PAGE ERRORS:\n" + errors.join("\n") : "no page errors");
await browser.close();
