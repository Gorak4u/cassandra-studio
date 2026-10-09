// Unit formatting for monitoring values. Every formatter returns null for a missing
// value so the caller can show "n/a" (never 0).

type N = number | null | undefined;

const missing = (v: N): v is null | undefined => v === null || v === undefined || Number.isNaN(v);

function trim(x: number, digits: number): string {
  return x.toFixed(digits).replace(/\.0+$|(\.\d*[1-9])0+$/, "$1");
}

export function fmtBytes(v: N): string | null {
  if (missing(v)) return null;
  const units = ["B", "KiB", "MiB", "GiB", "TiB", "PiB"];
  let x = Math.abs(v);
  let i = 0;
  while (x >= 1024 && i < units.length - 1) { x /= 1024; i++; }
  return (v < 0 ? "-" : "") + trim(x, x >= 100 || i === 0 ? 0 : 1) + " " + units[i];
}

/** Microseconds as ms (or µs below 1 ms). */
export function fmtMicros(v: N): string | null {
  if (missing(v)) return null;
  if (Math.abs(v) < 1000) return trim(v, 0) + " µs";
  const ms = v / 1000;
  return trim(ms, ms >= 100 ? 0 : ms >= 10 ? 1 : 2) + " ms";
}

export function fmtMs(v: N): string | null {
  if (missing(v)) return null;
  if (Math.abs(v) >= 10_000) return trim(v / 1000, 1) + " s";
  return trim(v, Math.abs(v) >= 100 ? 0 : 1) + " ms";
}

/** A percentage already in 0-100. */
export function fmtPct(v: N, digits = 1): string | null {
  if (missing(v)) return null;
  return trim(v, digits) + " %";
}

/** A ratio in 0-1 shown as a percentage. */
export function fmtRatio(v: N, digits = 1): string | null {
  return missing(v) ? null : fmtPct(v * 100, digits);
}

export function fmtCount(v: N): string | null {
  if (missing(v)) return null;
  const a = Math.abs(v);
  if (a >= 1e9) return trim(v / 1e9, 1) + "G";
  if (a >= 1e6) return trim(v / 1e6, 1) + "M";
  if (a >= 1e4) return trim(v / 1e3, 1) + "k";
  return trim(v, a < 10 && !Number.isInteger(v) ? 2 : 0);
}

export function fmtRate(v: N): string | null {
  const c = fmtCount(v);
  return c === null ? null : c + "/s";
}

/** Uptime and other durations, e.g. "12d 3h", "4h 12m", "35s". */
export function fmtDuration(sec: N): string | null {
  if (missing(sec)) return null;
  const s = Math.max(0, Math.round(sec));
  const d = Math.floor(s / 86_400), h = Math.floor((s % 86_400) / 3600), m = Math.floor((s % 3600) / 60);
  if (d) return `${d}d ${h}h`;
  if (h) return `${h}h ${m}m`;
  if (m) return `${m}m ${s % 60}s`;
  return `${s}s`;
}

/** "3 min ago" style relative time. */
export function fmtSince(epochMs: N, now = Date.now()): string | null {
  if (missing(epochMs)) return null;
  const s = Math.max(0, Math.round((now - epochMs) / 1000));
  if (s < 45) return "just now";
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86_400) return `${Math.round(s / 3600)} h ago`;
  return `${Math.round(s / 86_400)} d ago`;
}

export function fmtNumber(v: N, digits = 2): string | null {
  return missing(v) ? null : trim(v, digits);
}

export type Unit = "bytes" | "micros" | "ms" | "pct" | "perSec" | "count";

export function formatterFor(unit: Unit): (v: N) => string | null {
  switch (unit) {
    case "bytes": return fmtBytes;
    case "micros": return fmtMicros;
    case "ms": return fmtMs;
    case "pct": return (v) => fmtPct(v);
    case "perSec": return fmtRate;
    case "count": return fmtCount;
  }
}

export function sum(values: (number | null | undefined)[]): number | null {
  let s = 0, any = false;
  for (const v of values) if (!missing(v)) { s += v; any = true; }
  return any ? s : null;
}
