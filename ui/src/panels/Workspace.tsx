import { useCallback, useEffect, useState } from "react";
import { api } from "../lib/api";
import type { ClusterInfo, ConnectionConfig } from "../lib/types";
import { errorText } from "../components/feedback";
import { OverviewPanel } from "./OverviewPanel";
import { QueryPanel } from "./QueryPanel";
import { SchemaPanel } from "./SchemaPanel";
import { RolesPanel } from "./RolesPanel";
import { HistoryPanel } from "./HistoryPanel";
import { MonitoringPanel } from "./monitoring/MonitoringPanel";
import { OperationsPanel } from "./ops/OperationsPanel";
import { DiagnosticsPanel } from "./diag/DiagnosticsPanel";
import { GcLogPanel } from "./gclog/GcLogPanel";
import { ConfigPanel } from "./config/ConfigPanel";
import { BackupPanel } from "./backup/BackupPanel";
import { BulkPanel } from "./bulk/BulkPanel";

type Tab = "overview" | "monitoring" | "query" | "schema" | "roles" | "history"
  | "operations" | "diagnostics" | "gclogs" | "config" | "backups" | "bulk";

/** One open cluster: its tabs share the connection and cluster info (CON-11). */
export function Workspace(props: { conn: ConnectionConfig; dark: boolean; onConnected: (id: string, ok: boolean) => void }) {
  const [tab, setTab] = useState<Tab>("query");
  const [info, setInfo] = useState<ClusterInfo | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [openText, setOpenText] = useState<{ text: string; seq: number } | null>(null);
  const { onConnected } = props;

  const connect = useCallback(() => {
    setError(null);
    api.connect(props.conn.id!)
      .then((i) => { setInfo(i); onConnected(props.conn.id!, true); })
      .catch((e) => { setError(errorText(e)); onConnected(props.conn.id!, false); });
  }, [props.conn.id, onConnected]);
  useEffect(connect, [connect]);

  // Keep topology current: nodes that join, leave or go down after connecting show up without a manual refresh.
  useEffect(() => {
    if (!info) return;
    const t = setInterval(() => {
      if (document.visibilityState === "visible") api.clusterInfo(props.conn.id!).then(setInfo).catch(() => undefined);
    }, 15000);
    return () => clearInterval(t);
  }, [info !== null, props.conn.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const openInEditor = (text: string) => {
    setOpenText((o) => ({ text, seq: (o?.seq ?? 0) + 1 }));
    setTab("query");
  };

  const tabs: [Tab, string][] = [
    ["overview", "Overview"], ["monitoring", "Monitoring"], ["query", "Query"], ["schema", "Schema"], ["roles", "Users & roles"], ["operations", "Operations"], ["diagnostics", "Diagnostics"], ["gclogs", "GC logs"], ["config", "Config"], ["backups", "Backups"], ["bulk", "Bulk"], ["history", "History"],
  ];

  return (
    <div style={{ display: "flex", flexDirection: "column", height: "100%", minHeight: 0 }}>
      {props.conn.environment === "PROD" && (
        <div className="prod-banner" data-testid="prod-banner">PRODUCTION — {props.conn.name}: every change needs the connection name typed to confirm</div>
      )}
      {props.conn.readOnly && <div className="ro-banner">🔒 Read-only connection: writes, DDL and operations are blocked</div>}
      <div className="tabs">
        {tabs.map(([k, label]) => (
          <button key={k} className={"tab" + (tab === k ? " active" : "")} onClick={() => setTab(k)}>{label}</button>
        ))}
      </div>
      <div style={{ flex: 1, minHeight: 0 }}>
        {error ? (
          <div className="pad">
            <div className="notice error" data-testid="connect-error">{error}</div>
            <button className="btn" onClick={connect}>Retry</button>
          </div>
        ) : !info ? (
          <div className="empty">Connecting to {props.conn.name}…</div>
        ) : (
          <>
            <div style={{ display: tab === "overview" ? "block" : "none", height: "100%" }}>
              <OverviewPanel conn={props.conn} info={info} onRefresh={() => api.clusterInfo(props.conn.id!).then(setInfo).catch(() => connect())} />
            </div>
            <div style={{ display: tab === "query" ? "block" : "none", height: "100%" }}>
              <QueryPanel conn={props.conn} info={info} dark={props.dark} openText={openText} />
            </div>
            {tab === "monitoring" && <MonitoringPanel conn={props.conn} info={info} dark={props.dark} />}
            {tab === "schema" && <SchemaPanel conn={props.conn} onOpenInEditor={openInEditor} />}
            {tab === "roles" && <RolesPanel conn={props.conn} />}
            {tab === "operations" && <OperationsPanel conn={props.conn} info={info} dark={props.dark} />}
            {tab === "diagnostics" && <DiagnosticsPanel conn={props.conn} info={info} dark={props.dark} />}
            {tab === "gclogs" && <GcLogPanel conn={props.conn} info={info} dark={props.dark} />}
            {tab === "config" && <ConfigPanel conn={props.conn} info={info} dark={props.dark} />}
            {tab === "backups" && <BackupPanel conn={props.conn} info={info} dark={props.dark} />}
            {tab === "bulk" && <BulkPanel conn={props.conn} info={info} dark={props.dark} />}
            {tab === "history" && <HistoryPanel conn={props.conn} onOpenInEditor={openInEditor} />}
          </>
        )}
      </div>
    </div>
  );
}
