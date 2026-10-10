// Mirrors the engine's JSON records (engine/src/main/java/.../model, cql, schema, security).

export type Environment = "DEV" | "TEST" | "STAGING" | "PROD";
export type JmxMethod = "SSH_TUNNEL" | "DIRECT" | "EXPORTER" | "SIDECAR" | "NONE";
export type SshAuth = "AGENT" | "KEY" | "PASSWORD";

export interface Tls {
  enabled: boolean;
  truststorePath?: string | null;
  truststoreType?: string | null;
  keystorePath?: string | null;
  keystoreType?: string | null;
  hostnameVerification: boolean;
}

export interface Jmx {
  method: JmxMethod;
  port: number;
  username?: string | null;
  ssl: boolean;
  exporterPort: number;
  sidecarPort: number;
}

export interface Ssh {
  username?: string | null;
  port: number;
  auth: SshAuth;
  keyPath?: string | null;
  jumpHost?: string | null;
  jumpPort: number;
  jumpUser?: string | null;
  strictHostKeyChecking: boolean;
  knownHostsPath?: string | null;
  /** Optional: reach the first SSH hop through an HTTP CONNECT or SOCKS5 proxy (NFR-NET). */
  proxy?: SshProxy | null;
}

export interface SshProxy {
  type: "HTTP" | "SOCKS5";
  host: string;
  port?: number | null;
  username?: string | null;
}

export interface ConnectionConfig {
  id?: string | null;
  folderId?: string | null;
  name: string;
  environment: Environment;
  color?: string | null;
  readOnly: boolean;
  contactPoints: string[];
  localDatacenter?: string | null;
  username?: string | null;
  tls: Tls;
  protocolVersion: string;
  defaultConsistency: string;
  requestTimeoutMs: number;
  pageSize: number;
  jmx: Jmx;
  ssh: Ssh;
  tags: string[];
  notes?: string | null;
  secretsSet?: Record<string, boolean>;
}

export type SecretName =
  | "password"
  | "jmxPassword"
  | "sshPassword"
  | "sshPassphrase"
  | "truststorePassword"
  | "keystorePassword"
  | "sshProxyPassword";

export interface Folder {
  id: string;
  parentId?: string | null;
  name: string;
  position: number;
}

export interface TestResult {
  ok: boolean;
  error?: string;
  clusterName?: string;
  version?: string;
  nodes: number;
  protocolVersion?: string;
  elapsedMs: number;
}

export interface NodeInfo {
  hostId?: string;
  address: string;
  cqlPort: number;
  datacenter?: string;
  rack?: string;
  version?: string;
  state: string;
  tokens: number;
  schemaVersion?: string;
  openConnections: number;
}

export interface ClusterInfo {
  name?: string;
  partitioner?: string;
  datacenters: string[];
  nodes: NodeInfo[];
  schemaAgreement: boolean;
  versions: string[];
  protocolVersion: string;
}

export interface QueryRequest {
  cql: string;
  keyspace?: string | null;
  node?: string | null;
  consistency?: string | null;
  serialConsistency?: string | null;
  pageSize?: number | null;
  pagingState?: string | null;
  tracing?: boolean;
  timeoutMs?: number | null;
  stopOnError?: boolean;
  maxRows?: number | null;
  confirmed?: boolean;
  confirmName?: string | null;
}

export interface Column {
  name: string;
  type: string;
  keyspace?: string;
  table?: string;
}

export interface TraceEvent {
  source?: string;
  elapsedMicros: number;
  thread?: string;
  activity: string;
}

export interface Trace {
  traceId: string;
  coordinator?: string;
  requestType?: string;
  durationMicros: number;
  parameters: Record<string, string>;
  events: TraceEvent[];
}

export type Cell = string | number | boolean | null;

export interface StatementResult {
  index: number;
  line: number;
  statement: string;
  kind: string;
  status: "ok" | "error" | "skipped";
  error?: string;
  columns: Column[];
  rows: Cell[][];
  rowCount: number;
  hasMore: boolean;
  pagingState?: string;
  serverWarnings: string[];
  clientWarnings: string[];
  coordinator?: string;
  durationMs: number;
  trace?: Trace;
  settings?: Record<string, string>;
  message?: string;
  /** Keyspace and consistency the statement ran with (for "next page"). */
  keyspace?: string;
  consistency?: string;
}

export interface ScriptResult {
  results: StatementResult[];
  keyspace?: string;
  consistency: string;
  tracing: boolean;
}

export interface HistoryEntry {
  id: number;
  executedAt: string;
  statement: string;
  keyspace?: string;
  node?: string;
  durationMs?: number;
  rowCount?: number;
  error?: string;
}

export interface AuditEntry {
  id: number;
  at: string;
  actor: string;
  connectionId?: string;
  connectionName?: string;
  environment?: string;
  node?: string;
  category: string;
  action: string;
  detail?: string;
  outcome: "SUCCESS" | "FAILED" | "BLOCKED";
  error?: string;
}

export interface ObjectRef {
  name: string;
  kind: string;
}

export interface KeyspaceNode {
  name: string;
  system: boolean;
  tables: ObjectRef[];
  views: ObjectRef[];
  indexes: ObjectRef[];
  types: ObjectRef[];
  functions: ObjectRef[];
  aggregates: ObjectRef[];
}

export interface SchemaTree {
  clusterName?: string;
  keyspaces: KeyspaceNode[];
}

export interface KeyspaceDetails {
  name: string;
  system: boolean;
  replication: Record<string, string>;
  durableWrites: boolean;
  virtual: boolean;
  tables: string[];
  views: string[];
  types: string[];
  ddl: string;
}

export interface ColumnInfo {
  name: string;
  type: string;
  kind: "partition_key" | "clustering" | "static" | "regular";
  position: number;
  clusteringOrder?: string;
}

export interface IndexInfo {
  name: string;
  kind: string;
  target: string;
  className?: string;
  options: Record<string, string>;
  ddl: string;
}

export interface TableDetails {
  keyspace: string;
  name: string;
  virtual: boolean;
  id?: string;
  columns: ColumnInfo[];
  partitionKey: string[];
  clusteringColumns: string[];
  options: Record<string, unknown>;
  indexes: IndexInfo[];
  views: string[];
  ddl: string;
}

export interface Role {
  name: string;
  login: boolean;
  superuser: boolean;
  memberOf: string[];
}

export interface Permission {
  role: string;
  resource: string;
  permission: string;
}

export interface RolesView {
  roles: Role[];
  rolesError?: string;
  permissions: Permission[];
  permissionsError?: string;
  warnings: string[];
}

export interface EngineInfo {
  version: string;
  secretStore: string;
  actor: string;
  dbVersion: number;
}

/** Body of a 428 from the ActionGuard. */
export interface ConfirmDetails {
  connectionName: string;
  environment: Environment;
  summary: string;
  preview: string[];
  warnings: string[];
  destructive: boolean;
  requireTypedName: boolean;
}

export interface SavedScript {
  id: string;
  folder: string;
  name: string;
  content?: string;
  updatedAt: string;
}
