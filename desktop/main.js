// Electron shell: starts the bundled engine (Java) and shows its UI.
// The engine listens on 127.0.0.1 only, on a free port, with a per-launch token
// that is handed to the UI in the URL fragment (never sent over the network).
const { app, BrowserWindow, shell, dialog, session } = require("electron");
const { spawn } = require("node:child_process");
const path = require("node:path");
const fs = require("node:fs");

let engine = null;
let win = null;
let engineOrigin = null;

// No network from the shell itself (NFR-SEC "no telemetry", NFR-NET offline): the window only ever talks to
// the local engine. Chromium must not look for a proxy (no WPAD/PAC fetches), prefetch DNS, ping, phone home
// for components or report network errors. Proxies for Studio's own outbound calls are the engine's job.
for (const sw of ["no-proxy-server", "disable-background-networking", "disable-component-update",
  "disable-domain-reliability", "dns-prefetch-disable", "no-pings", "disable-breakpad"]) {
  app.commandLine.appendSwitch(sw);
}
app.commandLine.appendSwitch("disable-features", "SpellcheckService,AutofillServerCommunication,MediaRouter,OptimizationHints");

function resources(...p) {
  // Packaged: <app>/resources/...; development: ./resources/...
  const base = app.isPackaged ? process.resourcesPath : path.join(__dirname, "resources");
  return path.join(base, ...p);
}

function javaBinary() {
  const exe = process.platform === "win32" ? "java.exe" : "java";
  const bundled = resources("runtime", "bin", exe);
  if (fs.existsSync(bundled)) return bundled;
  return process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, "bin", exe) : exe;
}

function startEngine() {
  return new Promise((resolve, reject) => {
    const jar = resources("engine", "cassandra-studio-engine-all.jar");
    const args = [
      "-Xmx1g",
      "-Dcom.sun.jndi.rmiURLParsing=legacy",
      // Basic auth to a corporate proxy for HTTPS tunnels (the update check); the JDK disables it by default.
      "-Djdk.http.auth.tunneling.disabledSchemes=",
      "-jar", jar,
      "--host", "127.0.0.1",
      "--port", "0",
      "--ui-dir", resources("ui"),
      "--exit-on-stdin-close",
    ];
    // The engine inherits the environment on purpose: in "system proxy" mode it reads HTTPS_PROXY,
    // HTTP_PROXY and NO_PROXY (Settings > Network). Nothing else from the environment is used for networking.
    engine = spawn(javaBinary(), args, { stdio: ["pipe", "pipe", "pipe"], windowsHide: true, env: { ...process.env } });
    let buffer = "";
    let log = "";
    const timer = setTimeout(() => reject(new Error("Engine did not start within 60 s\n" + log)), 60000);
    engine.stdout.on("data", (d) => {
      buffer += d.toString();
      const m = /STUDIO_ENGINE_READY (\{.*\})/.exec(buffer);
      if (m) {
        clearTimeout(timer);
        resolve(JSON.parse(m[1]));
      }
    });
    engine.stderr.on("data", (d) => {
      log = (log + d.toString()).slice(-8000);
      process.stderr.write(d);
    });
    engine.on("exit", (code) => {
      clearTimeout(timer);
      if (win && !win.isDestroyed()) {
        dialog.showErrorBox("Cassandra Studio", `The engine stopped (exit code ${code}).\n\n${log.slice(-2000)}`);
        app.quit();
      }
      reject(new Error(`Engine exited with code ${code}\n${log}`));
    });
  });
}

async function createWindow() {
  let ready;
  try {
    ready = await startEngine();
  } catch (e) {
    dialog.showErrorBox("Cassandra Studio could not start", String(e.message || e));
    app.quit();
    return;
  }
  const origin = `http://127.0.0.1:${ready.port}`;
  engineOrigin = origin;
  win = new BrowserWindow({
    width: 1440,
    height: 900,
    title: "Cassandra Studio",
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      spellcheck: false, // no Hunspell dictionary downloads
    },
  });
  // Stay on the engine's exact origin. Only http(s) links may leave, and only to the system browser;
  // other schemes (file:, smb:, custom protocol handlers) are dropped.
  const sameOrigin = (url) => {
    try { return new URL(url).origin === origin; } catch { return false; }
  };
  const openOutside = (url) => {
    try {
      const u = new URL(url);
      if (u.protocol === "https:" || u.protocol === "http:") shell.openExternal(u.toString());
    } catch { /* not a URL: ignore */ }
  };
  win.webContents.on("will-navigate", (e, url) => {
    if (!sameOrigin(url)) {
      e.preventDefault();
      openOutside(url);
    }
  });
  win.webContents.setWindowOpenHandler(({ url }) => {
    openOutside(url);
    return { action: "deny" };
  });
  win.loadURL(`${origin}/#token=${encodeURIComponent(ready.token)}`);
}

/**
 * Every request from the window must go to the engine's exact origin (or stay in-process: data:, blob:,
 * devtools:). Anything else (a CDN, a font, a remote image) is cancelled and logged, so no data can leave the
 * machine from the UI even if a library tried. Clusters are reached by the engine, not by the window.
 */
function allowedRequest(url) {
  if (url.startsWith("data:") || url.startsWith("blob:") || url.startsWith("devtools:") || url.startsWith("chrome-extension:")) return true;
  try {
    return engineOrigin !== null && new URL(url).origin === engineOrigin;
  } catch {
    return false;
  }
}

function lockDownSession(s) {
  s.setSpellCheckerEnabled(false);
  s.setProxy({ mode: "direct" }).catch(() => undefined);
  s.webRequest.onBeforeRequest((details, callback) => {
    const ok = allowedRequest(details.url);
    if (!ok) console.warn(`blocked outbound request from the UI: ${details.url.slice(0, 200)}`);
    callback({ cancel: !ok });
  });
  // No permission prompts (camera, geolocation, notifications ...): Studio needs none.
  s.setPermissionRequestHandler((_wc, _permission, callback) => callback(false));
}

app.on("web-contents-created", (_e, contents) => {
  contents.on("will-attach-webview", (e) => e.preventDefault());
});

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on("second-instance", () => {
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
    }
  });
  app.whenReady().then(() => {
    lockDownSession(session.defaultSession);
    return createWindow();
  });
}

app.on("window-all-closed", () => app.quit());
app.on("will-quit", () => {
  if (engine && !engine.killed) {
    engine.stdin.end(); // engine exits when stdin closes
    setTimeout(() => engine && !engine.killed && engine.kill(), 3000);
  }
});
