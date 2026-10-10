// A small Markdown parser for the bundled guides (NFR-DOCS). It covers what the guides use:
// headings, paragraphs, lists (nested, ordered), fenced code, tables, block quotes, rules, and
// inline code, bold, italic and links. It produces a tree that Markdown.tsx renders as React
// elements, so text (including anything that looks like HTML) is never interpreted as markup.

export type Inline =
  | { type: "text"; text: string }
  | { type: "code"; text: string }
  | { type: "strong"; children: Inline[] }
  | { type: "em"; children: Inline[] }
  | { type: "link"; href: string; children: Inline[] };

export type Block =
  | { type: "heading"; level: number; id: string; children: Inline[] }
  | { type: "paragraph"; children: Inline[] }
  | { type: "code"; lang: string; text: string }
  | { type: "list"; ordered: boolean; start: number; items: Block[][] }
  | { type: "table"; header: Inline[][]; align: ("left" | "center" | "right" | null)[]; rows: Inline[][][] }
  | { type: "quote"; children: Block[] }
  | { type: "rule" };

const HEADING = /^(#{1,6})\s+(.*?)\s*#*\s*$/;
const FENCE = /^\s*(```+|~~~+)\s*([\w+-]*)\s*$/;
const RULE = /^\s*([-*_])(\s*\1){2,}\s*$/;
const LIST_ITEM = /^(\s*)([-*+]|\d{1,9}[.)])\s+(.*)$/;
const TABLE_SEP = /^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$/;

/** GitHub-style anchor for a heading: lower case, punctuation dropped, spaces to hyphens. */
export function slugify(text: string): string {
  return text.trim().toLowerCase().replace(/[^\p{L}\p{N}\s_-]/gu, "").replace(/\s/g, "-");
}

/** The plain text of inline content (for anchors and accessible names). */
export function plainText(inlines: Inline[]): string {
  return inlines.map((i) => (i.type === "text" || i.type === "code" ? i.text : plainText(i.children))).join("");
}

/** Parses a whole document. Heading ids are unique within it (repeats get -1, -2, ...). */
export function parseMarkdown(src: string): Block[] {
  const seen = new Map<string, number>();
  const blocks = parseBlocks(src.replace(/\r\n?/g, "\n").split("\n"));
  const assign = (bs: Block[]) => {
    for (const b of bs) {
      if (b.type === "heading") {
        const base = b.id;
        const n = seen.get(base) ?? 0;
        seen.set(base, n + 1);
        b.id = n === 0 ? base : `${base}-${n}`;
      } else if (b.type === "quote") assign(b.children);
      else if (b.type === "list") b.items.forEach(assign);
    }
  };
  assign(blocks);
  return blocks;
}

function indentOf(line: string): number {
  let n = 0;
  for (const ch of line) {
    if (ch === " ") n++;
    else if (ch === "\t") n += 4;
    else break;
  }
  return n;
}

function dedent(line: string, by: number): string {
  let i = 0;
  let n = 0;
  while (i < line.length && n < by && (line[i] === " " || line[i] === "\t")) {
    n += line[i] === "\t" ? 4 : 1;
    i++;
  }
  return line.slice(i);
}

/** True when a line starts a block other than a paragraph (so a paragraph ends before it). */
function startsBlock(line: string, next: string | undefined): boolean {
  return HEADING.test(line) || FENCE.test(line) || RULE.test(line) || LIST_ITEM.test(line)
    || /^\s*>/.test(line) || (line.includes("|") && next !== undefined && TABLE_SEP.test(next) && next.includes("-"));
}

function parseBlocks(lines: string[]): Block[] {
  const out: Block[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (line.trim() === "") { i++; continue; }

    const fence = FENCE.exec(line);
    if (fence) {
      const body: string[] = [];
      const indent = indentOf(line);
      i++;
      while (i < lines.length && !(lines[i].trim().startsWith(fence[1][0].repeat(3)) && lines[i].trim().replace(/[`~]/g, "") === "")) {
        body.push(dedent(lines[i], indent));
        i++;
      }
      i++; // closing fence (or end of input)
      out.push({ type: "code", lang: fence[2], text: body.join("\n") });
      continue;
    }

    const h = HEADING.exec(line);
    if (h) {
      const children = parseInline(h[2]);
      out.push({ type: "heading", level: h[1].length, id: slugify(plainText(children)), children });
      i++;
      continue;
    }

    if (RULE.test(line)) { out.push({ type: "rule" }); i++; continue; }

    if (/^\s*>/.test(line)) {
      const inner: string[] = [];
      while (i < lines.length && /^\s*>/.test(lines[i])) {
        inner.push(lines[i].replace(/^\s*>\s?/, ""));
        i++;
      }
      out.push({ type: "quote", children: parseBlocks(inner) });
      continue;
    }

    if (line.includes("|") && i + 1 < lines.length && TABLE_SEP.test(lines[i + 1]) && lines[i + 1].includes("-")) {
      const header = splitRow(line);
      const align = splitRow(lines[i + 1]).map((c) => {
        const l = c.startsWith(":"), r = c.endsWith(":");
        return l && r ? "center" as const : r ? "right" as const : l ? "left" as const : null;
      });
      i += 2;
      const rows: Inline[][][] = [];
      while (i < lines.length && lines[i].trim() !== "" && lines[i].includes("|")) {
        const cells = splitRow(lines[i]);
        while (cells.length < header.length) cells.push("");
        rows.push(cells.slice(0, header.length).map(parseInline));
        i++;
      }
      out.push({ type: "table", header: header.map(parseInline), align: header.map((_, k) => align[k] ?? null), rows });
      continue;
    }

    const item = LIST_ITEM.exec(line);
    if (item) {
      const baseIndent = indentOf(line);
      const ordered = /\d/.test(item[2]);
      const start = ordered ? parseInt(item[2], 10) : 1;
      const items: Block[][] = [];
      while (i < lines.length) {
        const m = LIST_ITEM.exec(lines[i]);
        if (!m || indentOf(lines[i]) !== baseIndent || /\d/.test(m[2]) !== ordered) break;
        const contentIndent = baseIndent + m[2].length + 1;
        const body = [m[3]];
        i++;
        // Continuation lines and nested lists: anything indented deeper than the marker.
        while (i < lines.length && lines[i].trim() !== "" && indentOf(lines[i]) > baseIndent) {
          body.push(dedent(lines[i], contentIndent));
          i++;
        }
        items.push(parseBlocks(body));
      }
      out.push({ type: "list", ordered, start, items });
      continue;
    }

    const para: string[] = [line.trim()];
    i++;
    while (i < lines.length && lines[i].trim() !== "" && !startsBlock(lines[i], lines[i + 1])) {
      para.push(lines[i].trim());
      i++;
    }
    out.push({ type: "paragraph", children: parseInline(para.join(" ")) });
  }
  return out;
}

