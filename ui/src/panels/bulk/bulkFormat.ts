// Pure helpers of the bulk panel (tested in __tests__/bulkFormat.test.ts).
import type { BulkStats, Mapping, TableColumn } from "./bulkApi";

export function fmtInt(n: number | null | undefined): string {
  return n === null || n === undefined ? "–" : Math.round(n).toLocaleString("en-US");
}

export function fmtBytes(b: number | null | undefined): string {
  if (b === null || b === undefined) return "–";
  if (b < 1024) return `${b} B`;
  const units = ["KiB", "MiB", "GiB", "TiB"];
  let v = b / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(1)} ${units[i]}`;
}

export function fmtDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return "–";
  if (ms < 1000) return `${ms} ms`;
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(1)} s`;
  const m = Math.floor(s / 60);
  return `${m} min ${Math.round(s - m * 60)} s`;
}

/** One-line summary of a bulk job's counters. */
export function statsLine(s: BulkStats): string {
  const parts: string[] = [];
  if (s.kind === "unload") {
    if (s.rangesTotal) parts.push(`${fmtInt(s.rangesDone)}/${fmtInt(s.rangesTotal)} ranges`);
    parts.push(`${fmtInt(s.rowsWritten)} rows`, fmtBytes(s.bytes));
    if (s.rangesFailed) parts.push(`${fmtInt(s.rangesFailed)} failed ranges`);
  } else {
    parts.push(`${fmtInt(s.rowsRead)} read`, `${fmtInt(s.rowsWritten)} ${s.dryRun ? "valid" : "written"}`, `${fmtInt(s.rejected)} rejected`);
    if (s.estimatedRows) parts.push(`of ~${fmtInt(s.estimatedRows)}`);
  }
  parts.push(`${fmtInt(s.rowsPerSecond)} rows/s`, fmtDuration(s.elapsedMs));
  return parts.join(" · ");
}

/** The default unload file: <downloads>/<ks>.<table>.<csv|jsonl>[.gz]. */
export function defaultUnloadPath(dir: string, sep: string, name: string, format: "csv" | "json", gzip: boolean): string {
  const base = dir.endsWith(sep) ? dir : dir + sep;
  return `${base}${name || "export"}.${format === "json" ? "jsonl" : "csv"}${gzip ? ".gz" : ""}`;
}

/** A path with its extension switched to match format and compression (keeps the user's stem). */
export function withExtension(path: string, format: "csv" | "json", gzip: boolean): string {
  const m = /^(.*?)(\.(csv|jsonl|json|ndjson))?(\.gz)?$/i.exec(path);
  const stem = m ? m[1] : path;
  return `${stem}.${format === "json" ? "jsonl" : "csv"}${gzip ? ".gz" : ""}`;
}

/** Mapping as a column -> source record, from the engine's suggestion. */
export function mappingRecord(cols: TableColumn[], suggested: Mapping[] | undefined): Record<string, string> {
  const r: Record<string, string> = {};
  for (const c of cols) r[c.name] = "";
  for (const m of suggested ?? []) if (m.column in r) r[m.column] = m.source;
  return r;
}

/** Problems that make the mapping unusable: unmapped key columns, nothing mapped. */
export function mappingProblems(cols: TableColumn[], mapping: Record<string, string>): string[] {
  const out: string[] = [];
  const keys = cols.filter((c) => c.kind !== "regular" && !mapping[c.name]).map((c) => c.name);
  if (keys.length) out.push(`Primary key column${keys.length > 1 ? "s" : ""} ${keys.join(", ")} must be mapped.`);
  if (!Object.values(mapping).some(Boolean)) out.push("Map at least one file column.");
  return out;
}

export function mappingList(mapping: Record<string, string>): Mapping[] {
  return Object.entries(mapping).filter(([, s]) => s).map(([column, source]) => ({ column, source }));
}
