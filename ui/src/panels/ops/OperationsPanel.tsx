import type { ClusterInfo, ConnectionConfig } from "../../lib/types";

// Phase 3 Track 1: nodetool views, maintenance, repair, snapshots (OPS-1..4).
export function OperationsPanel(props: { conn: ConnectionConfig; info: ClusterInfo; dark: boolean }) {
  void props;
  return <div className="empty" data-testid="operations-panel">Operations: coming in Phase 3</div>;
}
