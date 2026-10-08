// Monaco is loaded from its prebuilt files, copied into public/monaco by
// scripts/copy-monaco.mjs, and served by the engine from the same origin. No CDN is used, so
// Studio works offline and on air-gapped sites (NFR-NET). Bundling Monaco's
// ESM sources instead made the production build run out of memory.
import { loader } from "@monaco-editor/react";
import type * as Monaco from "monaco-editor";
import { CQL_FUNCTIONS, CQL_KEYWORDS, CQL_TYPES, suggest, type Schema } from "./cqlText";

loader.config({ paths: { vs: new URL("./monaco/vs", document.baseURI).href } });

export type MonacoApi = typeof Monaco;

/** Per-editor context the completion provider reads (schema and current keyspace). */
const contexts = new Map<string, { schema: Schema; keyspace: string | null; statementAt: (offset: number) => string }>();

export function setEditorContext(
  modelUri: string,
  ctx: { schema: Schema; keyspace: string | null; statementAt: (offset: number) => string },
) {
  contexts.set(modelUri, ctx);
}

export function clearEditorContext(modelUri: string) {
  contexts.delete(modelUri);
}

let registered = false;

export function registerCql(monaco: MonacoApi) {
  if (registered) return;
  registered = true;
  monaco.languages.register({ id: "cql" });
  const words = (list: string[]) => list.flatMap((k) => k.split(" ")).map((w) => w.toLowerCase());
  monaco.languages.setMonarchTokensProvider("cql", {
    ignoreCase: true,
    keywords: Array.from(new Set(words(CQL_KEYWORDS))),
    typeKeywords: CQL_TYPES,
    functions: CQL_FUNCTIONS.map((f) => f.replace(/\(.*$/, "").toLowerCase()),
    tokenizer: {
      root: [
        [/--.*$/, "comment"],
        [/\/\/.*$/, "comment"],
        [/\/\*/, "comment", "@comment"],
        [/\$\$/, "string", "@dollar"],
        [/'/, "string", "@string"],
        [/"/, "identifier", "@quoted"],
        [/0x[0-9a-fA-F]+/, "number.hex"],
        [/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/, "number"],
        [/-?\d+(\.\d+)?([eE][-+]?\d+)?/, "number"],
        [
          /[a-zA-Z_]\w*/,
          { cases: { "@keywords": "keyword", "@typeKeywords": "type", "@functions": "predefined", "@default": "identifier" } },
        ],
        [/[;,.]/, "delimiter"],
        [/[{}()[\]]/, "@brackets"],
      ],
      comment: [
        [/\*\//, "comment", "@pop"],
        [/./, "comment"],
      ],
      string: [
        [/''/, "string.escape"],
        [/'/, "string", "@pop"],
        [/[^']+/, "string"],
      ],
      quoted: [
        [/""/, "identifier"],
        [/"/, "identifier", "@pop"],
        [/[^"]+/, "identifier"],
      ],
      dollar: [
        [/\$\$/, "string", "@pop"],
        [/./, "string"],
      ],
    },
  });
  monaco.languages.setLanguageConfiguration("cql", {
    comments: { lineComment: "--", blockComment: ["/*", "*/"] },
    brackets: [["(", ")"], ["{", "}"], ["[", "]"]],
    autoClosingPairs: [
      { open: "(", close: ")" },
      { open: "{", close: "}" },
      { open: "[", close: "]" },
      { open: "'", close: "'", notIn: ["string", "comment"] },
      { open: '"', close: '"', notIn: ["string", "comment"] },
    ],
  });
  const kinds = monaco.languages.CompletionItemKind;
  monaco.languages.registerCompletionItemProvider("cql", {
    triggerCharacters: [".", " "],
    provideCompletionItems(model, position) {
      const ctx = contexts.get(model.uri.toString());
      const offset = model.getOffsetAt(position);
      const before = model.getValue().slice(0, offset);
      const lineBefore = before.slice(before.lastIndexOf("\n") + 1);
      const statement = ctx ? ctx.statementAt(offset) : lineBefore;
      const word = model.getWordUntilPosition(position);
      const range = new monaco.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn);
      const list = suggest(lineBefore, statement, ctx?.schema ?? {}, ctx?.keyspace ?? null);
      return {
        suggestions: list.map((s, i) => ({
          label: s.label,
          detail: s.detail ?? s.kind,
          kind:
            s.kind === "keyspace" ? kinds.Module
            : s.kind === "table" ? kinds.Class
            : s.kind === "column" ? kinds.Field
            : s.kind === "type" ? kinds.TypeParameter
            : s.kind === "function" ? kinds.Function
            : kinds.Keyword,
          insertText: s.kind === "function" ? s.label.replace("()", "($0)") : s.label,
          insertTextRules: s.kind === "function" ? monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet : undefined,
          sortText: String(i).padStart(5, "0"),
          range,
        })),
      };
    },
  });
}
