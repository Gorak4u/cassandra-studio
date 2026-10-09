import { describe, expect, it } from "vitest";
import { fmtBytes, fmtCount, fmtDuration, fmtMicros, fmtMs, fmtPct, fmtRate, fmtRatio, fmtSince, formatterFor, sum } from "../format";
import { sortRows } from "../common";

describe("formatters", () => {
  it("returns null for missing values, never 0", () => {
    for (const f of [fmtBytes, fmtMicros, fmtMs, fmtPct, fmtRatio, fmtCount, fmtRate, fmtDuration]) {
      expect(f(null)).toBeNull();
      expect(f(undefined)).toBeNull();
      expect(f(NaN)).toBeNull();
    }
    expect(fmtSince(null)).toBeNull();
    expect(fmtBytes(0)).toBe("0 B");
  });

  it("formats bytes in binary units", () => {
    expect(fmtBytes(512)).toBe("512 B");
    expect(fmtBytes(1536)).toBe("1.5 KiB");
    expect(fmtBytes(8 * 1024 ** 3)).toBe("8 GiB");
    expect(fmtBytes(262 * 1024 ** 3)).toBe("262 GiB");
    expect(fmtBytes(1.25 * 1024 ** 4)).toBe("1.3 TiB");
  });

  it("formats microseconds as ms", () => {
    expect(fmtMicros(420)).toBe("420 µs");
    expect(fmtMicros(3200)).toBe("3.2 ms");
    expect(fmtMicros(12_345)).toBe("12.3 ms");
    expect(fmtMicros(250_000)).toBe("250 ms");
    expect(fmtMs(12_500)).toBe("12.5 s");
    expect(fmtMs(18.25)).toBe("18.3 ms");
  });

  it("formats percentages, ratios, counts and rates", () => {
    expect(fmtPct(87.456)).toBe("87.5 %");
    expect(fmtPct(10)).toBe("10 %");
    expect(fmtRatio(0.97)).toBe("97 %");
    expect(fmtCount(1234)).toBe("1234");
    expect(fmtCount(48_200_113)).toBe("48.2M");
    expect(fmtCount(0.4)).toBe("0.4");
    expect(fmtRate(1500)).toBe("1500/s");
    expect(formatterFor("bytes")(1024)).toBe("1 KiB");
    expect(formatterFor("pct")(5)).toBe("5 %");
  });

  it("formats durations and relative time", () => {
    expect(fmtDuration(35)).toBe("35s");
    expect(fmtDuration(4 * 3600 + 12 * 60)).toBe("4h 12m");
    expect(fmtDuration(12 * 86_400 + 3 * 3600)).toBe("12d 3h");
    const now = 1_000_000_000;
    expect(fmtSince(now - 10_000, now)).toBe("just now");
    expect(fmtSince(now - 95_000, now)).toBe("2 min ago");
    expect(fmtSince(now - 6 * 3_600_000, now)).toBe("6 h ago");
  });

  it("sums ignoring missing values", () => {
    expect(sum([1, null, 2])).toBe(3);
    expect(sum([null, undefined])).toBeNull();
  });

  it("sorts with missing values last in both directions", () => {
    const rows = [{ v: 2 }, { v: null }, { v: 10 }, { v: 1 }];
    expect(sortRows(rows, (r) => r.v, "asc").map((r) => r.v)).toEqual([1, 2, 10, null]);
    expect(sortRows(rows, (r) => r.v, "desc").map((r) => r.v)).toEqual([10, 2, 1, null]);
    expect(sortRows([{ s: "10.0.1.11" }, { s: "10.0.1.9" }], (r) => r.s, "asc").map((r) => r.s)).toEqual(["10.0.1.9", "10.0.1.11"]);
  });
});
