import { createContext, useCallback, useContext, useRef, useState, type ReactNode } from "react";
import { ApiError } from "../lib/api";
import type { ConfirmDetails } from "../lib/types";
import { Modal } from "./Modal";

// ---- toasts ---------------------------------------------------------------

type Toast = { id: number; kind: "ok" | "error" | "info"; text: string };
type ToastApi = { ok: (t: string) => void; error: (e: unknown) => void; info: (t: string) => void };
const ToastCtx = createContext<ToastApi | null>(null);

export function errorText(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return String(e);
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const next = useRef(1);
  const push = useCallback((kind: Toast["kind"], text: string) => {
    const id = next.current++;
    setToasts((t) => [...t, { id, kind, text }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), kind === "error" ? 9000 : 4000);
  }, []);
  const api = useRef<ToastApi>({
    ok: (t) => push("ok", t),
    info: (t) => push("info", t),
    error: (e) => push("error", errorText(e)),
  });
  return (
    <ToastCtx.Provider value={api.current}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={"toast " + t.kind}>
            <div style={{ flex: 1 }}>{t.text}</div>
            <button className="btn link" aria-label="Dismiss" onClick={() => setToasts((x) => x.filter((y) => y.id !== t.id))}>
              ×
            </button>
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  );
}

export function useToast(): ToastApi {
  const t = useContext(ToastCtx);
  if (!t) throw new Error("ToastProvider missing");
  return t;
}

// ---- guarded actions (NFR-SAFE) ---------------------------------------------

export type Confirmation = { confirmed?: boolean; confirmName?: string | null };

type Pending = { details: ConfirmDetails; resolve: (c: Confirmation | null) => void };
const ConfirmCtx = createContext<((d: ConfirmDetails) => Promise<Confirmation | null>) | null>(null);

/**
 * Runs an action that the engine may refuse with 428 "confirmation required".
 * On 428 it shows exactly what will run (the engine's preview), and for PROD
 * asks for the connection name; then retries with that confirmation. The
 * engine checks again, so the UI can never skip the guard.
 */
export function useGuarded() {
  const ask = useContext(ConfirmCtx);
  if (!ask) throw new Error("ConfirmProvider missing");
  return useCallback(
    async <T,>(run: (c: Confirmation) => Promise<T>): Promise<T | null> => {
      try {
        return await run({});
      } catch (e) {
        if (e instanceof ApiError && e.confirmation) {
          const c = await ask(e.confirmation);
          if (!c) return null;
          return await run(c);
        }
        throw e;
      }
    },
    [ask],
  );
}

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<Pending | null>(null);
  const [typed, setTyped] = useState("");
  const ask = useCallback(
    (details: ConfirmDetails) =>
      new Promise<Confirmation | null>((resolve) => {
        setTyped("");
        setPending({ details, resolve });
      }),
    [],
  );
  const close = (c: Confirmation | null) => {
    pending?.resolve(c);
    setPending(null);
  };
  const d = pending?.details;
  const nameOk = !d?.requireTypedName || typed.trim() === d.connectionName.trim();
  return (
    <ConfirmCtx.Provider value={ask}>
      {children}
      {d && (
        <Modal
          danger={d.environment === "PROD" || d.destructive}
          title={
            <>
              <span className={"env " + d.environment}>{d.environment}</span> Confirm on {d.connectionName}
            </>
          }
          onClose={() => close(null)}
          footer={
            <>
              {d.requireTypedName && (
                <input
                  autoFocus
                  aria-label="Type the connection name to confirm"
                  placeholder={`Type "${d.connectionName}" to confirm`}
                  value={typed}
                  onChange={(e) => setTyped(e.target.value)}
                  style={{ flex: 1 }}
                />
              )}
              <button className="btn" onClick={() => close(null)}>
                Cancel
              </button>
              <button
                className={"btn " + (d.destructive || d.environment === "PROD" ? "danger" : "primary")}
                disabled={!nameOk}
                autoFocus={!d.requireTypedName}
                onClick={() => close(d.requireTypedName ? { confirmName: typed.trim() } : { confirmed: true })}
              >
                Run
              </button>
            </>
          }
        >
          <p style={{ marginTop: 0 }}>
            You are about to <b>{d.summary}</b>. This is exactly what will run:
          </p>
          <pre className="ddl">{d.preview.join(";\n\n") + ";"}</pre>
          {d.warnings.map((w, i) => (
            <div key={i} className="notice warn">
              ⚠ {w}
            </div>
          ))}
          {d.requireTypedName && (
            <p className="error-text">This is a PRODUCTION cluster. Type its name to confirm.</p>
          )}
        </Modal>
      )}
    </ConfirmCtx.Provider>
  );
}
