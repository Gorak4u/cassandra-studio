import { useCallback, useEffect, useState } from "react";
import { api } from "../lib/api";
import type { ConnectionConfig, KeyspaceDetails, SchemaTree, TableDetails } from "../lib/types";
import { useGuarded, useToast } from "../components/feedback";
import { Modal } from "../components/Modal";

type Sel = { kind: "keyspace"; ks: string } | { kind: "table"; ks: string; table: string } | null;

/** Schema browser and DDL forms (SCH-1 ... SCH-5). */
export function SchemaPanel(props: { conn: ConnectionConfig; onOpenInEditor: (text: string) => void }) {
  const toast = useToast();
  const guarded = useGuarded();
  const [tree, setTree] = useState<SchemaTree | null>(null);
  const [showSystem, setShowSystem] = useState(false);
  const [open, setOpen] = useState<Set<string>>(new Set());
  const [sel, setSel] = useState<Sel>(null);
  const [ksDetails, setKsDetails] = useState<KeyspaceDetails | null>(null);
  const [tDetails, setTDetails] = useState<TableDetails | null>(null);
  const [form, setForm] = useState<null | "keyspace" | "table">(null);

  const load = useCallback((refresh = false) => {
    api.schema(props.conn.id!, refresh).then(setTree).catch(toast.error);
  }, [props.conn.id, toast]);
  useEffect(() => load(), [load]);

  useEffect(() => {
    setKsDetails(null);
    setTDetails(null);
    if (!sel) return;
    if (sel.kind === "keyspace") api.keyspace(props.conn.id!, sel.ks).then(setKsDetails).catch(toast.error);
    else api.table(props.conn.id!, sel.ks, sel.table).then(setTDetails).catch(toast.error);
  }, [sel, props.conn.id, toast]);

  /** Generate CQL on the engine, then run it through the guarded path (shows it before running). */
  const runDdl = async (op: string, body: unknown, after?: () => void) => {
    try {
      const { cql } = await api.ddl(op, body);
      const res = await guarded((c) => api.query(props.conn.id!, { cql, ...c }));
      if (!res) return;
      const r = res.results[0];
      if (r.status === "error") throw new Error(r.error);
      toast.ok("Done: " + cql.split("\n")[0]);
      load(true);
      after?.();
    } catch (e) {
      toast.error(e);
    }
  };

  const toggle = (k: string) => setOpen((s) => { const n = new Set(s); if (n.has(k)) n.delete(k); else n.add(k); return n; });
  const keyspaces = tree?.keyspaces.filter((k) => showSystem || !k.system) ?? [];

  return (
    <div className="split">
      <div className="stack" style={{ gap: 6, padding: 8 }}>
        <div className="row">
          <button className="btn small" onClick={() => load(true)} title="Reload schema from the cluster">⟳</button>
          <button className="btn small" onClick={() => setForm("keyspace")}>+ Keyspace</button>
          <label className="check"><input type="checkbox" checked={showSystem} onChange={(e) => setShowSystem(e.target.checked)} /> System</label>
        </div>
        <div className="tree">
          {keyspaces.map((k) => (
            <div key={k.name}>
              <div className={"tree-item" + (sel?.kind === "keyspace" && sel.ks === k.name ? " selected" : "")}
                onClick={() => { toggle(k.name); setSel({ kind: "keyspace", ks: k.name }); }}>
                <span className="twisty">{open.has(k.name) ? "▾" : "▸"}</span>
                <span className="name">🗄 {k.name}</span>
                <span className="muted">{k.tables.length}</span>
              </div>
              {open.has(k.name) && (
                <>
                  {k.tables.map((t) => (
                    <div key={t.name} style={{ paddingLeft: 22 }}
                      className={"tree-item" + (sel?.kind === "table" && sel.ks === k.name && sel.table === t.name ? " selected" : "")}
                      onClick={() => setSel({ kind: "table", ks: k.name, table: t.name })}
                      onDoubleClick={() => props.onOpenInEditor(`SELECT * FROM ${k.name}.${t.name} LIMIT 100;`)}>
                      <span className="name">▦ {t.name}</span>
                    </div>
                  ))}
                  {k.views.map((v) => (
                    <div key={"v" + v.name} className="tree-item" style={{ paddingLeft: 22 }}><span className="name muted">◫ {v.name} (view)</span></div>
                  ))}
                  {k.types.map((t) => (
                    <div key={"t" + t.name} className="tree-item" style={{ paddingLeft: 22 }}><span className="name muted">◇ {t.name} (type)</span></div>
                  ))}
                  {k.indexes.map((i) => (
                    <div key={"i" + i.name} className="tree-item" style={{ paddingLeft: 22 }}><span className="name muted">⌕ {i.name} ({i.kind})</span></div>
                  ))}
                  {[...k.functions, ...k.aggregates].map((f) => (
                    <div key={"f" + f.name} className="tree-item" style={{ paddingLeft: 22 }}><span className="name muted">ƒ {f.name}</span></div>
                  ))}
                </>
              )}
            </div>
          ))}
          {!tree && <div className="muted pad">Loading schema…</div>}
        </div>
      </div>
      <div className="pad stack">
        {!sel && <div className="empty">Select a keyspace or table. Double-click a table to query it.</div>}
        {ksDetails && (
          <div className="panel stack">
            <div className="row">
              <h3 style={{ margin: 0 }}>Keyspace {ksDetails.name}</h3>
              <span className="spacer" />
              {!ksDetails.system && (
                <>
                  <button className="btn small" onClick={() => setForm("table")}>+ Table</button>
                  <button className="btn small danger" onClick={() => runDdl("dropKeyspace", { keyspace: ksDetails.name }, () => setSel(null))}>Drop keyspace</button>
                </>
              )}
            </div>
            <table className="data" style={{ width: "auto" }}>
              <tbody>
                {Object.entries(ksDetails.replication).map(([k, v]) => <tr key={k}><th>{k}</th><td className="mono">{v}</td></tr>)}
                <tr><th>durable_writes</th><td>{String(ksDetails.durableWrites)}</td></tr>
                <tr><th>tables</th><td>{ksDetails.tables.length}</td></tr>
              </tbody>
            </table>
            {ksDetails.replication.class?.endsWith("SimpleStrategy") && !ksDetails.system && (
              <div className="notice warn">SimpleStrategy ignores datacenters and racks. Use NetworkTopologyStrategy for multi-DC or production keyspaces.</div>
            )}
            <Ddl text={ksDetails.ddl} onOpen={props.onOpenInEditor} />
          </div>
        )}
        {tDetails && (
          <div className="panel stack">
            <div className="row">
              <h3 style={{ margin: 0 }}>Table {tDetails.keyspace}.{tDetails.name}</h3>
              <span className="spacer" />
              <button className="btn small" onClick={() => props.onOpenInEditor(`SELECT * FROM ${tDetails.keyspace}.${tDetails.name} LIMIT 100;`)}>Query</button>
              {!tDetails.virtual && !tDetails.keyspace.startsWith("system") && (
                <>
                  <button className="btn small danger" onClick={() => runDdl("truncate", { keyspace: tDetails.keyspace, table: tDetails.name })}>Truncate</button>
                  <button className="btn small danger" onClick={() => runDdl("dropTable", { keyspace: tDetails.keyspace, table: tDetails.name }, () => setSel(null))}>Drop</button>
                </>
              )}
            </div>
            <table className="data">
              <thead><tr><th>Column</th><th>Type</th><th>Role</th></tr></thead>
              <tbody>
                {tDetails.columns.map((c) => (
                  <tr key={c.name}>
                    <td className="mono">{c.name}</td>
                    <td className="mono">{c.type}</td>
                    <td>{c.kind === "partition_key" ? `partition key #${c.position + 1}` : c.kind === "clustering" ? `clustering #${c.position + 1} ${c.clusteringOrder ?? ""}` : c.kind}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <details>
              <summary>Table options ({Object.keys(tDetails.options).length})</summary>
              <table className="data">
                <tbody>
                  {Object.entries(tDetails.options).map(([k, v]) => <tr key={k}><th>{k}</th><td className="mono">{typeof v === "object" ? JSON.stringify(v) : String(v)}</td></tr>)}
                </tbody>
              </table>
            </details>
            {tDetails.indexes.length > 0 && (
              <div>
                <b>Indexes</b>
                {tDetails.indexes.map((i) => (
                  <div key={i.name} className="row">
                    <code>{i.name}</code> <span className="muted">{i.kind} on {i.target}</span>
                    <button className="btn link small" onClick={() => runDdl("dropIndex", { keyspace: tDetails.keyspace, name: i.name }, () => setSel({ ...sel! }))}>drop</button>
                  </div>
                ))}
              </div>
            )}
            {Number(tDetails.options["gc_grace_seconds"]) === 0 && (
              <div className="notice warn">gc_grace_seconds is 0: deleted data can come back if a node misses the delete and is not repaired.</div>
            )}
            <Ddl text={tDetails.ddl} onOpen={props.onOpenInEditor} />
          </div>
        )}
      </div>
      {form === "keyspace" && (
        <CreateKeyspaceForm onClose={() => setForm(null)} onSubmit={(spec) => runDdl("createKeyspace", spec, () => setForm(null))} />
      )}
      {form === "table" && ksDetails && (
        <CreateTableForm keyspace={ksDetails.name} onClose={() => setForm(null)}
          onSubmit={(spec) => runDdl("createTable", spec, () => setForm(null))} />
      )}
    </div>
  );
}

function Ddl({ text, onOpen }: { text: string; onOpen: (t: string) => void }) {
  return (
    <div className="stack" style={{ gap: 6 }}>
      <div className="row">
        <b>DDL</b>
        <button className="btn small" onClick={() => navigator.clipboard?.writeText(text)}>Copy</button>
        <button className="btn small" onClick={() => onOpen(text)}>Open in editor</button>
      </div>
      <pre className="ddl">{text}</pre>
    </div>
  );
}

function CreateKeyspaceForm(props: { onClose: () => void; onSubmit: (spec: unknown) => void }) {
  const [name, setName] = useState("");
  const [strategy, setStrategy] = useState("NetworkTopologyStrategy");
  const [dcs, setDcs] = useState("dc1:3");
  const [rf, setRf] = useState(3);
  const [durable, setDurable] = useState(true);
  const datacenters = Object.fromEntries(
    dcs.split(",").map((s) => s.trim()).filter(Boolean).map((s) => { const [d, n] = s.split(":"); return [d.trim(), Number(n ?? 3)]; }),
  );
  return (
    <Modal title="Create keyspace" onClose={props.onClose}
      footer={<>
        <button className="btn" onClick={props.onClose}>Cancel</button>
        <button className="btn primary" disabled={!name.trim()} onClick={() => props.onSubmit({
          name: name.trim(), strategy, replicationFactor: rf, datacenters, durableWrites: durable, ifNotExists: true,
        })}>Review CQL…</button>
      </>}>
      <div className="grid2">
        <label className="field"><span>Name</span><input autoFocus value={name} onChange={(e) => setName(e.target.value)} /></label>
        <label className="field"><span>Strategy</span>
          <select value={strategy} onChange={(e) => setStrategy(e.target.value)}>
            <option>NetworkTopologyStrategy</option><option>SimpleStrategy</option>
          </select>
        </label>
        {strategy === "NetworkTopologyStrategy"
          ? <label className="field"><span>Datacenters (dc:rf, comma separated)</span><input value={dcs} onChange={(e) => setDcs(e.target.value)} /></label>
          : <label className="field"><span>Replication factor</span><input type="number" min={1} value={rf} onChange={(e) => setRf(Number(e.target.value))} /></label>}
        <label className="check"><input type="checkbox" checked={durable} onChange={(e) => setDurable(e.target.checked)} /> durable_writes</label>
      </div>
    </Modal>
  );
}

interface ColRow { name: string; type: string; role: "pk" | "ck" | "static" | "regular"; order: "ASC" | "DESC" }

function CreateTableForm(props: { keyspace: string; onClose: () => void; onSubmit: (spec: unknown) => void }) {
  const [name, setName] = useState("");
  const [cols, setCols] = useState<ColRow[]>([
    { name: "id", type: "uuid", role: "pk", order: "ASC" },
    { name: "", type: "text", role: "regular", order: "ASC" },
  ]);
  const [options, setOptions] = useState("");
  const set = (i: number, patch: Partial<ColRow>) => setCols((c) => c.map((x, k) => (k === i ? { ...x, ...patch } : x)));
  const submit = () => {
    const valid = cols.filter((c) => c.name.trim());
    const opts = Object.fromEntries(options.split("\n").map((l) => l.trim()).filter(Boolean).map((l) => {
      const i = l.indexOf("=");
      return [l.slice(0, i).trim(), l.slice(i + 1).trim()];
    }));
    props.onSubmit({
      keyspace: props.keyspace, name: name.trim(), ifNotExists: true,
      columns: valid.map((c) => ({ name: c.name.trim(), type: c.type.trim(), isStatic: c.role === "static" })),
      partitionKey: valid.filter((c) => c.role === "pk").map((c) => c.name.trim()),
      clustering: valid.filter((c) => c.role === "ck").map((c) => ({ name: c.name.trim(), order: c.order })),
      options: opts,
    });
  };
  return (
    <Modal title={`Create table in ${props.keyspace}`} onClose={props.onClose} width={760}
      footer={<>
        <button className="btn" onClick={props.onClose}>Cancel</button>
        <button className="btn primary" disabled={!name.trim()} onClick={submit}>Review CQL…</button>
      </>}>
      <div className="stack">
        <label className="field"><span>Table name</span><input autoFocus value={name} onChange={(e) => setName(e.target.value)} /></label>
        <table className="data">
          <thead><tr><th>Column</th><th>Type</th><th>Role</th><th>Order</th><th /></tr></thead>
          <tbody>
            {cols.map((c, i) => (
              <tr key={i}>
                <td><input value={c.name} onChange={(e) => set(i, { name: e.target.value })} aria-label="Column name" /></td>
                <td><input value={c.type} onChange={(e) => set(i, { type: e.target.value })} list="cql-types" aria-label="Column type" /></td>
                <td>
                  <select value={c.role} onChange={(e) => set(i, { role: e.target.value as ColRow["role"] })}>
                    <option value="pk">partition key</option><option value="ck">clustering</option>
                    <option value="static">static</option><option value="regular">regular</option>
                  </select>
                </td>
                <td>{c.role === "ck" && (
                  <select value={c.order} onChange={(e) => set(i, { order: e.target.value as "ASC" | "DESC" })}>
                    <option>ASC</option><option>DESC</option>
                  </select>)}
                </td>
                <td><button className="btn link" onClick={() => setCols((x) => x.filter((_, k) => k !== i))}>✕</button></td>
              </tr>
            ))}
          </tbody>
        </table>
        <datalist id="cql-types">
          {["text", "int", "bigint", "uuid", "timeuuid", "timestamp", "date", "boolean", "double", "decimal", "blob",
            "list<text>", "set<text>", "map<text, text>", "counter", "inet", "varint"].map((t) => <option key={t} value={t} />)}
        </datalist>
        <div><button className="btn small" onClick={() => setCols((c) => [...c, { name: "", type: "text", role: "regular", order: "ASC" }])}>+ Column</button></div>
        <label className="field"><span>Options, one per line (e.g. default_time_to_live = 86400)</span>
          <textarea rows={3} value={options} onChange={(e) => setOptions(e.target.value)}
            placeholder={"compaction = {'class': 'LeveledCompactionStrategy'}\ngc_grace_seconds = 864000"} />
        </label>
      </div>
    </Modal>
  );
}
