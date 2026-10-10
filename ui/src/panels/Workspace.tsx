import { useCallback, useEffect, useState } from "react";
import { api } from "../lib/api";
import type { ClusterInfo, ConnectionConfig } from "../lib/types";
import { errorText } from "../components/feedback";
import { ViewBoundary } from "../components/ViewBoundary";
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

export type Tab = "overview" | "monitoring" | "query" | "schema" | "roles" | "history"
  | "operations" | "diagnostics" | "gclogs" | "config" | "backups" | "bulk";

export const WORKSPACE_TABS: [Tab, string][] = [
  ["overview", "Overview"], ["monitoring", "Monitoring"], ["query", "Query"], ["schema", "Schema"], ["roles", "Users & roles"], ["operations", "Operations"], ["diagnostics", "Diagnostics"], ["gclogs", "GC logs"], ["config", "Config"], ["backups", "Backups"], ["bulk", "Bulk"], ["history", "History"],
];

function isTab(t: string | undefined): t is Tab {
  return !!t && WORKSPACE_TABS.some(([k]) => k === t);
}

/**
 * One open cluster: its tabs share the connection and cluster info (CON-11). The active tab is
 * remembered across launches (NFR-UX); {@code autoConnect} false (a restored PROD tab that was not
 * connected when Studio closed) waits for an explicit Connect.
 */
export function Workspace(props: {
  conn: ConnectionConfig; dark: boolean; onConnected: (id: string, ok: boolean) => void;
  initialTab?: string; onTabChange?: (id: string, tab: Tab) => void; autoConnect?: boolean;
}) {
  const [tab, setTabState] = useState<Tab>(isTab(props.initialTab) ? props.initialTab : "query");
  const [info, setInfo] = useState<ClusterInfo | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [waiting, setWaiting] = useState(props.autoConnect === false);
  const [openText, setOpenText] = useState<{ text: string; seq: number } | null>(null);
  const { onConnected, onTabChange } = props;
  const setTab = useCallback((t: Tab) => {
    setTabState(t);
    onTabChange?.(props.conn.id!, t);
  }, [onTabChange, props.conn.id]);

  const connect = useCallback(() => {
    setError(null);
    setWaiting(false);
    api.connect(props.conn.id!)
      .then((i) => { setInfo(i); onConnected(props.conn.id!, true); })
      .catch((e) => { setError(errorText(e)); onConnected(props.conn.id!, false); });
  }, [props.conn.id, onConnected]);
  const autoConnect = props.autoConnect !== false;
  useEffect(() => { if (autoConnect) connect(); }, [connect]); // eslint-disable-line react-hooks/exhaustive-deps

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

  const tabs = WORKSPACE_TABS;

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
        {waiting ? (
          <div className="pad">
            <div className="notice" data-testid="restore-connect">
              {props.conn.name} is a production cluster and was not connected when Studio closed, so it was not reconnected automatically.
            </div>
            <button className="btn primary" onClick={connect}>Connect</button>
          </div>
        ) : error ? (
          <div className="pad">
            <div className="notice error" data-testid="connect-error">{error}</div>
            <button className="btn" onClick={connect}>Retry</button>
          </div>
        ) : !info ? (
          <div className="empty">Connecting to {props.conn.name}…</div>
        ) : (
          <>
            <div style={{ display: tab === "overview" ? "block" : "none", height: "100%" }}>
              <ViewBoundary name={`${props.conn.name} · Overview`}>
                <OverviewPanel conn={props.conn} info={info} onRefresh={() => api.clusterInfo(props.conn.id!).then(setInfo).catch(() => connect())} />
              </ViewBoundary>
            </div>
            <div style={{ display: tab === "query" ? "block" : "none", height: "100%" }}>
              <ViewBoundary name={`${props.conn.name} · Query`}>
                <QueryPanel conn={props.conn} info={info} dark={props.dark} openText={openText} />
              </ViewBoundary>
            </div>
            {/* One failing tab shows its error in place; the tab bar and the other tabs keep working. */}
            {tab !== "overview" && tab !== "query" && (
              <ViewBoundary key={tab} name={`${props.conn.name} · ${tab}`}>
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
              </ViewBoundary>
            )}
          </>
        )}
      </div>
    </div>
  );
}
