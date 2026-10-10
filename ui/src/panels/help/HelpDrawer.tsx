import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { helpDocs, resolveLink } from "./docs";
import { Markdown } from "./Markdown";
import "./help.css";

interface Place { path: string; anchor: string | null }

/**
 * The in-app guide (NFR-DOCS): a side drawer that shows one bundled document, scrolled to a
 * section. Links between guides open in the drawer; web links open in the system browser.
 */
export default function HelpDrawer(props: { initial: Place; onClose: () => void }) {
  const docs = helpDocs();
  const uid = useId().replace(/:/g, "");
  const idPrefix = `help-${uid}-`;
  const [place, setPlace] = useState<Place>(props.initial);
  const [back, setBack] = useState<Place[]>([]);
  const content = useRef<HTMLDivElement | null>(null);
  const doc = docs.get(place.path) ?? docs.get("guide/user-guide.md");
  const { onClose } = props;

  const go = (path: string, anchor: string | null) => {
    setBack((b) => [...b, place]);
    setPlace({ path, anchor });
  };

  // Scroll to the section and move focus to its heading, so screen readers start there.
  useLayoutEffect(() => {
    const root = content.current;
    if (!root) return;
    const target = (place.anchor && root.ownerDocument.getElementById(idPrefix + place.anchor))
      || root.querySelector<HTMLElement>("h1, h2, h3, h4, h5, h6");
    if (!place.anchor) root.scrollTop = 0;
    if (target) {
      target.scrollIntoView?.({ block: "start" });
      target.focus({ preventScroll: true });
    }
  }, [place, idPrefix]);

  useEffect(() => {
    const onKey = (e: globalThis.KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  if (!doc) return null;
  const titleId = `${idPrefix}title`;
  return createPortal(
    <aside className="help-drawer" role="dialog" aria-modal="false" aria-labelledby={titleId} data-testid="help-drawer">
      <header className="help-head">
        <h2 id={titleId}>Help</h2>
        <label className="help-pick">
          <span className="help-sr">Guide</span>
          <select value={doc.path} onChange={(e) => go(e.target.value, null)} aria-label="Guide">
            {[...docs.values()].map((d) => <option key={d.path} value={d.path}>{d.title}</option>)}
          </select>
        </label>
        <button type="button" className="btn small" disabled={!back.length} aria-label="Back"
          onClick={() => { const prev = back[back.length - 1]; setBack(back.slice(0, -1)); if (prev) setPlace(prev); }}>←</button>
        <button type="button" className="btn small" onClick={onClose} aria-label="Close help">✕</button>
      </header>
      <div ref={content} className="help-content" role="region" aria-label={doc.title} tabIndex={0} data-testid="help-content">
        <Markdown
          blocks={doc.blocks}
          idPrefix={idPrefix}
          headingOffset={2}
          resolve={(href) => resolveLink(doc.path, href, docs)}
          onNavigate={(path, anchor) => (path === place.path ? setPlace({ path, anchor }) : go(path, anchor))}
        />
      </div>
      <footer className="help-foot muted">Built into Cassandra Studio; works offline.</footer>
    </aside>,
    document.body,
  );
}
