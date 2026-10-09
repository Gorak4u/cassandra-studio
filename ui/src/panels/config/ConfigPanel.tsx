import type { ClusterInfo, ConnectionConfig } from "../../lib/types";

// Phase 3 Track 4: effective config per node and drift (CFG-1/2).
export function ConfigPanel(props: { conn: ConnectionConfig; info: ClusterInfo; dark: boolean }) {
  void props;
  return <div className="empty" data-testid="config-panel">Config: coming in Phase 3</div>;
}
