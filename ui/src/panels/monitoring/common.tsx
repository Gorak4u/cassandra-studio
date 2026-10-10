// Small building blocks shared by the monitoring views.
import { useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import type { Level } from "../../lib/monitoringTypes";
import { errorText } from "../../components/feedback";

/** A formatted value, or a muted "n/a" when the metric is not available. */
export function Val(props: { v: string | null | undefined; title?: string }) {
  return props.v === null || props.v === undefined
    ? <span className="mon-na" title="Not available from this node or version">n/a</span>
    : <span title={props.title}>{props.v}</span>;
}

const LEVEL_TEXT: Record<Level, { icon: string; text: string }> = {
  GREEN: { icon: "✔", text: "Healthy" },
  YELLOW: { icon: "▲", text: "Warning" },
  RED: { icon: "✖", text: "Critical" },
};

/** Level as icon + word + colour (never colour alone). */
export function LevelTag(props: { level: Level; big?: boolean; testId?: string }) {
  const t = LEVEL_TEXT[props.level];
  return (
    <span className={`mon-level ${props.level}${props.big ? " big" : ""}`} data-testid={props.testId}>
      <span aria-hidden="true">{t.icon}</span> {props.level}
      {props.big && <span className="mon-level-text"> · {t.text}</span>}
    </span>
  );
}

/** Used / total as a bar with the numbers in text; amber from warnPct, red from critPct. */
export function UsageBar(props: { used: number | null; total: number | null; fmt: (v: number | null) => string | null; warnPct?: number; critPct?: number; label: string }) {
  const { used, total } = props;
  if (used === null || total === null || !total) return <Val v={props.fmt(used)} />;
  const pct = Math.max(0, Math.min(100, (100 * used) / total));
  const cls = pct >= (props.critPct ?? 95) ? "crit" : pct >= (props.warnPct ?? 85) ? "warn" : "";
  return (
    <span className="mon-usage" title={`${props.label}: ${pct.toFixed(1)} %`}>
      <span className="mon-bar" role="meter" aria-label={props.label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(pct)}>
        <span className={"mon-bar-fill " + cls} style={{ width: pct + "%" }} />
      </span>
      <span className="mon-usage-text">{props.fmt(used)} / {props.fmt(total)}</span>
    </span>
  );
}

export function Loading(props: { what: string }) {
  return <div className="empty" role="status">Loading {props.what}…</div>;
}

export function Empty(props: { children: ReactNode }) {
  return <div className="empty">{props.children}</div>;
}

export function ErrorState(props: { error: unknown; onRetry?: () => void }) {
  return (
    <div className="pad">
      <div className="notice error" role="alert">{errorText(props.error)}</div>
      {props.onRetry && <button className="btn small" onClick={props.onRetry}>Retry</button>}
    </div>
  );
}

// ---- sortable table ------------------------------------------------------------

export type SortValue = number | string | null | undefined;

export interface Col<T> {
  key: string;
  label: string;
  title?: string;
  num?: boolean;
  sort?: (r: T) => SortValue;
  render: (r: T) => ReactNode;
}

/** Sorts a copy; missing values always go last. */
export function sortRows<T>(rows: T[], get: (r: T) => SortValue, dir: "asc" | "desc"): T[] {
  const m = dir === "asc" ? 1 : -1;
  return [...rows].sort((a, b) => {
    const x = get(a), y = get(b);
    const xm = x === null || x === undefined || (typeof x === "number" && Number.isNaN(x));
    const ym = y === null || y === undefined || (typeof y === "number" && Number.isNaN(y));
    if (xm || ym) return xm && ym ? 0 : xm ? 1 : -1;
    if (typeof x === "number" && typeof y === "number") return (x - y) * m;
    return String(x).localeCompare(String(y), undefined, { numeric: true }) * m;
  });
}

/** From this many rows on, SortTable renders only the rows in view. */
export const VIRTUAL_FROM = 150;
const ROW_HEIGHT_GUESS = 33;
const OVERSCAN = 15;

export function SortTable<T>(props: {
  rows: T[];
  cols: Col<T>[];
  rowKey: (r: T) => string;
  label: string;
  testId?: string;
  initial?: { key: string; dir: "asc" | "desc" };
  onRowClick?: (r: T) => void;
  rowClass?: (r: T) => string | undefined;
}) {
  const [sort, setSort] = useState(props.initial ?? null);
  const col = props.cols.find((c) => c.key === sort?.key);
  const rows = useMemo(
    () => (col?.sort && sort ? sortRows(props.rows, col.sort, sort.dir) : props.rows),
    // the columns are rebuilt on every render of the parent; the sort key and direction are what matter
    [props.rows, sort?.key, sort?.dir], // eslint-disable-line react-hooks/exhaustive-deps
  );
  const toggle = (key: string) =>
    setSort((s) => (s?.key === key ? { key, dir: s.dir === "asc" ? "desc" : "asc" } : { key, dir: "asc" }));
  const { onRowClick } = props;
  // Long tables (hundreds of nodes) render only the rows in view, in their own scroll area (NFR-SCALE).
  const virtual = rows.length > VIRTUAL_FROM;
  const wrap = useRef<HTMLDivElement | null>(null);
  const rowHeight = useRef(ROW_HEIGHT_GUESS);
  const [scroll, setScroll] = useState({ top: 0, height: 800 });
  useLayoutEffect(() => {
    if (!virtual || !wrap.current) return;
    const tr = wrap.current.querySelector<HTMLTableRowElement>("tbody tr[aria-rowindex]");
    if (tr && tr.offsetHeight > 0) rowHeight.current = tr.offsetHeight;
  });
  const h = rowHeight.current;
  const start = virtual ? Math.max(0, Math.floor(scroll.top / h) - OVERSCAN) : 0;
  const end = virtual ? Math.min(rows.length, Math.ceil((scroll.top + scroll.height) / h) + OVERSCAN) : rows.length;
  const onScroll = virtual
    ? (e: React.UIEvent<HTMLDivElement>) => setScroll({ top: e.currentTarget.scrollTop, height: e.currentTarget.clientHeight })
    : undefined;
  const spacer = (height: number, key: string) => height > 0 && (
    <tr key={key} aria-hidden="true"><td colSpan={props.cols.length} style={{ height, padding: 0, border: 0 }} /></tr>
  );
  return (
    <div className={"mon-table-wrap" + (virtual ? " virtual" : "")} ref={wrap} onScroll={onScroll}
      style={virtual ? { maxHeight: "70vh", overflowY: "auto" } : undefined}>
      <table className="data mon-table" aria-label={props.label} data-testid={props.testId} aria-rowcount={rows.length + 1}>
        <thead>
          <tr>
            {props.cols.map((c) => (
              <th
                key={c.key}
                className={c.num ? "num" : undefined}
                style={virtual ? { position: "sticky", top: 0, zIndex: 1, background: "var(--panel)" } : undefined}
                title={c.title}
                aria-sort={sort?.key === c.key ? (sort.dir === "asc" ? "ascending" : "descending") : undefined}
              >
                {c.sort ? (
                  <button className="mon-sort" onClick={() => toggle(c.key)}>
                    {c.label}
                    <span aria-hidden="true" className="mon-sort-mark">{sort?.key === c.key ? (sort.dir === "asc" ? "▲" : "▼") : ""}</span>
                  </button>
                ) : c.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {spacer(start * h, "top")}
          {rows.slice(start, end).map((r, i) => (
            <tr
              key={props.rowKey(r)}
              aria-rowindex={start + i + 2}
              className={[props.rowClass?.(r), onRowClick ? "mon-clickable" : ""].filter(Boolean).join(" ") || undefined}
              onClick={onRowClick ? () => onRowClick(r) : undefined}
            >
              {props.cols.map((c) => <td key={c.key} className={c.num ? "num" : undefined}>{c.render(r)}</td>)}
            </tr>
          ))}
          {spacer((rows.length - end) * h, "bottom")}
        </tbody>
      </table>
    </div>
  );
}