/** Splits a table row on pipes that are not escaped and not inside a code span. */
function splitRow(line: string): string[] {
  let s = line.trim();
  if (s.startsWith("|")) s = s.slice(1);
  if (s.endsWith("|") && !s.endsWith("\\|")) s = s.slice(0, -1);
  const cells: string[] = [];
  let cur = "";
  let code = false;
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    if (ch === "\\" && s[i + 1] === "|") { cur += "|"; i++; continue; }
    if (ch === "`") code = !code;
    if (ch === "|" && !code) { cells.push(cur.trim()); cur = ""; continue; }
    cur += ch;
  }
  cells.push(cur.trim());
  return cells;
}

const ESCAPABLE = "\\`*_{}[]()#+-.!|>~";

/** Parses inline Markdown: `code`, **bold**, *italic*, _italic_, [links](href) and \ escapes. */
export function parseInline(src: string): Inline[] {
  const out: Inline[] = [];
  let text = "";
  const flush = () => { if (text) { out.push({ type: "text", text }); text = ""; } };
  let i = 0;
  while (i < src.length) {
    const ch = src[i];
    if (ch === "\\" && i + 1 < src.length && ESCAPABLE.includes(src[i + 1])) {
      text += src[i + 1];
      i += 2;
      continue;
    }
    if (ch === "`") {
      let n = 1;
      while (src[i + n] === "`") n++;
      const fence = "`".repeat(n);
      const end = src.indexOf(fence, i + n);
      if (end > 0) {
        flush();
        let code = src.slice(i + n, end);
        if (code.startsWith(" ") && code.endsWith(" ") && code.trim()) code = code.slice(1, -1);
        out.push({ type: "code", text: code });
        i = end + n;
        continue;
      }
      text += fence;
      i += n;
      continue;
    }
    if (ch === "[") {
      const m = /^\[((?:[^[\]\\]|\\.|\[[^\]]*\])*)\]\(\s*<?([^)\s>]*)>?(?:\s+"[^"]*")?\s*\)/.exec(src.slice(i));
      if (m) {
        flush();
        out.push({ type: "link", href: m[2], children: parseInline(m[1]) });
        i += m[0].length;
        continue;
      }
    }
    if ((ch === "*" || ch === "_") && src[i + 1] === ch) {
      const end = findClose(src, ch + ch, i + 2);
      if (end > 0) {
        flush();
        out.push({ type: "strong", children: parseInline(src.slice(i + 2, end)) });
        i = end + 2;
        continue;
      }
    }
    if (ch === "*" || ch === "_") {
      // Underscores inside words (snake_case) are text, not emphasis.
      const wordBefore = ch === "_" && i > 0 && /[\p{L}\p{N}]/u.test(src[i - 1]);
      const end = wordBefore ? -1 : findClose(src, ch, i + 1);
      if (end > 0 && !(ch === "_" && /[\p{L}\p{N}]/u.test(src[end + 1] ?? ""))) {
        flush();
        out.push({ type: "em", children: parseInline(src.slice(i + 1, end)) });
        i = end + 1;
        continue;
      }
    }
    text += ch;
    i++;
  }
  flush();
  return out;
}

/** Index of the closing delimiter: not right after whitespace, content not empty, skipping code spans. */
function findClose(src: string, delim: string, from: number): number {
  if (from >= src.length || /\s/.test(src[from])) return -1;
  for (let j = from; j < src.length; j++) {
    if (src[j] === "\\") { j++; continue; }
    if (src[j] === "`") {
      const end = src.indexOf("`", j + 1);
      if (end < 0) return -1;
      j = end;
      continue;
    }
    if (src.startsWith(delim, j) && j > from && !/\s/.test(src[j - 1])) {
      // "**" inside a single-"*" span belongs to a nested strong, not the closer.
      if (delim.length === 1 && src[j + 1] === delim) { j++; continue; }
      return j;
    }
  }
  return -1;
}
