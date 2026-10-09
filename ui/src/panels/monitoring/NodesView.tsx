import type { Alert, NodeSnapshot } from "../../lib/monitoringTypes";
import { fmtBytes, fmtCount, fmtDuration, fmtMicros, fmtPct, fmtRate, sum } from "./format";
import { imbalancedNodes, levelRank, stateCode, worstLevel } from "./health";
import type { NodeStyle } from "./palette";
import { LevelTag, SortTable, UsageBar, Val, type Col } from "./common";

const droppedTotal = (n: NodeSnapshot) => (n.dropped ? sum(Object.values(n.dropped)) : null);
const heapPct = (n: NodeSnapshot) => (n.heapUsedBytes !== null && n.heapMaxBytes ? (100 * n.heapUsedBytes) / n.heapMaxBytes : null);

/** One row per node (MON-12); click a node for its detail drawer. */
export function NodesView(props: { nodes: NodeSnapshot[]; alerts: Alert[]; styles: Map<string, NodeStyle>; onNode: (address: string) => void }) {
  const imbalanced = imbalancedNodes(props.nodes);
  const alertsOf = (n: NodeSnapshot) => props.alerts.filter((a) => a.node === n.address);
  const nodeLevel = (n: NodeSnapshot) => worstLevel(alertsOf(n).map((a) => a.level));
  const cols: Col<NodeSnapshot>[] = [
    { key: "dc", label: "DC", sort: (n) => n.datacenter, render: (n) => <Val v={n.datacenter} /> },
    { key: "rack", label: "Rack", sort: (n) => n.rack, render: (n) => <Val v={n.rack} /> },
    {
      key: "address", label: "Address", sort: (n) => n.address,
      render: (n) => (
        <button className="btn link mono mon-node-link" onClick={(e) => { e.stopPropagation(); props.onNode(n.address); }} aria-label={`Open details for ${n.address}`}>
          <span className="mon-swatch" style={{ background: props.styles.get(n.address)?.color }} aria-hidden="true" />
          {n.address}
        </button>
      ),
    },
    {
      key: "health", label: "Health", sort: (n) => -levelRank(nodeLevel(n)),
      render: (n) => {
        const a = alertsOf(n);
        return <span title={a.map((x) => x.message).join("\n") || "No alerts"}><LevelTag level={nodeLevel(n)} />{a.length > 1 ? ` ×${a.length}` : ""}</span>;
      },
    },
    {
      key: "state", label: "State", sort: (n) => stateCode(n.state),
      render: (n) => <span className={"status " + (stateCode(n.state).startsWith("U") ? "UP" : "DOWN")}>{stateCode(n.state)}</span>,
    },
    { key: "version", label: "Version", sort: (n) => n.cassandraVersion, render: (n) => <Val v={n.cassandraVersion} /> },
    { key: "java", label: "Java", sort: (n) => n.javaVersion, render: (n) => <Val v={n.javaVersion} title={n.javaVendor ?? undefined} /> },
    { key: "uptime", label: "Uptime", num: true, sort: (n) => n.uptimeSec, render: (n) => <Val v={fmtDuration(n.uptimeSec)} /> },
    {
      key: "load", label: "Load", num: true, sort: (n) => n.loadBytes,
      render: (n) => imbalanced.has(n.address)
        ? <span className="mon-warn-text" title={`${imbalanced.get(n.address)!.toFixed(2)}× the DC average`}>▲ {fmtBytes(n.loadBytes)}</span>
        : <Val v={fmtBytes(n.loadBytes)} />,
    },
    { key: "tokens", label: "Tokens", num: true, sort: (n) => n.tokens, render: (n) => <Val v={fmtCount(n.tokens)} /> },
    { key: "heap", label: "Heap used / max", sort: heapPct, render: (n) => <UsageBar used={n.heapUsedBytes} total={n.heapMaxBytes} fmt={fmtBytes} label={`Heap of ${n.address}`} /> },
    { key: "gc", label: "GC %", title: "GC time as % of wall clock between polls", num: true, sort: (n) => n.gcTimePct, render: (n) => <Val v={fmtPct(n.gcTimePct)} /> },
    { key: "cpu", label: "CPU %", num: true, sort: (n) => n.cpuProcessPct, render: (n) => <Val v={fmtPct(n.cpuProcessPct)} /> },
    {
      key: "pc", label: "Pending compactions", num: true, sort: (n) => n.pendingCompactions,
      render: (n) => <span className={(n.pendingCompactions ?? 0) > 100 ? "mon-warn-text" : undefined}><Val v={fmtCount(n.pendingCompactions)} /></span>,
    },
    { key: "hints", label: "Hints", title: "Hints in progress", num: true, sort: (n) => n.hintsInProgress, render: (n) => <Val v={fmtCount(n.hintsInProgress)} /> },
    { key: "dropped", label: "Dropped", num: true, sort: droppedTotal, render: (n) => <Val v={fmtCount(droppedTotal(n))} /> },
    { key: "rr", label: "Reads", num: true, sort: (n) => n.clientRequests?.read?.ratePerSec, render: (n) => <Val v={fmtRate(n.clientRequests?.read?.ratePerSec)} /> },
    { key: "wr", label: "Writes", num: true, sort: (n) => n.clientRequests?.write?.ratePerSec, render: (n) => <Val v={fmtRate(n.clientRequests?.write?.ratePerSec)} /> },
    { key: "rp99", label: "Read p99", num: true, sort: (n) => n.clientRequests?.read?.p99Micros, render: (n) => <Val v={fmtMicros(n.clientRequests?.read?.p99Micros)} /> },
    { key: "wp99", label: "Write p99", num: true, sort: (n) => n.clientRequests?.write?.p99Micros, render: (n) => <Val v={fmtMicros(n.clientRequests?.write?.p99Micros)} /> },
    { key: "route", label: "Route", sort: (n) => n.route, render: (n) => <span className="mono mon-ellipsis" title={n.route ?? undefined}><Val v={n.route} /></span> },
    { key: "error", label: "Error", sort: (n) => n.error, render: (n) => (n.error ? <span className="error-text mon-ellipsis" title={n.error}>{n.error}</span> : <span className="muted">—</span>) },
  ];

  if (!props.nodes.length) return <div className="empty">The cluster reported no nodes.</div>;
  return (
    <div className="pad">
      <SortTable
        rows={props.nodes}
        cols={cols}
        rowKey={(n) => n.hostId ?? n.address}
        label="Nodes"
        testId="monitoring-nodes-table"
        initial={{ key: "dc", dir: "asc" }}
        onRowClick={(n) => props.onNode(n.address)}
        rowClass={(n) => (n.error ? "mon-row-error" : undefined)}
      />
      {imbalanced.size > 0 && <div className="muted mon-footnote">▲ load more than 1.5 × its datacenter average</div>}
    </div>
  );
}
