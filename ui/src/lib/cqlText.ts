// Pure CQL text helpers for the editor: find the statement under the cursor and
// work out what to auto-complete. Mirrors the engine's CqlScript rules (quotes,
// comments, $$ bodies, BEGIN BATCH ... APPLY BATCH).

export interface Span {
  start: number;
  end: number;
  text: string;
}

/** Statement spans in the script, comments included in no span's text boundaries. */
export function statementSpans(script: string): Span[] {
  const spans: Span[] = [];
  let start = -1;
  let i = 0;
  const n = script.length;
  const flush = (end: number) => {
    if (start >= 0) {
      const text = script.slice(start, end).trim();
      if (text) spans.push({ start, end, text });
    }
    start = -1;
  };
  const inBatch = () => {
    if (start < 0) return false;
    const s = script.slice(start, i).replace(/--[^\n]*|\/\/[^\n]*|\/\*[\s\S]*?\*\//g, " ").trim().toUpperCase().replace(/\s+/g, " ");
    return s.startsWith("BEGIN ") && !/\bAPPLY BATCH\s*$/.test(s);
  };
  while (i < n) {
    const c = script[i];
    const next = script[i + 1];
    if ((c === "-" && next === "-") || (c === "/" && next === "/")) {
      while (i < n && script[i] !== "\n") i++;
      continue;
    }
    if (c === "/" && next === "*") {
      const e = script.indexOf("*/", i + 2);
      i = e < 0 ? n : e + 2;
      continue;
    }
    if (start < 0 && !/\s/.test(c) && c !== ";") start = i;
    if (c === "'" || c === '"') {
      let j = i + 1;
      while (j < n) {
        if (script[j] === c) {
          if (script[j + 1] === c) {
            j += 2;
            continue;
          }
          break;
        }
        j++;
      }
      i = Math.min(j + 1, n);
      continue;
    }
    if (c === "$" && next === "$") {
      const e = script.indexOf("$$", i + 2);
      i = e < 0 ? n : e + 2;
      continue;
    }
    if (c === ";" && !inBatch()) {
      flush(i);
      i++;
      continue;
    }
    i++;
  }
  flush(n);
  return spans;
}

/** The statement containing (or ending just before) the cursor offset. */
export function statementAt(script: string, offset: number): Span | null {
  const spans = statementSpans(script);
  let best: Span | null = null;
  for (const s of spans) {
    if (offset >= s.start && offset <= s.end + 1) return s;
    if (s.end < offset) best = s;
  }
  return best ?? spans[0] ?? null;
}

export const CQL_KEYWORDS = [
  "SELECT", "FROM", "WHERE", "AND", "OR", "IN", "CONTAINS", "KEY", "LIMIT", "PER PARTITION LIMIT", "ORDER BY", "ASC",
  "DESC", "ALLOW FILTERING", "GROUP BY", "DISTINCT", "JSON", "INSERT INTO", "VALUES", "IF NOT EXISTS", "IF EXISTS",
  "USING TTL", "USING TIMESTAMP", "UPDATE", "SET", "DELETE", "BEGIN BATCH", "BEGIN UNLOGGED BATCH", "APPLY BATCH",
  "TRUNCATE", "CREATE KEYSPACE", "CREATE TABLE", "CREATE INDEX", "CREATE CUSTOM INDEX", "CREATE TYPE",
  "CREATE MATERIALIZED VIEW", "CREATE FUNCTION", "CREATE AGGREGATE", "CREATE ROLE", "ALTER KEYSPACE", "ALTER TABLE",
  "ALTER TYPE", "ALTER ROLE", "DROP KEYSPACE", "DROP TABLE", "DROP INDEX", "DROP TYPE", "DROP MATERIALIZED VIEW",
  "DROP ROLE", "GRANT", "REVOKE", "LIST ROLES", "LIST ALL PERMISSIONS", "WITH", "replication", "PRIMARY KEY",
  "CLUSTERING ORDER BY", "STATIC", "FROZEN", "USE", "DESCRIBE", "DESCRIBE KEYSPACES", "DESCRIBE TABLES",
  "DESCRIBE KEYSPACE", "DESCRIBE TABLE", "CONSISTENCY", "TRACING ON", "TRACING OFF", "TOKEN", "WRITETIME", "TTL",
  "CAST", "NULL", "true", "false",
];

export const CQL_TYPES = [
  "ascii", "bigint", "blob", "boolean", "counter", "date", "decimal", "double", "duration", "float", "inet", "int",
  "smallint", "text", "time", "timestamp", "timeuuid", "tinyint", "uuid", "varchar", "varint", "list", "set", "map",
  "tuple", "frozen", "vector",
];

export const CQL_FUNCTIONS = [
  "now()", "uuid()", "toTimestamp()", "toDate()", "toUnixTimestamp()", "currentTimestamp()", "currentDate()",
  "minTimeuuid()", "maxTimeuuid()", "token()", "writetime()", "ttl()", "count(*)", "min()", "max()", "sum()", "avg()",
  "toJson()", "fromJson()", "blobAsText()", "textAsBlob()",
];

export type Schema = Record<string, Record<string, string[]>>;

export interface Suggestion {
  label: string;
  kind: "keyspace" | "table" | "column" | "keyword" | "type" | "function";
  detail?: string;
}

const AFTER_TABLE = /\b(FROM|INTO|UPDATE|TABLE|TRUNCATE|DESCRIBE|DESC|ON)\s+([\w"]*\.?[\w"]*)$/i;

/** Suggestions for the text before the cursor, inside the current statement. */
export function suggest(beforeCursor: string, statement: string, schema: Schema, keyspace: string | null): Suggestion[] {
  const dotted = /([\w"]+)\.([\w"]*)$/.exec(beforeCursor);
  if (dotted) {
    const ks = unquote(dotted[1]);
    if (schema[ks]) return Object.keys(schema[ks]).sort().map((t) => ({ label: t, kind: "table", detail: ks }));
    // "alias.col" or "table.col": columns of that table in the current keyspace
    const cols = keyspace && schema[keyspace]?.[ks];
    if (cols) return cols.map((c) => ({ label: c, kind: "column", detail: ks }));
  }
  if (AFTER_TABLE.test(beforeCursor)) {
    const out: Suggestion[] = Object.keys(schema).sort().map((k) => ({ label: k, kind: "keyspace" }));
    if (keyspace && schema[keyspace]) {
      for (const t of Object.keys(schema[keyspace]).sort()) out.push({ label: t, kind: "table", detail: keyspace });
    }
    return out;
  }
  const out: Suggestion[] = [];
  const seen = new Set<string>();
  for (const [ks, table] of tablesIn(statement, keyspace)) {
    for (const c of schema[ks]?.[table] ?? []) {
      if (!seen.has(c)) {
        seen.add(c);
        out.push({ label: c, kind: "column", detail: `${ks}.${table}` });
      }
    }
  }
  if (/\b(CREATE\s+TABLE|ALTER\s+TABLE|CREATE\s+TYPE)\b/i.test(statement)) {
    for (const t of CQL_TYPES) out.push({ label: t, kind: "type" });
  }
  for (const k of CQL_KEYWORDS) out.push({ label: k, kind: "keyword" });
  for (const f of CQL_FUNCTIONS) out.push({ label: f, kind: "function" });
  return out;
}

/** [keyspace, table] pairs referenced by FROM / INTO / UPDATE in a statement. */
export function tablesIn(statement: string, keyspace: string | null): [string, string][] {
  const out: [string, string][] = [];
  const re = /\b(?:FROM|INTO|UPDATE|TABLE)\s+(?:([\w"]+)\.)?([\w"]+)/gi;
  let m: RegExpExecArray | null;
  while ((m = re.exec(statement))) {
    const ks = m[1] ? unquote(m[1]) : keyspace;
    if (ks) out.push([ks, unquote(m[2])]);
  }
  return out;
}

function unquote(id: string): string {
  return id.startsWith('"') && id.endsWith('"') ? id.slice(1, -1).replace(/""/g, '"') : id.toLowerCase();
}
