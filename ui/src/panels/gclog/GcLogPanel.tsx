import type { ClusterInfo, ConnectionConfig } from "../../lib/types";

// Phase 3 Track 3: GC log analysis (GCL-1..3, findings).
export function GcLogPanel(props: { conn: ConnectionConfig; info: ClusterInfo; dark: boolean }) {
  void props;
  return <div className="empty" data-testid="gclogs-panel">GC logs: coming in Phase 3</div>;
}
