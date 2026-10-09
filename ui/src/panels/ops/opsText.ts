// "Copy as text": a view as nodetool-like plain text (aligned columns, key: value sections).
import type { OpsView, Section } from "./opsApi";

/** True when a cell looks numeric (right-aligned, like nodetool's numbers). */
export function isNumeric(v: string | null): boolean {
  return v !== null && /^-?\d[\d,]*(\.\d+)?(%| ?(bytes|KiB|MiB|GiB|TiB))?$/.test(v.trim());
}

export function sectionText(s: Section): string {
  const cell = (v: string | null) => (v === null ? "n/a" : v);
  if (s.keyValue) {
    const w = Math.max(0, ...s.rows.map((r) => cell(r[0]).length));
    return [s.title, ...s.rows.map((r) => `${cell(r[0]).padEnd(w)} : ${cell(r[1])}`)].join("\n");
  }
  const widths = s.columns.map((c, i) => Math.max(c.length, ...s.rows.map((r) => cell(r[i] ?? null).length)));
  const line = (vals: (string | null)[], header: boolean) =>
    vals.map((v, i) => {
      const t = header ? (v ?? "") : cell(v);
      return !header && isNumeric(v) ? t.padStart(widths[i]) : t.padEnd(widths[i]);
    }).join("  ").trimEnd();
  const out = [s.title, line(s.columns, true), ...s.rows.map((r) => line(r, false))];
  if (s.rows.length === 0) out.push("(none)");
  return out.join("\n");
}

export function viewText(v: OpsView): string {
  const parts = [`$ ${v.command}`, ...v.sections.map(sectionText)];
  if (v.notes.length) parts.push(v.notes.map((n) => "Note: " + n).join("\n"));
  return parts.join("\n\n") + "\n";
}
