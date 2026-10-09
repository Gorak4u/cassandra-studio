import { useEffect, useState } from "react";
import type { MonitoringClient } from "../../lib/monitoringApi";
import type { TableMetrics } from "../../lib/monitoringTypes";
import { fmtBytes, fmtCount, fmtMicros, fmtNumber, fmtRatio } from "./format";
import { Empty, ErrorState, Loading, SortTable, Val, type Col } from "./common";

export const LARGE_PARTITION_BYTES = 100 * 1024 * 1024;
/** Cassandra's default tombstone_warn_threshold. */
export const TOMBSTONE_WARN = 1000;

function Flagged(props: { flag: boolean; v: string | null; why: string }) {
  return props.flag ? <span className="mon-warn-text" title={props.why}>▲ {props.v}</span> : <Val v={props.v} />;
}

const cols: Col<TableMetrics>[] = [
  { key: "ks", label: "Keyspace", sort: (t) => t.keyspace, render: (t) => t.keyspace },
  { key: "table", label: "Table", sort: (t) => t.table, render: (t) => <b>{t.table}</b> },
  { key: "reads", label: "Reads", num: true, sort: (t) => t.readCount, render: (t) => <Val v={fmtCount(t.readCount)} /> },
  { key: "writes", label: "Writes", num: true, sort: (t) => t.writeCount, render: (t) => <Val v={fmtCount(t.writeCount)} /> },
  { key: "rp99", label: "Read p99", num: true, sort: (t) => t.readLatencyP99Micros, render: (t) => <Val v={fmtMicros(t.readLatencyP99Micros)} /> },
  { key: "wp99", label: "Write p99", num: true, sort: (t) => t.writeLatencyP99Micros, render: (t) => <Val v={fmtMicros(t.writeLatencyP99Micros)} /> },
  { key: "live", label: "Live disk", num: true, sort: (t) => t.liveDiskSpaceBytes, render: (t) => <Val v={fmtBytes(t.liveDiskSpaceBytes)} /> },
  { key: "total", label: "Total disk", num: true, sort: (t) => t.totalDiskSpaceBytes, render: (t) => <Val v={fmtBytes(t.totalDiskSpaceBytes)} /> },
  { key: "sst", label: "SSTables", num: true, sort: (t) => t.sstableCount, render: (t) => <Val v={fmtCount(t.sstableCount)} /> },
  { key: "meanp", label: "Mean partition", num: true, sort: (t) => t.meanPartitionSizeBytes, render: (t) => <Val v={fmtBytes(t.meanPartitionSizeBytes)} /> },
  {
    key: "maxp", label: "Max partition", num: true, sort: (t) => t.maxPartitionSizeBytes,
    render: (t) => <Flagged flag={(t.maxPartitionSizeBytes ?? 0) > LARGE_PARTITION_BYTES} v={fmtBytes(t.maxPartitionSizeBytes)} why="Large partition: over 100 MiB" />,
  },
  {
    key: "tomb", label: "Tombstones / read", title: "p99 tombstones scanned per read", num: true, sort: (t) => t.tombstonesPerReadP99,
    render: (t) => <Flagged flag={(t.tombstonesPerReadP99 ?? 0) > TOMBSTONE_WARN} v={fmtNumber(t.tombstonesPerReadP99, 1)} why="Over the tombstone warning threshold (1000)" />,
  },
  { key: "sstr", label: "SSTables / read", title: "p99 SSTables touched per read", num: true, sort: (t) => t.sstablesPerReadP99, render: (t) => <Val v={fmtNumber(t.sstablesPerReadP99, 1)} /> },
  { key: "bf", label: "Bloom FP ratio", num: true, sort: (t) => t.bloomFilterFalseRatio, render: (t) => <Val v={fmtRatio(t.bloomFilterFalseRatio, 2)} /> },
  { key: "pc", label: "Pending compactions", num: true, sort: (t) => t.pendingCompactions, render: (t) => <Val v={fmtCount(t.pendingCompactions)} /> },
  { key: "kc", label: "Key cache hit", num: true, sort: (t) => t.keyCacheHitRate, render: (t) => <Val v={fmtRatio(t.keyCacheHitRate)} /> },
];

/** Keyspace and table metrics (MON-18), read on request. */
export function TablesView(props: { client: MonitoringClient }) {
  const [rows, setRows] = useState<TableMetrics[] | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [keyspace, setKeyspace] = useState("");
  const [reload, setReload] = useState(0);
  const { client } = props;

  useEffect(() => {
    let alive = true;
    setError(null);
    client.tables().then((t) => alive && setRows(t)).catch((e) => alive && setError(e));
    return () => { alive = false; };
  }, [client, reload]);

  if (error) return <ErrorState error={error} onRetry={() => setReload((r) => r + 1)} />;
  if (!rows) return <Loading what="table metrics" />;
  const keyspaces = [...new Set(rows.map((r) => r.keyspace))].sort();
  const shown = keyspace ? rows.filter((r) => r.keyspace === keyspace) : rows;
  return (
    <div className="pad stack">
      <div className="row mon-toolbar">
        <label className="row">
          <span className="muted">Keyspace</span>
          <select value={keyspace} onChange={(e) => setKeyspace(e.target.value)} aria-label="Filter by keyspace">
            <option value="">All non-system keyspaces</option>
            {keyspaces.map((k) => <option key={k} value={k}>{k}</option>)}
          </select>
        </label>
        <span className="muted">{shown.length} table{shown.length === 1 ? "" : "s"}</span>
        <span className="spacer" />
        <button className="btn small" onClick={() => setReload((r) => r + 1)}>⟳ Refresh</button>
      </div>
      {shown.length ? (
        <SortTable rows={shown} cols={cols} rowKey={(t) => t.keyspace + "." + t.table} label="Table metrics" testId="monitoring-tables-grid" initial={{ key: "reads", dir: "desc" }} />
      ) : (
        <Empty>No user tables found.</Empty>
      )}
      <div className="muted mon-footnote">▲ max partition over 100 MiB, or more than 1000 tombstones per read (p99).</div>
    </div>
  );
}
