import { lazy, Suspense, useCallback, useRef, useState } from "react";
import { HELP_TOPICS, type HelpTopic } from "../panels/help/topics";
import "../panels/help/helpLink.css";

// The drawer and the bundled guides load on first use, in their own chunk (still offline).
const HelpDrawer = lazy(() => import("../panels/help/HelpDrawer"));

/**
 * The "?" button in a panel header (NFR-DOCS): opens the matching section of the bundled guide
 * in the help drawer. `corner` places it at the top right of a panel that has no toolbar row,
 * without taking layout space.
 */
export function HelpLink(props: { topic: HelpTopic; corner?: boolean }) {
  const t = HELP_TOPICS[props.topic];
  const [open, setOpen] = useState(false);
  const button = useRef<HTMLButtonElement | null>(null);
  const close = useCallback(() => {
    setOpen(false);
    button.current?.focus();
  }, []);
  const label = `Help: ${t.label}`;
  const btn = (
    <button ref={button} type="button" className="btn small help-btn" aria-label={label} title={label}
      aria-haspopup="dialog" aria-expanded={open} onClick={() => setOpen((o) => !o)} data-testid={`help-${props.topic}`}>
      ?
    </button>
  );
  return (
    <>
      {props.corner ? <span className="help-corner">{btn}</span> : btn}
      {open && (
        <Suspense fallback={null}>
          <HelpDrawer initial={{ path: t.path, anchor: t.anchor }} onClose={close} />
        </Suspense>
      )}
    </>
  );
}
