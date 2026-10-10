import { describe, expect, it } from "vitest";
import { headingIds, helpDocs, normalizePath, resolveLink } from "../docs";
import type { Block, Inline } from "../markdown";
import { HELP_TOPICS } from "../topics";

const docs = helpDocs();

function links(blocks: Block[]): string[] {
  const out: string[] = [];
  const inl = (xs: Inline[]) => xs.forEach((x) => {
    if (x.type === "link") out.push(x.href);
    if (x.type === "strong" || x.type === "em" || x.type === "link") inl(x.children);
  });
  const walk = (bs: Block[]) => bs.forEach((b) => {
    if (b.type === "heading" || b.type === "paragraph") inl(b.children);
    else if (b.type === "list") b.items.forEach(walk);
    else if (b.type === "quote") walk(b.children);
    else if (b.type === "table") { b.header.forEach(inl); b.rows.forEach((r) => r.forEach(inl)); }
  });
  walk(blocks);
  return out;
}

describe("bundled guides", () => {
  it("includes the user guide, install guide, every runbook and the release notes", () => {
    const paths = [...docs.keys()];
    expect(paths[0]).toBe("guide/user-guide.md");
    expect(paths).toContain("guide/install.md");
    expect(paths).toContain("release-notes/v1.0.0.md");
    for (const r of ["flush", "compaction", "cleanup", "scrub", "upgradesstables", "garbagecollect", "repair", "snapshots",
      "backup-run-now", "bulk-unload", "bulk-load", "drift-investigation", "gc-log-investigation", "thread-dump-investigation"]) {
      expect(paths).toContain(`guide/runbooks/${r}.md`);
    }
    for (const d of docs.values()) expect(d.title).not.toBe(d.path); // every doc has an h1
  });

  it("every panel topic points at an existing document and heading", () => {
    for (const [topic, t] of Object.entries(HELP_TOPICS)) {
      const doc = docs.get(t.path);
      expect(doc, topic).toBeDefined();
      if (t.anchor) expect(headingIds(doc!).has(t.anchor), `${topic} → ${t.path}#${t.anchor}`).toBe(true);
    }
  });

  it("every relative link between guides resolves, anchors included", () => {
    const broken: string[] = [];
    for (const d of docs.values()) {
      for (const href of links(d.blocks)) {
        const t = resolveLink(d.path, href);
        if (t.kind === "external") continue;
        if (t.kind === "none") { broken.push(`${d.path}: ${href}`); continue; }
        if (t.anchor && !headingIds(docs.get(t.path)!).has(t.anchor)) broken.push(`${d.path}: ${href} (no such heading)`);
      }
    }
    expect(broken).toEqual([]);
  });

  it("leaves no unparsed Markdown markers in the text", () => {
    const leftovers: string[] = [];
    const inl = (path: string, xs: Inline[]) => xs.forEach((x) => {
      if (x.type === "text" && /\*\*|\]\(|`/.test(x.text)) leftovers.push(`${path}: ${x.text}`);
      if (x.type === "strong" || x.type === "em" || x.type === "link") inl(path, x.children);
    });
    const walk = (path: string, bs: Block[]) => bs.forEach((b) => {
      if (b.type === "heading" || b.type === "paragraph") inl(path, b.children);
      else if (b.type === "list") b.items.forEach((i) => walk(path, i));
      else if (b.type === "quote") walk(path, b.children);
      else if (b.type === "table") { b.header.forEach((c) => inl(path, c)); b.rows.forEach((r) => r.forEach((c) => inl(path, c))); }
    });
    for (const d of docs.values()) walk(d.path, d.blocks);
    expect(leftovers).toEqual([]);
  });

  it("every runbook has the required sections", () => {
    for (const d of docs.values()) {
      if (!d.path.startsWith("guide/runbooks/") || d.path.endsWith("README.md")) continue;
      const ids = headingIds(d);
      for (const s of ["when-to-use", "before-you-start", "steps-in-studio", "what-the-confirmation-shows", "verify", "stop-or-roll-back"]) {
        expect(ids.has(s), `${d.path} lacks ${s}`).toBe(true);
      }
    }
  });
});

describe("resolveLink", () => {
  it("resolves relative paths, anchors and external links", () => {
    expect(normalizePath("guide/runbooks/../install.md")).toBe("guide/install.md");
    expect(resolveLink("guide/runbooks/repair.md", "../install.md#logs")).toEqual({ kind: "doc", path: "guide/install.md", anchor: "logs" });
    expect(resolveLink("guide/user-guide.md", "#jobs")).toEqual({ kind: "doc", path: "guide/user-guide.md", anchor: "jobs" });
    expect(resolveLink("guide/user-guide.md", "../release-notes/v1.0.0.md")).toEqual({ kind: "doc", path: "release-notes/v1.0.0.md", anchor: null });
    expect(resolveLink("guide/user-guide.md", "https://example.org/x")).toEqual({ kind: "external", url: "https://example.org/x" });
  });

  it("refuses unknown files and unsafe schemes", () => {
    expect(resolveLink("guide/user-guide.md", "nope.md")).toEqual({ kind: "none" });
    expect(resolveLink("guide/user-guide.md", "javascript:alert(1)")).toEqual({ kind: "none" });
    expect(resolveLink("guide/user-guide.md", "file:///etc/passwd")).toEqual({ kind: "none" });
    expect(resolveLink("guide/user-guide.md", "//evil.example/x")).toEqual({ kind: "none" });
  });
});
