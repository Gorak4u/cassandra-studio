import { useCallback, useEffect, useMemo, useState } from "react";
import type { MonitoringClient } from "../../lib/monitoringApi";
import type { NodeSnapshot, Ring, RingDc } from "../../lib/monitoringTypes";
import { buildRingOption, type RingNodeInfo } from "./chartOptions";
import { fmtBytes, fmtPct } from "./format";
import { imbalancedNodes, stateCode, tokenRanges } from "./health";
import { ErrorState, Loading, SortTable, Val, type Col } from "./common";
import { EChart } from "./EChart";
import type { ChartTheme, NodeStyle } from "./palette";

interface Row { dc: string; address: string; rack: string | null; state: string; loadBytes: number | null; vnodes: number; owns: number | null; effective: number | null; ratio: number | null }

/** Token ring per DC (MON-14) and ownership like `nodetool ring` / `status <keyspace>` (MON-13). */
export default function RingView(props: { client: MonitoringClient; styles: Map<string, NodeStyle>; dark: boolean; nodes: NodeSnapshot[]; onNode: (address: string) => void }) {
  const [keyspace, setKeyspace] = useState<string | null>(null);
  const [keyspaces, setKeyspaces] = useState<string[]>([]);
  const [ring, setRing] = useState<Ring | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [reload, setReload] = useState(0);
  const { client } = props;

  useEffect(() => {
    let alive = true;
    client.tables().then((t) => alive && setKeyspaces([...new Set(t.map((x) => x.keyspace))].sort())).catch(() => undefined);
    return () => { alive = false; };
  }, [client]);

  useEffect(() => {
    let alive = true;
    setError(null);
    client.ring(keyspace).then((r) => alive && setRing(r)).catch((e) => alive && setError(e));
    return () => { alive = false; };
  }, [client, keyspace, reload]);

  // Live state from the snapshot wins over the ring's (the ring is read on request).
  const liveState = useMemo(() => new Map(props.nodes.map((n) => [n.address, n.state])), [props.nodes]);

  if (error) return <ErrorState error={error} onRetry={() => setReload((r) => r + 1)} />;
  if (!ring) return <Loading what="token ring" />;
  if (!ring.datacenters.length) return <div className="empty">No token information was reported.</div>;

  const rows: Row[] = ring.datacenters.flatMap((dc) => {
    const imb = imbalancedNodes(dc.nodes.map((n) => ({ ...n, datacenter: dc.name })));
    return dc.nodes.map((n) => ({
      dc: dc.name, address: n.address, rack: n.rack, state: stateCode(liveState.get(n.address) ?? n.state), loadBytes: n.loadBytes,
      vnodes: n.tokens.length, owns: n.ownershipPct, effective: n.effectiveOwnershipPct, ratio: imb.get(n.address) ?? null,
    }));
  });
  const options = [...new Set([...(ring.keyspace ? [ring.keyspace] : []), ...keyspaces])].sort();
  const cols: Col<Row>[] = [
    { key: "dc", label: "DC", sort: (r) => r.dc, render: (r) => r.dc },
    {
      key: "address", label: "Address", sort: (r) => r.address,
      render: (r) => (
        <button className="btn link mono mon-node-link" onClick={() => props.onNode(r.address)} aria-label={`Open details for ${r.address}`}>
          <span className="mon-swatch" style={{ background: props.styles.get(r.address)?.color }} aria-hidden="true" />{r.address}
        </button>
      ),
    },
    { key: "rack", label: "Rack", sort: (r) => r.rack, render: (r) => <Val v={r.rack} /> },
    { key: "state", label: "State", sort: (r) => r.state, render: (r) => <span className={"status " + (r.state.startsWith("U") ? "UP" : "DOWN")}>{r.state}</span> },
    {
      key: "load", label: "Load", num: true, sort: (r) => r.loadBytes,
      render: (r) => r.ratio ? <span className="mon-warn-text">▲ {fmtBytes(r.loadBytes)} ({r.ratio.toFixed(2)}× DC avg)</span> : <Val v={fmtBytes(r.loadBytes)} />,
    },
    { key: "vnodes", label: "Tokens", num: true, sort: (r) => r.vnodes, render: (r) => r.vnodes },
    { key: "owns", label: "Owns", num: true, sort: (r) => r.owns, render: (r) => <Val v={fmtPct(r.owns)} /> },
    { key: "eff", label: `Effective (${ring.keyspace ?? "n/a"})`, num: true, sort: (r) => r.effective, render: (r) => <Val v={fmtPct(r.effective)} /> },
  ];

  return (
    <div className="pad stack" data-testid="monitoring-ring">
      <div className="row mon-toolbar">
        <label className="row">
          <span className="muted">Keyspace for effective ownership</span>
          <select value={ring.keyspace ?? ""} onChange={(e) => setKeyspace(e.target.value || null)} aria-label="Keyspace for effective ownership">
            {!ring.keyspace && <option value="">(none)</option>}
            {options.map((k) => <option key={k} value={k}>{k}</option>)}
          </select>
        </label>
        <span className="muted">Partitioner: {ring.partitioner?.replace("org.apache.cassandra.dht.", "") ?? "n/a"}</span>
        <span className="spacer" />
        <button className="btn small" onClick={() => setReload((r) => r + 1)}>⟳ Refresh ring</button>
      </div>
      <div className="mon-ring-grid">
        {ring.datacenters.map((dc) => (
          <DcRing key={dc.name} dc={dc} partitioner={ring.partitioner} rows={rows} styles={props.styles} dark={props.dark} onNode={props.onNode} />
        ))}
      </div>
      <div className="muted mon-footnote">Arcs are token ranges coloured by owning node; hatched, faded arcs belong to nodes that are not up. Click an arc for node details.</div>
      <div className="panel">
        <h3>Ownership</h3>
        <SortTable rows={rows} cols={cols} rowKey={(r) => r.address} label="Token ownership" initial={{ key: "dc", dir: "asc" }} rowClass={(r) => (r.ratio ? "mon-row-warn" : undefined)} />
      </div>
    </div>
  );
}

function DcRing(props: { dc: RingDc; partitioner: string | null; rows: Row[]; styles: Map<string, NodeStyle>; dark: boolean; onNode: (a: string) => void }) {
  const { dc, partitioner, rows, styles } = props;
  const ranges = useMemo(() => tokenRanges(dc.nodes, partitioner), [dc, partitioner]);
  const info = useMemo(
    () => new Map<string, RingNodeInfo>(rows.filter((r) => r.dc === dc.name).map((r) => [r.address, {
      state: r.state, rack: r.rack, loadBytes: r.loadBytes, ownershipPct: r.owns, effectiveOwnershipPct: r.effective, vnodes: r.vnodes,
    }])),
    [rows, dc.name],
  );
  const build = useCallback((theme: ChartTheme) => buildRingOption(dc.name, ranges, styles, info, theme), [dc.name, ranges, styles, info]);
  const down = [...info.values()].filter((i) => !i.state.startsWith("U")).length;
  const label = `Token ring of ${dc.name}: ${info.size} nodes, ${ranges.length} token ranges${down ? `, ${down} not up` : ""}. `
    + [...info].map(([a, i]) => `${a} owns ${fmtPct(i.ownershipPct) ?? "n/a"}`).join("; ");
  return (
    <div className="panel mon-ring">
      <EChart build={build} dark={props.dark} label={label} testId={`monitoring-ring-${dc.name}`} height={280} onClick={(a) => a && props.onNode(a)} />
    </div>
  );
}
