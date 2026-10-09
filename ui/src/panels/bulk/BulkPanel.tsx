import type { ClusterInfo, ConnectionConfig } from "../../lib/types";

// Phase 3 Track 6: unload and load CSV/JSON (BLK-1/2).
export function BulkPanel(props: { conn: ConnectionConfig; info: ClusterInfo; dark: boolean }) {
  void props;
  return <div className="empty" data-testid="bulk-panel">Bulk: coming in Phase 3</div>;
}
