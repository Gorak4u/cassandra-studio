// Node, keyspace and table pickers shared by the operations tabs.
import { useEffect, useMemo, useState } from "react";
import { api } from "../../lib/api";
import type { KeyspaceNode, NodeInfo } from "../../lib/types";

/** Keyspaces with their tables from the schema API; system keyspaces last. */
export function useKeyspaces(connectionId: string): { keyspaces: KeyspaceNode[]; error: string | null } {
  const [keyspaces, setKeyspaces] = useState<KeyspaceNode[]>([]);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    let stop = false;
    api.schema(connectionId).then((t) => {
      if (stop) return;
      setKeyspaces([...t.keyspaces].sort((a, b) => Number(a.system) - Number(b.system) || a.name.localeCompare(b.name)));
    }).catch((e) => { if (!stop) setError(String(e?.message ?? e)); });
    return () => { stop = true; };
  }, [connectionId]);
  return { keyspaces, error };
}

/** Multi-select of nodes grouped by datacenter, with select-all per DC (OPS-2 "a set of nodes"). */
export function NodePicker(props: { nodes: NodeInfo[]; selected: string[]; onChange: (s: string[]) => void; idPrefix: string }) {
  const byDc = useMemo(() => {
    const m = new Map<string, NodeInfo[]>();
    for (const n of props.nodes) {
      const dc = n.datacenter ?? "unknown";
      m.set(dc, [...(m.get(dc) ?? []), n]);
    }
    return [...m.entries()].sort(([a], [b]) => a.localeCompare(b));
  }, [props.nodes]);
  const sel = new Set(props.selected);
  const toggle = (addrs: string[], on: boolean) => {
    const next = new Set(sel);
    addrs.forEach((a) => (on ? next.add(a) : next.delete(a)));
    props.onChange(props.nodes.map((n) => n.address).filter((a) => next.has(a)));
  };
  return (
    <div className="ops-nodes" data-testid="ops-node-picker">
      <div className="row ops-nodes-head">
        <b id={`${props.idPrefix}-nodes-label`}>Nodes</b>
        <span className="spacer" />
        <button className="btn link small" onClick={() => toggle(props.nodes.map((n) => n.address), true)}>All</button>
        <button className="btn link small" onClick={() => toggle(props.nodes.map((n) => n.address), false)}>None</button>
      </div>
      {byDc.map(([dc, nodes]) => {
        const addrs = nodes.map((n) => n.address);
        const all = addrs.every((a) => sel.has(a));
        return (
          <fieldset key={dc} className="ops-dc">
            <legend>
              <label>
                <input type="checkbox" checked={all} onChange={(e) => toggle(addrs, e.target.checked)} /> {dc}
              </label>
            </legend>
            {nodes.map((n) => (
              <label key={n.address} className="ops-node" title={`${n.rack ?? ""} ${n.version ?? ""}`}>
                <input type="checkbox" checked={sel.has(n.address)} onChange={(e) => toggle([n.address], e.target.checked)} />
                <span className="mono">{n.address}</span>
                <span className={"status " + n.state}>{n.state}</span>
                <span className="muted ops-node-meta">{n.rack}{n.version ? " · " + n.version : ""}</span>
              </label>
            ))}
          </fieldset>
        );
      })}
    </div>
  );
}

export function KeyspaceSelect(props: { id: string; keyspaces: KeyspaceNode[]; value: string; onChange: (v: string) => void; optional?: boolean }) {
  return (
    <label className="ops-field">
      <span>Keyspace</span>
      <select id={props.id} value={props.value} onChange={(e) => props.onChange(e.target.value)}>
        <option value="">{props.optional ? "(all)" : "Choose…"}</option>
        {props.keyspaces.map((k) => <option key={k.name} value={k.name}>{k.name}{k.system ? " (system)" : ""}</option>)}
      </select>
    </label>
  );
}

/** Tables of one keyspace as checkboxes; none checked = the whole keyspace. */
export function TableChecks(props: { keyspace: KeyspaceNode | undefined; value: string[]; onChange: (v: string[]) => void }) {
  if (!props.keyspace) return null;
  const tables = props.keyspace.tables.map((t) => t.name);
  if (tables.length === 0) return <div className="muted">No tables in {props.keyspace.name}.</div>;
  return (
    <fieldset className="ops-tables">
      <legend>Tables <span className="muted">(none = all tables)</span></legend>
      {tables.map((t) => (
        <label key={t} className="ops-check">
          <input type="checkbox" checked={props.value.includes(t)}
            onChange={(e) => props.onChange(e.target.checked ? [...props.value, t] : props.value.filter((x) => x !== t))} />
          {t}
        </label>
      ))}
    </fieldset>
  );
}
