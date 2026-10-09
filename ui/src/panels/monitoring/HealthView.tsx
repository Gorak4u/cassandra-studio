import type { Alert, ClusterSnapshot } from "../../lib/monitoringTypes";
import type { ClusterInfo } from "../../lib/types";
import { fmtBytes, fmtNumber, fmtPct, fmtSince, sum } from "./format";
import { countsByDc, levelRank } from "./health";
import { LevelTag, SortTable, Val, type Col } from "./common";

const PCT_RULES = new Set(["heap.high", "gc.pressure", "disk.usage"]);

function fmtRuleValue(rule: string, v: number | null | undefined): string | null {
  if (PCT_RULES.has(rule)) return fmtPct(v);
  if (rule === "load.imbalance") return v === null || v === undefined ? null : fmtNumber(v) + "×";
  return fmtNumber(v);
}

/** Cluster health (MON-10, MON-11) and active alerts (ALR-1). */
export function HealthView(props: { snapshot: ClusterSnapshot; info: ClusterInfo; onNode: (address: string) => void }) {
  const s = props.snapshot;
  const counts = countsByDc(s.nodes);
  const states = [...new Set(counts.flatMap((c) => Object.keys(c.byState)))].sort((a, b) => (a === "UN" ? -1 : b === "UN" ? 1 : a.localeCompare(b)));
  const versions = [...new Set(s.nodes.map((n) => n.cassandraVersion).filter(Boolean))];
  const javas = [...new Set(s.nodes.map((n) => n.javaVersion && `${n.javaVersion}${n.javaVendor ? " (" + n.javaVendor + ")" : ""}`).filter(Boolean))];
  const racks = new Set(s.nodes.map((n) => `${n.datacenter}/${n.rack}`));
  const alertCols: Col<Alert>[] = [
    { key: "level", label: "Level", sort: (a) => -levelRank(a.level), render: (a) => <LevelTag level={a.level} /> },
    { key: "rule", label: "Rule", sort: (a) => a.rule, render: (a) => <span className="mono">{a.rule}</span> },
    {
      key: "node", label: "Node", sort: (a) => a.node ?? null,
      render: (a) => (a.node ? <button className="btn link mono" onClick={() => props.onNode(a.node!)}>{a.node}</button> : <span className="muted">cluster</span>),
    },
    { key: "message", label: "Message", render: (a) => a.message },
    {
      key: "value", label: "Value / threshold", num: true, sort: (a) => a.value ?? null,
      render: (a) => a.value === null || a.value === undefined ? <Val v={null} /> : <>{fmtRuleValue(a.rule, a.value)} <span className="muted">/ {fmtRuleValue(a.rule, a.threshold) ?? "n/a"}</span></>,
    },
    { key: "since", label: "Since", sort: (a) => -a.sinceEpochMs, render: (a) => <span title={new Date(a.sinceEpochMs).toLocaleString()}>{fmtSince(a.sinceEpochMs, s.atEpochMs)}</span> },
  ];

  return (
    <div className="pad stack">
      <div className={`mon-health ${s.health.level}`} role="status" aria-live="polite">
        <LevelTag level={s.health.level} big testId="monitoring-health-badge" />
        <div className="mon-health-reasons">
          {s.health.reasons.length ? (
            <ul>{s.health.reasons.map((r, i) => <li key={i}>{r}</li>)}</ul>
          ) : (
            <span>No active alerts. All health rules pass.</span>
          )}
        </div>
      </div>

      <div className="cards">
        <div className="card">
          <div className="label">Schema agreement</div>
          <div className="value">{s.schemaAgreement ? "✔ Yes" : "✖ No"}</div>
          {!s.schemaAgreement && <div className="error-text">More than one schema version among UP nodes</div>}
        </div>
        <div className="card"><div className="label">Nodes / racks / DCs</div><div className="value">{s.nodes.length} / {racks.size} / {counts.length}</div></div>
        <div className="card"><div className="label">Total load</div><div className="value"><Val v={fmtBytes(sum(s.nodes.map((n) => n.loadBytes)))} /></div></div>
        <div className="card"><div className="label">Partitioner</div><div className="value mon-card-small">{props.info.partitioner?.replace("org.apache.cassandra.dht.", "") ?? "n/a"}</div></div>
        <div className="card">
          <div className="label">Cassandra versions</div>
          <div className="value mon-card-small">{versions.join(", ") || "n/a"}</div>
          {versions.length > 1 && <div className="mon-warn-text">Mixed versions: upgrade in progress?</div>}
        </div>
        <div className="card"><div className="label">Java</div><div className="value mon-card-small">{javas.join(", ") || "n/a"}</div></div>
      </div>

      <div className="panel">
        <h3>Nodes by state</h3>
        <table className="data mon-table" aria-label="Node counts per datacenter and state">
          <thead>
            <tr><th>Datacenter</th>{states.map((st) => <th key={st} className="num">{st}</th>)}<th className="num">JMX unreachable</th><th className="num">Total</th></tr>
          </thead>
          <tbody>
            {counts.map((c) => (
              <tr key={c.dc}>
                <td>{c.dc}</td>
                {states.map((st) => (
                  <td key={st} className={"num" + (st !== "UN" && c.byState[st] ? " mon-bad" : "")}>{c.byState[st] ?? 0}</td>
                ))}
                <td className={"num" + (c.unreachable ? " mon-warn-text" : "")}>{c.unreachable}</td>
                <td className="num">{c.total}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="panel">
        <h3>Active alerts ({s.alerts.length})</h3>
        {s.alerts.length ? (
          <SortTable rows={s.alerts} cols={alertCols} rowKey={(a) => a.id} label="Active alerts" testId="monitoring-alerts" initial={{ key: "level", dir: "asc" }} />
        ) : (
          <div className="muted" data-testid="monitoring-alerts">No active alerts.</div>
        )}
      </div>
    </div>
  );
}
