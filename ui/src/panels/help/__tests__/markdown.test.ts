import { describe, expect, it } from "vitest";
import { parseInline, parseMarkdown, plainText, slugify, type Block } from "../markdown";

/** Text of a paragraph block. */
function para(b: Block): string {
  if (b.type !== "paragraph") throw new Error(`expected a paragraph, got ${b.type}`);
  return plainText(b.children);
}

describe("slugify", () => {
  it("makes GitHub-style anchors", () => {
    expect(slugify("Environments, PROD and read-only safety")).toBe("environments-prod-and-read-only-safety");
    expect(slugify("Limits in v1.0")).toBe("limits-in-v10");
    expect(slugify("Node prerequisites (SSH and JMX)")).toBe("node-prerequisites-ssh-and-jmx");
    expect(slugify("Runbook: repair (full / incremental)")).toBe("runbook-repair-full--incremental");
  });
});

describe("parseInline", () => {
  it("parses code, bold, italic and links", () => {
    expect(parseInline("a `x*y*` **b** *c* [d](e.md#f)")).toEqual([
      { type: "text", text: "a " },
      { type: "code", text: "x*y*" },
      { type: "text", text: " " },
      { type: "strong", children: [{ type: "text", text: "b" }] },
      { type: "text", text: " " },
      { type: "em", children: [{ type: "text", text: "c" }] },
      { type: "text", text: " " },
      { type: "link", href: "e.md#f", children: [{ type: "text", text: "d" }] },
    ]);
  });

  it("keeps HTML and snake_case as plain text", () => {
    expect(parseInline("<script>alert(1)</script> read_request_timeout_in_ms")).toEqual([
      { type: "text", text: "<script>alert(1)</script> read_request_timeout_in_ms" },
    ]);
  });

  it("handles escapes, unmatched markers and nested formatting", () => {
    expect(plainText(parseInline("\\*not em\\* 2 * 3 * 4"))).toBe("*not em* 2 * 3 * 4");
    expect(parseInline("**bold with `code` and *em***")[0]).toMatchObject({ type: "strong" });
    expect(parseInline("[**Run**](x.md)")[0]).toEqual({ type: "link", href: "x.md", children: [{ type: "strong", children: [{ type: "text", text: "Run" }] }] });
    expect(parseInline("`a")).toEqual([{ type: "text", text: "`a" }]);
  });
});

describe("parseMarkdown", () => {
  it("parses headings with unique ids", () => {
    const b = parseMarkdown("# Title\n\n## Verify\n\ntext\n\n## Verify\n");
    expect(b.filter((x) => x.type === "heading").map((x) => (x as { id: string }).id)).toEqual(["title", "verify", "verify-1"]);
  });

  it("joins paragraph lines and splits on blank lines", () => {
    const b = parseMarkdown("one\ntwo\n\nthree");
    expect(b).toHaveLength(2);
    expect(para(b[0])).toBe("one two");
  });

  it("parses fenced code without interpreting it", () => {
    const b = parseMarkdown("```bash\n**x** <b>\n  indented\n```\nafter");
    expect(b[0]).toEqual({ type: "code", lang: "bash", text: "**x** <b>\n  indented" });
    expect(b[1].type).toBe("paragraph");
  });

  it("parses tables with code spans containing pipes", () => {
    const b = parseMarkdown("| A | B |\n|---|:-:|\n| `x \\| y` | **z** |\n| 1 |\n") as Block[];
    const t = b[0];
    if (t.type !== "table") throw new Error("not a table");
    expect(t.header.map(plainText)).toEqual(["A", "B"]);
    expect(t.align).toEqual([null, "center"]);
    expect(t.rows.map((r) => r.map(plainText))).toEqual([["x | y", "z"], ["1", ""]]);
  });

  it("parses nested and ordered lists with continuation lines", () => {
    const b = parseMarkdown("3. first\n   continued\n4. second\n   - nested *a*\n     more\n   - nested b\n\n- other");
    expect(b).toHaveLength(2);
    const ol = b[0];
    if (ol.type !== "list") throw new Error("not a list");
    expect(ol.ordered).toBe(true);
    expect(ol.start).toBe(3);
    expect(para(ol.items[0][0])).toBe("first continued");
    const nested = ol.items[1][1];
    if (nested.type !== "list") throw new Error("no nested list");
    expect(nested.items).toHaveLength(2);
    expect(para(nested.items[0][0])).toBe("nested a more");
    expect(b[1]).toMatchObject({ type: "list", ordered: false });
  });

  it("parses block quotes and rules", () => {
    const b = parseMarkdown("> **Note** one\n> two\n\n---\n");
    expect(b[0].type).toBe("quote");
    expect(b[1].type).toBe("rule");
  });
});
