// The guides bundled into the UI build (NFR-DOCS): docs/guide/**/*.md and docs/release-notes/*.md,
// imported as raw text so the in-app help works fully offline.
import { parseMarkdown, plainText, type Block } from "./markdownParse";
import type { LinkTarget } from "./Markdown";

const RAW = import.meta.glob(["../../../../docs/guide/**/*.md", "../../../../docs/release-notes/*.md"], {
  query: "?raw", import: "default", eager: true,
}) as Record<string, string>;

export interface HelpDoc {
  /** Path under docs/, e.g. "guide/runbooks/repair.md". */
  path: string;
  title: string;
  blocks: Block[];
}

const PREFIX = "../../../../docs/";

/** Sort order in the guide picker: user guide, install guide, runbooks index, runbooks, release notes. */
function rank(path: string): string {
  if (path === "guide/user-guide.md") return "0";
  if (path === "guide/install.md") return "1";
  if (path === "guide/runbooks/README.md") return "2";
  if (path.startsWith("guide/runbooks/")) return "3" + path;
  if (path.startsWith("guide/")) return "4" + path;
  return "5" + path;
}

function load(raw: Record<string, string>): Map<string, HelpDoc> {
  const docs = [...Object.entries(raw)].map(([key, text]) => {
    const path = key.startsWith(PREFIX) ? key.slice(PREFIX.length) : key;
    const blocks = parseMarkdown(text);
    const h1 = blocks.find((b) => b.type === "heading" && b.level === 1);
    const title = h1 && h1.type === "heading" ? plainText(h1.children) : path;
    return { path, title, blocks };
  });
  docs.sort((a, b) => rank(a.path).localeCompare(rank(b.path)));
  return new Map(docs.map((d) => [d.path, d]));
}

let cache: Map<string, HelpDoc> | null = null;

/** Every bundled document by path, parsed once. */
export function helpDocs(): Map<string, HelpDoc> {
  cache ??= load(RAW);
  return cache;
}

/** Normalises "a/b/../c/./d.md" to "a/c/d.md". */
export function normalizePath(path: string): string {
  const out: string[] = [];
  for (const part of path.split("/")) {
    if (part === "" || part === ".") continue;
    if (part === "..") out.pop();
    else out.push(part);
  }
  return out.join("/");
}

/**
 * Where a link in document `from` points: another bundled document (with an optional anchor), a
 * web page (http, https or mailto, opened outside the app), or nowhere (anything else, e.g. a
 * file that is not bundled or a javascript: URL).
 */
export function resolveLink(from: string, href: string, docs: Map<string, HelpDoc> = helpDocs()): LinkTarget {
  const h = href.trim();
  if (/^(https?:|mailto:)/i.test(h)) return { kind: "external", url: h };
  if (/^[a-z][a-z0-9+.-]*:/i.test(h) || h.startsWith("//")) return { kind: "none" };
  const hash = h.indexOf("#");
  const file = hash >= 0 ? h.slice(0, hash) : h;
  const anchor = hash >= 0 ? decodeURIComponent(h.slice(hash + 1)) || null : null;
  if (!file) return { kind: "doc", path: from, anchor };
  const dir = from.includes("/") ? from.slice(0, from.lastIndexOf("/") + 1) : "";
  const path = normalizePath(dir + file);
  return docs.has(path) ? { kind: "doc", path, anchor } : { kind: "none" };
}

/** Heading ids of a document (for checking anchors). */
export function headingIds(doc: HelpDoc): Set<string> {
  const ids = new Set<string>();
  const walk = (bs: Block[]) => bs.forEach((b) => {
    if (b.type === "heading") ids.add(b.id);
    else if (b.type === "quote") walk(b.children);
    else if (b.type === "list") b.items.forEach(walk);
  });
  walk(doc.blocks);
  return ids;
}
