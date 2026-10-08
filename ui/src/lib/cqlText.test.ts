import { describe, expect, it } from "vitest";
import { cqlId, statementAt, statementSpans, suggest, tablesIn } from "./cqlText";

describe("statementSpans", () => {
  it("splits like the engine", () => {
    const s = "SELECT 'a;b' FROM t; -- c;\nBEGIN BATCH INSERT INTO t (k) VALUES (1); APPLY BATCH;\nSELECT 2 FROM u";
    expect(statementSpans(s).map((x) => x.text)).toEqual([
      "SELECT 'a;b' FROM t",
      "BEGIN BATCH INSERT INTO t (k) VALUES (1); APPLY BATCH",
      "SELECT 2 FROM u",
    ]);
  });
});

describe("statementAt", () => {
  const script = "SELECT * FROM a;\n\nSELECT * FROM b;\n";
  it("finds the statement under the cursor", () => {
    expect(statementAt(script, 3)?.text).toBe("SELECT * FROM a");
    expect(statementAt(script, script.indexOf("FROM b"))?.text).toBe("SELECT * FROM b");
  });
  it("uses the statement just before the cursor when on a blank line", () => {
    expect(statementAt(script, script.length)?.text).toBe("SELECT * FROM b");
  });
});

describe("suggest", () => {
  const schema = { shop: { orders: ["id", "total"], users: ["id", "name"] }, logs: { events: ["ts"] } };
  it("offers keyspaces and current-keyspace tables after FROM", () => {
    const s = suggest("SELECT * FROM ", "SELECT * FROM ", schema, "shop").map((x) => x.label);
    expect(s).toEqual(["logs", "shop", "orders", "users"]);
  });
  it("offers tables after keyspace dot", () => {
    expect(suggest("SELECT * FROM shop.", "", schema, null).map((x) => x.label)).toEqual(["orders", "users"]);
  });
  it("offers columns of referenced tables first", () => {
    const st = "SELECT  FROM shop.users";
    const s = suggest("SELECT ", st, schema, null);
    expect(s.slice(0, 2).map((x) => x.label)).toEqual(["id", "name"]);
    expect(s.some((x) => x.kind === "keyword")).toBe(true);
  });
  it("finds tables in statements", () => {
    expect(tablesIn('UPDATE "Shop".t SET a = 1', null)).toEqual([["Shop", "t"]]);
    expect(tablesIn("INSERT INTO t (k) VALUES (1)", "ks")).toEqual([["ks", "t"]]);
  });
});

describe("cqlId", () => {
  it("quotes only when needed", () => {
    expect(cqlId("orders")).toBe("orders");
    expect(cqlId("MyTable")).toBe('"MyTable"');
    expect(cqlId("table")).toBe('"table"');
    expect(cqlId('we"ird')).toBe('"we""ird"');
  });
});
