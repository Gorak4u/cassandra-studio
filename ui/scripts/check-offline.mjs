// Offline check of the built UI (NFR-NET air-gapped, NFR-SEC no telemetry): fails the build when dist/ could
// load anything from outside the engine's own origin.
//   - index.html: no <script>/<link>/<img>/<iframe> with an absolute or protocol-relative URL, and a CSP whose
//     sources are 'self' / loopback only.
//   - CSS files: no url(http...) or @import of a remote stylesheet (those are fetched by the browser).
//   - JS files: every absolute URL's host must be on REFERENCE_HOSTS: hosts that libraries only mention
//     (error-decoder links, docs links, XML namespaces), never fetch. A new host fails until reviewed here.
// The Electron shell additionally cancels every request that is not to the engine (desktop/main.js), and the
// CSP blocks remote scripts, fonts, images and fetches in a browser.
// Usage: node scripts/check-offline.mjs [distDir]
import { readFileSync, readdirSync, statSync } from "node:fs";
import path from "node:path";

const dist = path.resolve(process.argv[2] ?? "dist");

/** Mentioned in library code or Monaco's language data; never requested. Review before adding a host. */
const REFERENCE_HOSTS = new Set([
  // React error decoder links (console text), AG Grid docs links in console warnings
  "react.dev", "www.ag-grid.com", "ag-grid.com",
  // XML/SVG/XHTML namespaces and spec references
  "www.w3.org", "w3.org", "www.whatwg.org", "html.spec.whatwg.org", "wiki.whatwg.org", "drafts.csswg.org", "tc39.es",
  "tools.ietf.org", "www.ietf.org", "www.iana.org", "www.iso.org", "unicode.org", "cldr.unicode.org", "json-schema.org",
  "schema.org", "www.currency-iso.org", "unstats.un.org", "www.apache.org",
  // Monaco: "MDN Reference" links in the CSS/HTML language data, issue links in comments, docs links
  "developer.mozilla.org", "bugzilla.mozilla.org", "hacks.mozilla.org", "github.com", "code.visualstudio.com", "aka.ms",
  "microsoft.com", "go.microsoft.com", "sass-lang.com", "stackoverflow.com", "en.wikipedia.org", "developers.google.com",
  "support.google.com", "googlechrome.github.io", "code.google.com", "r12a.github.io", "help.yahoo.com", "www.bing.com",
  "www.dmoz.org",
]);

/**
 * Exact URL prefixes allowed despite their host: @monaco-editor/loader's default CDN path. It is replaced
 * by the local ./monaco/vs before any load (src/lib/monaco.ts, checked below) and the CSP has script-src 'self'.
 */
const OVERRIDDEN_DEFAULTS = ["https://cdn.jsdelivr.net/npm/monaco-editor@"];

const LOOPBACK = /^(127\.0\.0\.1|localhost|\[::1\])(:[\d*]+)?$/;
const problems = [];

function files(dir) {
  return readdirSync(dir).flatMap((f) => {
    const p = path.join(dir, f);
    return statSync(p).isDirectory() ? files(p) : [p];
  });
}

const rel = (f) => path.relative(dist, f);
const all = files(dist);
const text = (f) => readFileSync(f, "utf8");

// index.html
const indexFile = path.join(dist, "index.html");
const index = text(indexFile);
for (const m of index.matchAll(/<(script|link|img|iframe|source|video|audio)\b[^>]*\b(src|href)\s*=\s*["']?((?:https?:)?\/\/[^"' >]+)/gi)) {
  problems.push(`index.html: <${m[1]}> loads ${m[3]}`);
}
const csp = /http-equiv=["']Content-Security-Policy["'][^>]*content=(["'])([\s\S]*?)\1/i.exec(index)?.[2].replace(/\s+/g, " ");
if (!csp) problems.push("index.html: no Content-Security-Policy meta tag");
else {
  if (!/script-src 'self'(;|$)/.test(csp)) problems.push(`index.html: CSP script-src must be exactly 'self' (${csp})`);
  for (const src of csp.split(/[;\s]+/).filter((t) => /:\/\/|^\*$/.test(t))) {
    const host = src.replace(/^[a-z]+:\/\//, "").replace(/\/.*$/, "");
    if (src === "*" || !LOOPBACK.test(host)) problems.push(`index.html: CSP allows a non-local source ${src}`);
  }
}

// CSS
for (const f of all.filter((f) => f.endsWith(".css"))) {
  for (const m of text(f).matchAll(/(url\(\s*["']?|@import\s+["']?)((?:https?:)?\/\/[^"') ;]+)/gi)) {
    problems.push(`${rel(f)}: stylesheet fetches ${m[2]}`);
  }
}

// JS (and Monaco's HTML/JSON files)
const urlRe = /https?:\/\/([a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)+)(?::\d+)?[^\s"'`)<>\\]*/g;
for (const f of all.filter((f) => /\.(m?js|html|json)$/.test(f) && f !== indexFile)) {
  for (const m of text(f).matchAll(urlRe)) {
    const host = m[1].toLowerCase();
    if (LOOPBACK.test(host) || REFERENCE_HOSTS.has(host)) continue;
    if (OVERRIDDEN_DEFAULTS.some((p) => m[0].startsWith(p))) continue;
    problems.push(`${rel(f)}: unexpected external URL ${m[0].slice(0, 120)}`);
  }
}

// The Monaco CDN default must really be overridden by the local path.
const appJs = all.filter((f) => f.includes(`${path.sep}assets${path.sep}`) && f.endsWith(".js")).map(text).join("\n");
if (appJs.includes(OVERRIDDEN_DEFAULTS[0]) && !appJs.includes("./monaco/vs")) {
  problems.push("assets: Monaco's CDN default is present but the local ./monaco/vs override is not (src/lib/monaco.ts)");
}
if (!all.some((f) => f.endsWith(path.join("monaco", "vs", "loader.js")))) {
  problems.push("monaco/vs/loader.js missing: Monaco must be served locally (scripts/copy-monaco.mjs)");
}

if (problems.length) {
  console.error(`Offline check FAILED: ${problems.length} problem(s) in ${dist}`);
  for (const p of [...new Set(problems)].slice(0, 50)) console.error("  " + p);
  process.exit(1);
}
console.log(`Offline check OK: ${all.length} files in ${path.basename(dist)}/ load nothing from outside the engine's origin`);
