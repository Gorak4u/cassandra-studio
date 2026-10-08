import { describe, expect, it } from "vitest";
import { toCsv, toJson } from "./export";

const cols = [
  { name: "id", type: "int" },
  { name: "note", type: "text" },
];

describe("export", () => {
  it("quotes CSV fields that need it", () => {
    expect(toCsv(cols, [[1, 'say "hi", ok'], [2, null], [3, "line\nbreak"]])).toBe(
      'id,note\r\n1,"say ""hi"", ok"\r\n2,\r\n3,"line\nbreak"\r\n',
    );
  });
  it("writes JSON objects per row", () => {
    expect(JSON.parse(toJson(cols, [[1, "a"]]))).toEqual([{ id: 1, note: "a" }]);
  });
});
