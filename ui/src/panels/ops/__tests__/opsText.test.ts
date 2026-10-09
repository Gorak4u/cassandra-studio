import { describe, expect, it } from "vitest";
import { isNumeric, sectionText, viewText } from "../opsText";
import { formatBytes } from "../SnapshotsTab";

describe("copy as text", () => {
  it("aligns table columns like nodetool, numbers to the right", () => {
    const t = sectionText({ title: "Thread pools", keyValue: false, columns: ["Pool Name", "Active", "Completed"],
      rows: [["ReadStage", "0", "1234"], ["MutationStage", "12", null]] });
    expect(t).toBe([
      "Thread pools",
      "Pool Name      Active  Completed",
      "ReadStage           0       1234",
      "MutationStage      12  n/a",
    ].join("\n"));
  });

  it("prints key/value sections as name : value and adds the command and notes", () => {
    const text = viewText({ view: "info", node: "10.0.0.1", command: "nodetool -h 10.0.0.1 info", notes: ["a note"],
      sections: [{ title: "Node", keyValue: true, columns: ["Name", "Value"], rows: [["ID", "abc"], ["Load", "1.5 MiB"]] }] });
    expect(text).toBe("$ nodetool -h 10.0.0.1 info\n\nNode\nID   : abc\nLoad : 1.5 MiB\n\nNote: a note\n");
  });

  it("says (none) for empty tables", () => {
    expect(sectionText({ title: "Active", keyValue: false, columns: ["id"], rows: [] })).toContain("(none)");
  });

  it("recognises numeric cells", () => {
    expect(isNumeric("12")).toBe(true);
    expect(isNumeric("33.3%")).toBe(true);
    expect(isNumeric("1.50 KiB")).toBe(true);
    expect(isNumeric("10.231.42.11")).toBe(false);
    expect(isNumeric("UN")).toBe(false);
    expect(isNumeric(null)).toBe(false);
  });

  it("formats snapshot sizes", () => {
    expect(formatBytes(512)).toBe("512 bytes");
    expect(formatBytes(6236)).toBe("6.09 KiB");
    expect(formatBytes(null)).toBe("n/a");
  });
});
