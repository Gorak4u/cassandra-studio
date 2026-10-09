import { describe, expect, it } from "vitest";
import { defaultUnloadPath, fmtBytes, fmtDuration, mappingList, mappingProblems, mappingRecord, statsLine, withExtension } from "../bulkFormat";
import type { BulkStats, TableColumn } from "../bulkApi";

const cols: TableColumn[] = [
  { name: "id", type: "int", kind: "partition_key" },
  { name: "ck", type: "text", kind: "clustering" },
  { name: "v", type: "text", kind: "regular" },
];

describe("bulk helpers", () => {
  it("builds default paths and switches extensions", () => {
    expect(defaultUnloadPath("/home/me/Downloads", "/", "shop.orders", "csv", false)).toBe("/home/me/Downloads/shop.orders.csv");
    expect(defaultUnloadPath("C:\\Users\\me\\Downloads\\", "\\", "q", "json", true)).toBe("C:\\Users\\me\\Downloads\\q.jsonl.gz");
    expect(withExtension("/x/data.csv", "json", true)).toBe("/x/data.jsonl.gz");
    expect(withExtension("/x/data.jsonl.gz", "csv", false)).toBe("/x/data.csv");
    expect(withExtension("/x/data", "csv", false)).toBe("/x/data.csv");
  });

  it("checks the mapping", () => {
    const m = mappingRecord(cols, [{ column: "id", source: "ID" }, { column: "nope", source: "x" }]);
    expect(m).toEqual({ id: "ID", ck: "", v: "" });
    expect(mappingProblems(cols, m)).toEqual(["Primary key column ck must be mapped."]);
    expect(mappingProblems(cols, { id: "", ck: "", v: "" })[0]).toContain("columns id, ck");
    expect(mappingProblems(cols, { id: "a", ck: "b", v: "" })).toEqual([]);
    expect(mappingList({ id: "a", ck: "b", v: "" })).toEqual([{ column: "id", source: "a" }, { column: "ck", source: "b" }]);
  });

  it("formats counters", () => {
    expect(fmtBytes(512)).toBe("512 B");
    expect(fmtBytes(5 * 1024 * 1024)).toBe("5.0 MiB");
    expect(fmtDuration(65_000)).toBe("1 min 5 s");
    const s: BulkStats = {
      kind: "unload", path: "/x.csv", target: "ks.t", rowsRead: 10, rowsWritten: 200000, rejected: 0, bytes: 2048, totalBytes: null,
      rangesDone: 3, rangesFailed: 0, rangesTotal: 48, estimatedRows: null, rowsPerSecond: 25000, elapsedMs: 8000,
      errorFile: null, rejectFile: null, dryRun: false,
    };
    expect(statsLine(s)).toBe("3/48 ranges · 200,000 rows · 2.0 KiB · 25,000 rows/s · 8.0 s");
    expect(statsLine({ ...s, kind: "load", rowsRead: 10, rowsWritten: 8, rejected: 2, dryRun: true, estimatedRows: 10 }))
      .toBe("10 read · 8 valid · 2 rejected · of ~10 · 25,000 rows/s · 8.0 s");
  });
});
