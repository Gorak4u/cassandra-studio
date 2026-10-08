// Launches the real Electron app (desktop/) under a display, checks the engine starts and the
// UI renders inside the window, measures startup time (NFR-PERF), and checks the engine
// stops when the window closes.
// Usage: node tests/desktop-window.mjs <desktopDir> <screenshot.png> [maxStartupMs]
import { _electron as electron } from "@playwright/test";
import { execSync } from "node:child_process";
import { createRequire } from "node:module";
import path from "node:path";

const [desktopDir, shot, maxMs = "15000"] = process.argv.slice(2);
const started = Date.now();
const args = [desktopDir];
if (process.getuid?.() === 0) args.push("--no-sandbox"); // Chromium refuses to sandbox as root (containers)
// The desktop package's own Electron (require("electron") returns the binary path).
const executablePath = createRequire(path.join(path.resolve(desktopDir), "package.json"))("electron");
const app = await electron.launch({ executablePath, args, env: { ...process.env, ELECTRON_ENABLE_LOGGING: "1" } });
const win = await app.firstWindow();
await win.getByText("Cassandra Studio").first().waitFor({ timeout: 60000 });
await win.getByRole("button", { name: "+ Connection" }).waitFor({ timeout: 60000 });
const ms = Date.now() - started;
console.log(`window title: ${await win.title()}`);
console.log(`url: ${win.url().replace(/#token=.*/, "#token=***")}`);
console.log(`startup to usable UI: ${ms} ms`);
await win.screenshot({ path: shot });

const enginesBefore = countEngines();
await app.close();
let left = countEngines();
for (let i = 0; i < 20 && left > 0; i++) {
  await new Promise((r) => setTimeout(r, 500));
  left = countEngines();
}
console.log(`engine processes: ${enginesBefore} while open, ${left} after close`);
if (left > 0) { console.error("engine still running after the window closed"); process.exit(1); }
if (ms > Number(maxMs)) { console.error(`startup ${ms} ms exceeds ${maxMs} ms`); process.exit(1); }
console.log("desktop window OK");

function countEngines() {
  if (process.platform === "win32") return 0; // checked on Linux/macOS
  try {
    const out = execSync("pgrep -af \"[c]assandra-studio-engine-all[.]jar\" || true").toString().trim().split("\n").filter(Boolean);
    if (process.env.DEBUG_ENGINES) console.log(out.map((l) => l.slice(0, 140)).join("\n"));
    return out.length;
  } catch {
    return 0;
  }
}
