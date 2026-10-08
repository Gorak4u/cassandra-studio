import Editor, { type OnMount } from "@monaco-editor/react";
import { useEffect, useRef } from "react";
import { clearEditorContext, monaco, registerCql, setEditorContext } from "../lib/monaco";
import { statementAt, type Schema } from "../lib/cqlText";

registerCql();

export interface CqlEditorHandle {
  /** Selection if any, else the statement under the cursor. */
  current: () => string;
  all: () => string;
}

/** Monaco with CQL highlighting and schema-aware completion (CQL-1). */
export function CqlEditor(props: {
  value: string;
  onChange: (v: string) => void;
  schema: Schema;
  keyspace: string | null;
  dark: boolean;
  onRunCurrent: () => void;
  onRunAll: () => void;
  handleRef: React.MutableRefObject<CqlEditorHandle | null>;
}) {
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const runCurrent = useRef(props.onRunCurrent);
  const runAll = useRef(props.onRunAll);
  runCurrent.current = props.onRunCurrent;
  runAll.current = props.onRunAll;

  useEffect(() => {
    const ed = editorRef.current;
    const model = ed?.getModel();
    if (!model) return;
    const uri = model.uri.toString();
    setEditorContext(uri, {
      schema: props.schema,
      keyspace: props.keyspace,
      statementAt: (offset) => statementAt(model.getValue(), offset)?.text ?? "",
    });
  }, [props.schema, props.keyspace]);

  useEffect(() => () => {
    const uri = editorRef.current?.getModel()?.uri.toString();
    if (uri) clearEditorContext(uri);
  }, []);

  const onMount: OnMount = (ed) => {
    editorRef.current = ed;
    const model = ed.getModel()!;
    setEditorContext(model.uri.toString(), {
      schema: props.schema,
      keyspace: props.keyspace,
      statementAt: (offset) => statementAt(model.getValue(), offset)?.text ?? "",
    });
    ed.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => runCurrent.current());
    ed.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.Enter, () => runAll.current());
    props.handleRef.current = {
      current: () => {
        const sel = ed.getSelection();
        if (sel && !sel.isEmpty()) return model.getValueInRange(sel);
        const pos = ed.getPosition();
        const offset = pos ? model.getOffsetAt(pos) : 0;
        return statementAt(model.getValue(), offset)?.text ?? "";
      },
      all: () => model.getValue(),
    };
    ed.focus();
  };

  return (
    <Editor
      language="cql"
      theme={props.dark ? "vs-dark" : "vs"}
      value={props.value}
      onChange={(v) => props.onChange(v ?? "")}
      onMount={onMount}
      options={{
        minimap: { enabled: false },
        fontSize: 13,
        wordWrap: "on",
        scrollBeyondLastLine: false,
        automaticLayout: true,
        tabSize: 2,
        renderLineHighlight: "line",
        quickSuggestions: { other: true, comments: false, strings: false },
      }}
    />
  );
}
