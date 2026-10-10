// Per-node colours: the one place with literal colours. Eight categorical hues in a fixed
// order, stepped separately for each theme and checked for colour-vision-deficiency
// separation and contrast against --panel (#ffffff light, #171b21 dark). Three light
// steps sit below 3:1 on white, so node identity is always also given as text
// (legend chips, tooltips, the nodes table).
const LIGHT = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#4a3aa7", "#e34948"];
const DARK = ["#3987e5", "#d95926", "#199e70", "#c98500", "#d55181", "#008300", "#9085e9", "#e66767"];

export const PALETTE_SIZE = LIGHT.length;

/** One colour per name (datacenters in large-cluster charts), in sorted order. */
export function categoricalColors(names: Iterable<string>, dark: boolean): Map<string, string> {
  const p = dark ? DARK : LIGHT;
  return new Map([...new Set(names)].sort().map((n, i) => [n, p[i % p.length]]));
}

export interface NodeStyle { color: string; /** 0 for the first 8 nodes; past that lines are dotted. */ cycle: number }

/**
 * Colour per node address. Colour follows the node (sorted address order over the whole
 * cluster), so filtering nodes never repaints the others.
 */
export function nodeStyles(addresses: Iterable<string>, dark: boolean): Map<string, NodeStyle> {
  const p = dark ? DARK : LIGHT;
  const sorted = [...new Set(addresses)].sort(compareAddresses);
  return new Map(sorted.map((a, i) => [a, { color: p[i % p.length], cycle: Math.floor(i / p.length) }]));
}

/** IPv4 addresses sort numerically, others lexically. */
export function compareAddresses(a: string, b: string): number {
  const pa = a.split("."), pb = b.split(".");
  if (pa.length === 4 && pb.length === 4 && [...pa, ...pb].every((x) => /^\d+$/.test(x))) {
    for (let i = 0; i < 4; i++) if (+pa[i] !== +pb[i]) return +pa[i] - +pb[i];
    return 0;
  }
  return a.localeCompare(b);
}

export interface ChartTheme { text: string; muted: string; border: string; panel: string; panel2: string; danger: string }

/** Chart chrome from the app's CSS variables (read after the theme attribute is applied). */
export function readChartTheme(): ChartTheme {
  const cs = getComputedStyle(document.documentElement);
  const v = (name: string, fallback: string) => cs.getPropertyValue(name).trim() || fallback;
  return {
    text: v("--text", "#1b1f24"), muted: v("--muted", "#5f6b7a"), border: v("--border", "#d9dde3"),
    panel: v("--panel", "#ffffff"), panel2: v("--panel-2", "#f0f2f5"), danger: v("--danger", "#c62828"),
  };
}
