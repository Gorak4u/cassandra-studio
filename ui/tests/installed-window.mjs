// Attaches to an installed Cassandra Studio started with --remote-debugging-port and checks the
// window shows a usable UI and the bundled engine reports the expected version.
// Used by scripts/installer-check-linux.sh on fresh containers.
// Usage: node tests/installed-window.mjs <cdpUrl> <expectedVersion|""> <screenshot.png>
import { chromium } from "@playwright/test";

const [cdpUrl, expected, shot] = process.argv.slice(2);
const started = Date.now();
let browser;
for (let i = 0; i < 90 && !browser; i++) {
  try {
    browser = await chromium.connectOverCDP(cdpUrl);
  } catch {
    await new Promise((r) => setTimeout(r, 1000));
  }
}
if (!browser) throw new Error(`no app on ${cdpUrl} after 90 s`);
let page;
for (let i = 0; i < 60 && !page; i++) {
  page = browser.contexts().flatMap((c) => c.pages()).find((p) => p.url().startsWith("http://127.0.0.1"));
  if (!page) await new Promise((r) => setTimeout(r, 1000));
}
if (!page) throw new Error("the app window never loaded the engine's UI");
await page.getByRole("button", { name: "+ Connection" }).waitFor({ timeout: 60000 });
const info = await page.evaluate(async () => {
  const token = new URLSearchParams(location.hash.slice(1)).get("token");
  const r = await fetch("/api/info", { headers: { Authorization: `Bearer ${token}` } });
  return r.json();
});
await page.screenshot({ path: shot });
console.log(`usable UI after ${Date.now() - started} ms (from attach); engine ${info.version}, secrets in ${String(info.secretStore).split(" (")[0]}`);
await browser.close();
if (expected && info.version !== expected) {
  console.error(`expected engine version ${expected}, got ${info.version}`);
  process.exit(1);
}
console.log("installed window OK");
