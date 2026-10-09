import type { ClusterInfo, ConnectionConfig } from "../../lib/types";

// Phase 3 Track 5: backup providers, catalogue, run now (BAK-1..3).
export function BackupPanel(props: { conn: ConnectionConfig; info: ClusterInfo; dark: boolean }) {
  void props;
  return <div className="empty" data-testid="backups-panel">Backups: coming in Phase 3</div>;
}
