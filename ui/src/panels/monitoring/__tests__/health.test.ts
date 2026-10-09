import { describe, expect, it } from "vitest";
import { accessProblem, countsByDc, imbalancedNodes, isDown, isUp, stateClass, stateCode, tokenFraction, tokenRanges, worstLevel } from "../health";
import { mockSnapshot } from "../../../lib/monitoringMock";
import type { RingNode } from "../../../lib/monitoringTypes";

const ringNode = (address: string, tokens: string[], state = "UN"): RingNode => ({
  hostId: null, address, rack: null, state, loadBytes: 1, tokens, ownershipPct: null, effectiveOwnershipPct: null,
});

describe("health helpers", () => {
  it("picks the worst level", () => {
    expect(worstLevel([])).toBe("GREEN");
    expect(worstLevel(["GREEN", "YELLOW"])).toBe("YELLOW");
    expect(worstLevel(["YELLOW", "RED", "GREEN"])).toBe("RED");
  });

  it("normalises node states", () => {
    expect(stateCode("UN")).toBe("UN");
    expect(stateCode("UP")).toBe("UN");
    expect(stateCode("DOWN")).toBe("DN");
    expect(stateCode("uj")).toBe("UJ");
    expect(stateCode("weird")).toBe("?N");
    expect(stateCode("?N")).toBe("?N");
    // Unknown is neither up nor down: amber pill, no hatched ring arc, only node.unreachable fires.
    expect(stateClass("?N")).toBe("UNKNOWN");
    expect(stateClass("DN")).toBe("DOWN");
    expect(stateClass("UJ")).toBe("UP");
    expect(isDown("?N")).toBe(false);
    expect(isDown("DN")).toBe(true);
    expect(isUp("UL")).toBe(true);
    expect(isUp("DN")).toBe(false);
    expect(isUp(null)).toBe(false);
  });

  it("counts nodes per DC and state", () => {
    const s = mockSnapshot(Date.UTC(2026, 9, 9, 12));
    const c = countsByDc([...s.nodes, { ...s.nodes[0], address: "10.0.1.99", state: "DN", error: null }]);
    expect(c.map((x) => x.dc)).toEqual(["dc-east", "dc-west"]);
    expect(c[0]).toMatchObject({ total: 4, byState: { UN: 3, DN: 1 }, unreachable: 0 });
    expect(c[1]).toMatchObject({ total: 3, unreachable: 1 });
  });

  it("flags load above 1.5 × the DC average, per DC with 2+ nodes", () => {
    const g = 1024 ** 3;
    const m = imbalancedNodes([
      { address: "a", datacenter: "x", loadBytes: 100 * g },
      { address: "b", datacenter: "x", loadBytes: 100 * g },
      { address: "c", datacenter: "x", loadBytes: 260 * g },
      { address: "d", datacenter: "y", loadBytes: 999 * g }, // alone in its DC
      { address: "e", datacenter: "x", loadBytes: null },
    ]);
    expect([...m.keys()]).toEqual(["c"]);
    expect(m.get("c")).toBeCloseTo(260 / (460 / 3), 5);
    expect(imbalancedNodes([{ address: "a", datacenter: "x", loadBytes: 10 }, { address: "b", datacenter: "x", loadBytes: 12 }]).size).toBe(0);
  });

  it("builds the access problem from status", () => {
    const p = accessProblem(
      {
        method: "SSH_TUNNEL", polling: true, pollIntervalSec: 10, nodes: [
          { address: "a", ok: true, route: null, error: null, lastPollEpochMs: 0 },
          { address: "b", ok: false, route: null, error: "refused", lastPollEpochMs: 0 },
          { address: "c", ok: false, route: null, error: null, lastPollEpochMs: 0 },
        ],
      },
      null,
    );
    expect(p).toEqual({ failed: 2, total: 3, method: "ssh tunnel", errors: [{ address: "b", error: "refused" }, { address: "c", error: "unreachable" }] });
    expect(accessProblem(null, [])).toBeNull();
  });

  it("falls back to snapshot node errors without a status", () => {
    const s = mockSnapshot(Date.UTC(2026, 9, 9, 12));
    expect(accessProblem(null, s.nodes)).toMatchObject({ failed: 1, total: 6, method: "JMX" });
  });

  it("maps Murmur3 tokens onto the ring and splits it into ranges", () => {
    expect(tokenFraction("-9223372036854775808", "org.apache.cassandra.dht.Murmur3Partitioner")).toBe(0);
    expect(tokenFraction("0", "Murmur3Partitioner")).toBeCloseTo(0.5);
    expect(tokenFraction("abc", null)).toBeNull();
    const r = tokenRanges([ringNode("a", ["0"]), ringNode("b", ["-4611686018427387904", "4611686018427387904"])], "Murmur3Partitioner");
    expect(r.map((x) => x.address)).toEqual(["b", "a", "b"]);
    expect(r.map((x) => x.size)).toEqual([0.5, 0.25, 0.25]);
    expect(r[0].start).toBeCloseTo(0.75);
    expect(r.reduce((s, x) => s + x.size, 0)).toBeCloseTo(1);
  });

  it("spreads non-numeric tokens evenly", () => {
    const r = tokenRanges([ringNode("a", ["aa", "cc"]), ringNode("b", ["bb"])], "ByteOrderedPartitioner");
    expect(r.map((x) => x.address)).toEqual(["a", "b", "a"]);
    expect(r.reduce((s, x) => s + x.size, 0)).toBeCloseTo(1);
  });
});
