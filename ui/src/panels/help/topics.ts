// Which guide section each panel's "?" button opens. Tests check that every target exists.

export interface HelpTarget {
  /** Path under docs/, e.g. "guide/user-guide.md". */
  path: string;
  /** Heading anchor in that document, or null for the top. */
  anchor: string | null;
  /** Names the topic in the button's accessible label: "Help: <label>". */
  label: string;
}

const GUIDE = "guide/user-guide.md";

export const HELP_TOPICS = {
  guide: { path: GUIDE, anchor: null, label: "User guide" },
  overview: { path: GUIDE, anchor: "overview", label: "Overview" },
  monitoring: { path: GUIDE, anchor: "monitoring", label: "Monitoring" },
  query: { path: GUIDE, anchor: "cql-editor", label: "CQL editor" },
  schema: { path: GUIDE, anchor: "schema", label: "Schema" },
  roles: { path: GUIDE, anchor: "users-and-roles", label: "Users and roles" },
  "ops-views": { path: GUIDE, anchor: "operations", label: "Operations" },
  "ops-maintenance": { path: "guide/runbooks/README.md", anchor: null, label: "Maintenance runbooks" },
  "ops-repair": { path: "guide/runbooks/repair.md", anchor: null, label: "Repair runbook" },
  "ops-snapshots": { path: "guide/runbooks/snapshots.md", anchor: null, label: "Snapshots runbook" },
  "ops-jobs": { path: GUIDE, anchor: "jobs", label: "Jobs" },
  diagnostics: { path: GUIDE, anchor: "diagnostics", label: "Diagnostics" },
  gclog: { path: GUIDE, anchor: "gc-logs", label: "GC logs" },
  config: { path: GUIDE, anchor: "config-and-drift", label: "Config and drift" },
  backups: { path: GUIDE, anchor: "backups", label: "Backups" },
  bulk: { path: GUIDE, anchor: "bulk-unload-and-load", label: "Bulk unload and load" },
  history: { path: GUIDE, anchor: "history-and-saved-scripts", label: "History" },
  audit: { path: GUIDE, anchor: "audit-log", label: "Audit log" },
} satisfies Record<string, HelpTarget>;

export type HelpTopic = keyof typeof HELP_TOPICS;
