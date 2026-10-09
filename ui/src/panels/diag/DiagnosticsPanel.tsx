import type { ClusterInfo, ConnectionConfig } from "../../lib/types";

// Phase 3 Track 2: thread dumps, top threads, hot and large partitions, tombstones (JVM-1/2, PRF-1/2).
export function DiagnosticsPanel(props: { conn: ConnectionConfig; info: ClusterInfo; dark: boolean }) {
  void props;
  return <div className="empty" data-testid="diagnostics-panel">Diagnostics: coming in Phase 3</div>;
}
