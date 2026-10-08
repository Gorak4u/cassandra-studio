// Copies Monaco's prebuilt files into public/ so the app loads them from its own origin.
import { cpSync, existsSync, rmSync } from "node:fs";
const from = "node_modules/monaco-editor/min/vs";
const to = "public/monaco/vs";
if (!existsSync(from)) throw new Error("monaco-editor is not installed; run npm ci");
rmSync(to, { recursive: true, force: true });
cpSync(from, to, { recursive: true });
console.log("copied Monaco to", to);
