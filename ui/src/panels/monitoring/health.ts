// Pure helpers for health, node state, load imbalance and the token ring.
import type { AccessStatus, Level, NodeSnapshot, RingNode } from "../../lib/monitoringTypes";

const LEVEL_RANK: Record<Level, number> = { GREEN: 0, YELLOW: 1, RED: 2 };

export function worstLevel(levels: Level[]): Level {
  return levels.reduce<Level>((w, l) => (LEVEL_RANK[l] > LEVEL_RANK[w] ? l : w), "GREEN");
}

export function levelRank(l: Level): number {
  return LEVEL_RANK[l];
}

/** nodetool-style two-letter state (UN, DN, UJ, ...); accepts UP/DOWN too. "?N": nobody knows up or down. */
export function stateCode(state: string | null | undefined): string {
  const s = (state ?? "").toUpperCase();
  if (s === "UP" || s === "NORMAL") return "UN";
  if (s === "DOWN") return "DN";
  if (/^[UD?][NJLM]$/.test(s)) return s;
  return "?N";
}

export function isUp(state: string | null | undefined): boolean {
  return stateCode(state).startsWith("U");
}

/** Status pill class: an unknown state is not shown as down (only node.unreachable fires for it). */
export function stateClass(state: string | null | undefined): "UP" | "DOWN" | "UNKNOWN" {
  const c = stateCode(state);
  return c.startsWith("U") ? "UP" : c.startsWith("D") ? "DOWN" : "UNKNOWN";
}

export function isDown(state: string | null | undefined): boolean {
  return stateClass(state) === "DOWN";
}

export interface DcCounts { dc: string; total: number; byState: Record<string, number>; unreachable: number }

/** Node counts per DC by state, plus nodes whose JMX read failed. */
export function countsByDc(nodes: NodeSnapshot[]): DcCounts[] {
  const m = new Map<string, DcCounts>();
  for (const n of nodes) {
    const dc = n.datacenter ?? "unknown";
    const c = m.get(dc) ?? { dc, total: 0, byState: {}, unreachable: 0 };
    const s = stateCode(n.state);
    c.total++;
    c.byState[s] = (c.byState[s] ?? 0) + 1;
    if (n.error) c.unreachable++;
    m.set(dc, c);
  }
  return [...m.values()].sort((a, b) => a.dc.localeCompare(b.dc));
}

/**
 * Addresses whose load is more than factor × the average of their DC (load.imbalance rule).
 * Only DCs with 2+ nodes that report a load count.
 */
export function imbalancedNodes(
  nodes: { address: string; datacenter?: string | null; loadBytes: number | null }[],
  factor = 1.5,
): Map<string, number> {
  const byDc = new Map<string, { address: string; load: number }[]>();
  for (const n of nodes) {
    if (n.loadBytes === null || n.loadBytes === undefined) continue;
    const dc = n.datacenter ?? "";
    byDc.set(dc, [...(byDc.get(dc) ?? []), { address: n.address, load: n.loadBytes }]);
  }
  const out = new Map<string, number>();
  for (const members of byDc.values()) {
    if (members.length < 2) continue;
    const avg = members.reduce((s, x) => s + x.load, 0) / members.length;
    if (avg <= 0) continue;
    for (const x of members) if (x.load > factor * avg) out.set(x.address, x.load / avg);
  }
  return out;
}

export interface AccessProblem { failed: number; total: number; method: string; errors: { address: string; error: string }[] }

/** Nodes whose metrics could not be read, from status (preferred) or the snapshot. */
export function accessProblem(status: AccessStatus | null, nodes: NodeSnapshot[] | null): AccessProblem | null {
  const list = status?.nodes.length
    ? status.nodes.map((n) => ({ address: n.address, error: n.ok ? null : n.error ?? "unreachable" }))
    : (nodes ?? []).map((n) => ({ address: n.address, error: n.error }));
  const errors = list.filter((n): n is { address: string; error: string } => !!n.error);
  if (!errors.length) return null;
  return { failed: errors.length, total: list.length, method: methodLabel(status?.method), errors };
}

export function methodLabel(method: string | null | undefined): string {
  switch ((method ?? "").toUpperCase()) {
    case "SSH_TUNNEL": return "ssh tunnel";
    case "DIRECT": return "direct JMX";
    case "EXPORTER": return "the metrics exporter";
    case "SIDECAR": return "the sidecar";
    case "": return "JMX";
    default: return method!.toLowerCase().replace(/_/g, " ");
  }
}

// ---- ring --------------------------------------------------------------------

const TWO63 = 2n ** 63n;
const TWO64 = 2 ** 64;
const TWO127 = 2 ** 127;

/** Position of a token on the ring as a fraction in [0, 1). */
export function tokenFraction(token: string, partitioner: string | null | undefined): number | null {
  let t: bigint;
  try {
    t = BigInt(token);
  } catch {
    return null; // ByteOrdered or other non-numeric tokens
  }
  if (partitioner && /RandomPartitioner/.test(partitioner) && !/Murmur3/.test(partitioner)) return Number(t) / TWO127;
  return Number(t + TWO63) / TWO64;
}

export interface TokenRange { address: string; token: string; start: number; end: number; size: number }

/**
 * Token ranges of one DC ring in ring order. Range i is (token i-1, token i] and belongs to
 * the owner of token i; the first range wraps around. Non-numeric tokens are spread evenly.
 */
export function tokenRanges(nodes: RingNode[], partitioner: string | null | undefined): TokenRange[] {
  const all = nodes.flatMap((n) => n.tokens.map((t) => ({ address: n.address, token: t, pos: tokenFraction(t, partitioner) })));
  if (!all.length) return [];
  if (all.some((x) => x.pos === null)) {
    all.sort((a, b) => a.token.localeCompare(b.token));
    all.forEach((x, i) => (x.pos = i / all.length));
  } else {
    all.sort((a, b) => a.pos! - b.pos!);
  }
  return all.map((x, i) => {
    const prev = i === 0 ? all[all.length - 1].pos! - 1 : all[i - 1].pos!;
    const size = all.length === 1 ? 1 : x.pos! - prev;
    return { address: x.address, token: x.token, start: prev < 0 ? prev + 1 : prev, end: x.pos!, size };
  });
}
