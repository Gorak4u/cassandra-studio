import type { ClusterInfo, ConnectionConfig } from "../lib/types";

/** Cluster details and node list from the driver (MON-10, MON-11, CON-5). Live JMX dashboards arrive in Phase 2. */
export function OverviewPanel(props: { conn: ConnectionConfig; info: ClusterInfo | null; onRefresh: () => void }) {
  const i = props.info;
  if (!i) return <div className="empty">Connecting…</div>;
  const up = i.nodes.filter((n) => n.state === "UP").length;
  const down = i.nodes.length - up;
  const health = down > 0 || !i.schemaAgreement ? (down > 0 ? "RED" : "YELLOW") : "GREEN";
  return (
    <div className="pad stack scroll" style={{ height: "100%" }}>
      <div className="row">
        <h3 style={{ margin: 0 }}>{i.name ?? props.conn.name}</h3>
        <span className={"status " + (health === "GREEN" ? "UP" : health === "RED" ? "DOWN" : "UNKNOWN")} data-testid="health">{health}</span>
        <span className="spacer" />
        <button className="btn small" onClick={props.onRefresh}>⟳ Refresh</button>
      </div>
      <div className="cards">
        <div className="card"><div className="label">Nodes up</div><div className="value">{up} / {i.nodes.length}</div></div>
        <div className="card"><div className="label">Datacenters</div><div className="value">{i.datacenters.length}</div><div className="muted">{i.datacenters.join(", ")}</div></div>
        <div className="card"><div className="label">Schema agreement</div><div className="value">{i.schemaAgreement ? "Yes" : "No"}</div></div>
        <div className="card"><div className="label">Cassandra versions</div><div className="value" style={{ fontSize: 15 }}>{i.versions.join(", ") || "?"}</div></div>
        <div className="card"><div className="label">Partitioner</div><div className="value" style={{ fontSize: 13 }}>{i.partitioner?.replace("org.apache.cassandra.dht.", "")}</div></div>
        <div className="card"><div className="label">Protocol</div><div className="value">{i.protocolVersion}</div></div>
      </div>
      {i.versions.length > 1 && <div className="notice warn">Mixed Cassandra versions in one cluster: is an upgrade in progress?</div>}
      {!i.schemaAgreement && <div className="notice warn">Nodes disagree on the schema version. Wait, or check for unreachable nodes before running more DDL.</div>}
      <div className="panel">
        <h3>Nodes</h3>
        <table className="data">
          <thead><tr><th>State</th><th>Address</th><th>DC</th><th>Rack</th><th>Version</th><th>Tokens</th><th>Host ID</th><th>Schema</th></tr></thead>
          <tbody>
            {i.nodes.map((n) => (
              <tr key={n.hostId ?? n.address}>
                <td><span className={"status " + n.state}>{n.state}</span></td>
                <td className="mono">{n.address}:{n.cqlPort}</td>
                <td>{n.datacenter}</td><td>{n.rack}</td><td>{n.version}</td><td>{n.tokens}</td>
                <td className="mono">{n.hostId}</td>
                <td className="mono">{n.schemaVersion?.slice(0, 8)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
