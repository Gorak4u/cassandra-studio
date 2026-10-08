// Electron shell: starts the bundled engine (Java) and shows its UI.
// The engine listens on 127.0.0.1 only, on a free port, with a per-launch token
// that is handed to the UI in the URL fragment (never sent over the network).
const { app, BrowserWindow, shell, dialog } = require("electron");
const { spawn } = require("node:child_process");
const path = require("node:path");
const fs = require("node:fs");

let engine = null;
let win = null;

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
      "-jar", jar,
      "--host", "127.0.0.1",
      "--port", "0",
      "--ui-dir", resources("ui"),
      "--exit-on-stdin-close",
    ];
    engine = spawn(javaBinary(), args, { stdio: ["pipe", "pipe", "pipe"], windowsHide: true });
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
  win = new BrowserWindow({
    width: 1440,
    height: 900,
    title: "Cassandra Studio",
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  // Stay on the engine's origin; anything else opens in the system browser.
  win.webContents.on("will-navigate", (e, url) => {
    if (!url.startsWith(origin)) {
      e.preventDefault();
      shell.openExternal(url);
    }
  });
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith("https://")) shell.openExternal(url);
    return { action: "deny" };
  });
  win.loadURL(`${origin}/#token=${encodeURIComponent(ready.token)}`);
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
  app.whenReady().then(createWindow);
}

app.on("window-all-closed", () => app.quit());
app.on("will-quit", () => {
  if (engine && !engine.killed) {
    engine.stdin.end(); // engine exits when stdin closes
    setTimeout(() => engine && !engine.killed && engine.kill(), 3000);
  }
});
