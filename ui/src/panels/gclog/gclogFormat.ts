// Formatting and export for the GC report (pure, unit-tested).
import { fmtBytes, fmtMs } from "../monitoring/format";
import type { GcEvent, GcReport, LogInfo } from "./gclogTypes";

/** KiB as a binary size, or "n/a". */
export function fmtK(k: number | null | undefined): string {
  return k === null || k === undefined ? "n/a" : fmtBytes(k * 1024) ?? "n/a";
}

/** A pause or phase time; sub-millisecond values keep their precision (ZGC, Shenandoah). */
export function fmtPause(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return "n/a";
  if (ms > 0 && ms < 1) return (Math.round(ms * 1000) / 1000).toString() + " ms";
  return fmtMs(ms) ?? "n/a";
}

export function fmtDuration(sec: number): string {
  if (sec >= 2 * 86400) return (sec / 86400).toFixed(1) + " d";
  if (sec >= 2 * 3600) return (sec / 3600).toFixed(1) + " h";
  if (sec >= 120) return Math.round(sec / 60) + " min";
  return sec.toFixed(sec < 10 ? 1 : 0) + " s";
}

/** Axis value for an x: epoch ms on a wall-clock log, else seconds of JVM uptime. */
export function axisValue(log: LogInfo, x: number): number {
  return log.timeAxis === "wall" && log.startTs !== undefined ? log.startTs + x * 1000 : x;
}

/** The inverse of {@link axisValue}. */
export function xOf(log: LogInfo, axis: number): number {
  return log.timeAxis === "wall" && log.startTs !== undefined ? (axis - log.startTs) / 1000 : axis;
}

/** When an event happened, for tables and tooltips. */
export function fmtWhen(log: LogInfo, x: number): string {
  if (log.timeAxis === "wall" && log.startTs !== undefined) {
    return new Date(log.startTs + x * 1000).toISOString().replace("T", " ").replace("Z", "");
  }
  return x.toFixed(3) + " s";
}

const CSV_COLUMNS: [string, (e: GcEvent, log: LogInfo) => unknown][] = [
  ["time", (e, log) => fmtWhen(log, e.x)],
  ["uptime_s", (e) => e.uptime],
  ["gc_id", (e) => e.gcId],
  ["kind", (e) => e.kind],
  ["type", (e) => e.type],
  ["category", (e) => e.category],
  ["cause", (e) => e.cause],
  ["duration_ms", (e) => e.durationMs],
  ["heap_before_kb", (e) => e.heapBeforeK],
  ["heap_after_kb", (e) => e.heapAfterK],
  ["heap_total_kb", (e) => e.heapTotalK],
  ["young_before_kb", (e) => e.youngBeforeK],
  ["young_after_kb", (e) => e.youngAfterK],
  ["old_before_kb", (e) => e.oldBeforeK],
  ["old_after_kb", (e) => e.oldAfterK],
  ["old_total_kb", (e) => e.oldTotalK],
  ["metaspace_after_kb", (e) => e.metaAfterK],
  ["flags", (e) => (e.flags ?? []).join("; ")],
];

function csvCell(v: unknown): string {
  if (v === null || v === undefined) return "";
  const s = String(v);
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

/** The events as CSV, one row per event. */
export function eventsCsv(report: GcReport): string {
  const head = CSV_COLUMNS.map(([h]) => h).join(",");
  const rows = report.events.map((e) => CSV_COLUMNS.map(([, f]) => csvCell(f(e, report.log))).join(","));
  return [head, ...rows].join("\n") + "\n";
}

/** A file name for an export: "gc-report-10.0.0.1-gc.log.json". */
export function exportName(report: GcReport, ext: string): string {
  return "gc-report-" + report.name.replace(/[^A-Za-z0-9._-]+/g, "_").replace(/^_+|_+$/g, "") + "." + ext;
}
