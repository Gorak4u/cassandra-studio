import type { ReactNode } from "react";
import type { Block, Inline } from "./markdownParse";

/** Where a link goes, as decided by the caller (see docs.ts resolveLink). */
export type LinkTarget =
  | { kind: "doc"; path: string; anchor: string | null }
  | { kind: "external"; url: string }
  | { kind: "none" };

export interface MarkdownProps {
  blocks: Block[];
  /** Prefix for heading ids, so they stay unique in the page. */
  idPrefix: string;
  /** Added to heading levels: a document's h1 renders as h(1 + offset), capped at h6. */
  headingOffset: number;
  resolve: (href: string) => LinkTarget;
  onNavigate: (path: string, anchor: string | null) => void;
}

/**
 * Renders parsed Markdown as React elements. Only elements built here are produced, and every
 * piece of text goes through React's escaping: no raw HTML from the documents ever reaches the DOM.
 */
export function Markdown(props: MarkdownProps) {
  return <>{props.blocks.map((b, i) => renderBlock(b, i, props))}</>;
}

function renderBlock(b: Block, key: number, p: MarkdownProps): ReactNode {
  switch (b.type) {
    case "heading": {
      const level = Math.min(6, b.level + p.headingOffset);
      const Tag = `h${level}` as "h1";
      return <Tag key={key} id={p.idPrefix + b.id} tabIndex={-1}>{renderInlines(b.children, p)}</Tag>;
    }
    case "paragraph":
      return <p key={key}>{renderInlines(b.children, p)}</p>;
    case "code":
      return <pre key={key} className="help-code" tabIndex={0} aria-label={b.lang ? `${b.lang} code` : "Code"}><code>{b.text}</code></pre>;
    case "rule":
      return <hr key={key} />;
    case "quote":
      return <blockquote key={key}>{b.children.map((c, i) => renderBlock(c, i, p))}</blockquote>;
    case "list": {
      const items = b.items.map((item, i) => (
        <li key={i}>
          {item.length === 1 && item[0].type === "paragraph"
            ? renderInlines(item[0].children, p) // tight item: no <p> around the text
            : item.map((c, j) => renderBlock(c, j, p))}
        </li>
      ));
      return b.ordered
        ? <ol key={key} start={b.start === 1 ? undefined : b.start}>{items}</ol>
        : <ul key={key}>{items}</ul>;
    }
    case "table":
      return (
        <div key={key} className="help-table-wrap" tabIndex={0} role="region" aria-label="Table">
          <table className="data help-table">
            <thead>
              <tr>{b.header.map((h, i) => <th key={i} scope="col" style={b.align[i] ? { textAlign: b.align[i]! } : undefined}>{renderInlines(h, p)}</th>)}</tr>
            </thead>
            <tbody>
              {b.rows.map((r, i) => (
                <tr key={i}>{r.map((c, j) => <td key={j} style={b.align[j] ? { textAlign: b.align[j]! } : undefined}>{renderInlines(c, p)}</td>)}</tr>
              ))}
            </tbody>
          </table>
        </div>
      );
  }
}

function renderInlines(inlines: Inline[], p: MarkdownProps): ReactNode[] {
  return inlines.map((n, i) => {
    switch (n.type) {
      case "text": return n.text;
      case "code": return <code key={i}>{n.text}</code>;
      case "strong": return <strong key={i}>{renderInlines(n.children, p)}</strong>;
      case "em": return <em key={i}>{renderInlines(n.children, p)}</em>;
      case "link": {
        const t = p.resolve(n.href);
        const children = renderInlines(n.children, p);
        if (t.kind === "doc") {
          return <button key={i} type="button" className="help-link" onClick={() => p.onNavigate(t.path, t.anchor)}>{children}</button>;
        }
        if (t.kind === "external") {
          return <a key={i} href={t.url} target="_blank" rel="noopener noreferrer">{children}</a>;
        }
        return <span key={i} className="help-deadlink">{children}</span>;
      }
    }
  });
}
