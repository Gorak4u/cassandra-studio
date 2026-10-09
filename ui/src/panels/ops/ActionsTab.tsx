// OPS-2 maintenance and OPS-3 repair: forms that start a guarded job and follow it.
import { useState } from "react";
import type { KeyspaceNode } from "../../lib/types";
import type { Job } from "../../lib/jobsTypes";
import { useGuarded, useToast } from "../../components/feedback";
import { JobProgress } from "../../components/JobProgress";
import type { MaintenanceKind, OpsClient } from "./opsApi";
import { KeyspaceSelect, TableChecks } from "./pickers";

const KINDS: { id: MaintenanceKind; label: string; help: string }[] = [
  { id: "flush", label: "Flush", help: "Write memtables to SSTables." },
  { id: "compact", label: "Major compaction", help: "Compact all SSTables of the table(s) into one (or split by size)." },
  { id: "usercompact", label: "User-defined compaction", help: "Compact exactly the SSTable files you list." },
  { id: "cleanup", label: "Cleanup", help: "Drop data the node no longer owns (after adding nodes)." },
  { id: "scrub", label: "Scrub", help: "Rebuild SSTables, checking and fixing corruption." },
  { id: "upgradesstables", label: "Upgrade SSTables", help: "Rewrite SSTables to the current format." },
  { id: "garbagecollect", label: "Garbage collect", help: "Rewrite SSTables to remove deleted data." },
];

function Check(props: { label: string; checked: boolean; onChange: (v: boolean) => void; title?: string }) {
  return (
    <label className="ops-check" title={props.title}>
      <input type="checkbox" checked={props.checked} onChange={(e) => props.onChange(e.target.checked)} /> {props.label}
    </label>
  );
}

/** Starts a job through the guard (428 → confirm dialog → retry) and shows its progress. */
function useStart(onStarted?: (j: Job) => void) {
  const guarded = useGuarded();
  const toast = useToast();
  const [jobId, setJobId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const start = (run: (c: { confirmed?: boolean; confirmName?: string | null }) => Promise<Job>) => {
    setBusy(true);
    guarded(run).then((j) => {
      if (j) {
        setJobId(j.id);
        onStarted?.(j);
      }
    }).catch((e) => toast.error(e)).finally(() => setBusy(false));
  };
  return { jobId, busy, start };
}

export function MaintenanceTab(props: { client: OpsClient; nodes: string[]; keyspaces: KeyspaceNode[]; idPrefix: string; onStarted?: (j: Job) => void }) {
  const [kind, setKind] = useState<MaintenanceKind>("flush");
  const [keyspace, setKeyspace] = useState("");
  const [tables, setTables] = useState<string[]>([]);
  const [o, setO] = useState({ splitOutput: false, jobs: 0, disableSnapshot: false, skipCorrupted: false, noValidate: false,
    reinsertOverflowedTtl: false, includeAll: false, granularity: "ROW" as "ROW" | "CELL", continueOnError: false });
  const [files, setFiles] = useState("");
  const { jobId, busy, start } = useStart(props.onStarted);
  const set = <K extends keyof typeof o>(k: K, v: (typeof o)[K]) => setO((x) => ({ ...x, [k]: v }));
  const ks = props.keyspaces.find((k) => k.name === keyspace);
  const user = kind === "usercompact";
  const fileList = files.split(/[\n,]/).map((f) => f.trim()).filter(Boolean);
  const ready = props.nodes.length > 0 && (user ? fileList.length > 0 : !!keyspace);
  const usesJobs = kind === "cleanup" || kind === "scrub" || kind === "upgradesstables" || kind === "garbagecollect";

  const run = () => start((c) => props.client.maintenance(kind, {
    nodes: props.nodes, ...(user ? { files: fileList } : { keyspace, tables }),
    splitOutput: kind === "compact" ? o.splitOutput : undefined, jobs: usesJobs ? o.jobs : undefined,
    disableSnapshot: kind === "scrub" ? o.disableSnapshot : undefined, skipCorrupted: kind === "scrub" ? o.skipCorrupted : undefined,
    noValidate: kind === "scrub" ? o.noValidate : undefined, reinsertOverflowedTtl: kind === "scrub" ? o.reinsertOverflowedTtl : undefined,
    includeAll: kind === "upgradesstables" ? o.includeAll : undefined, granularity: kind === "garbagecollect" ? o.granularity : undefined,
    continueOnError: o.continueOnError,
  }, c));

  return (
    <div className="ops-tab" data-testid="ops-maintenance">
      <div className="ops-form-grid">
        <label className="ops-field">
          <span>Operation</span>
          <select value={kind} onChange={(e) => setKind(e.target.value as MaintenanceKind)}>
            {KINDS.map((k) => <option key={k.id} value={k.id}>{k.label}</option>)}
          </select>
        </label>
        <div className="muted ops-help">{KINDS.find((k) => k.id === kind)!.help}</div>
        {user ? (
          <label className="ops-field ops-wide">
            <span>SSTable data files (one per line, on the node)</span>
            <textarea rows={3} value={files} onChange={(e) => setFiles(e.target.value)}
              placeholder="/var/lib/cassandra/data/ks/table-id/nb-1-big-Data.db" />
          </label>
        ) : (
          <>
            <KeyspaceSelect id={`${props.idPrefix}-mt-ks`} keyspaces={props.keyspaces} value={keyspace}
              onChange={(v) => { setKeyspace(v); setTables([]); }} />
            <TableChecks keyspace={ks} value={tables} onChange={setTables} />
          </>
        )}
        <div className="row ops-options">
          {kind === "compact" && <Check label="Split output (-s)" checked={o.splitOutput} onChange={(v) => set("splitOutput", v)} />}
          {kind === "scrub" && (
            <>
              <Check label="No snapshot (-ns)" checked={o.disableSnapshot} onChange={(v) => set("disableSnapshot", v)} />
              <Check label="Skip corrupted (-s)" checked={o.skipCorrupted} onChange={(v) => set("skipCorrupted", v)} title="Drops corrupt partitions: data loss" />
              <Check label="No validate (-n)" checked={o.noValidate} onChange={(v) => set("noValidate", v)} />
              <Check label="Reinsert overflowed TTL (-r)" checked={o.reinsertOverflowedTtl} onChange={(v) => set("reinsertOverflowedTtl", v)} />
            </>
          )}
          {kind === "upgradesstables" && <Check label="Include all SSTables (-a)" checked={o.includeAll} onChange={(v) => set("includeAll", v)} />}
          {kind === "garbagecollect" && (
            <label className="ops-field">
              <span>Granularity</span>
              <select value={o.granularity} onChange={(e) => set("granularity", e.target.value as "ROW" | "CELL")}>
                <option value="ROW">ROW</option><option value="CELL">CELL</option>
              </select>
            </label>
          )}
          {usesJobs && (
            <label className="ops-field">
              <span>Jobs (-j, 0 = all)</span>
              <input type="number" min={0} max={64} value={o.jobs} onChange={(e) => set("jobs", Math.max(0, Number(e.target.value) || 0))} style={{ width: 70 }} />
            </label>
          )}
          {props.nodes.length > 1 && <Check label="Continue on error" checked={o.continueOnError} onChange={(v) => set("continueOnError", v)} />}
        </div>
      </div>
      <div className="row">
        <button className="btn primary" disabled={!ready || busy} onClick={run}>
          Run on {props.nodes.length} node{props.nodes.length === 1 ? "" : "s"}…
        </button>
        {props.nodes.length === 0 && <span className="muted">Select nodes first.</span>}
        <span className="muted">You will see the exact nodetool commands before anything runs.</span>
      </div>
      {jobId && <div className="ops-job"><JobProgress jobId={jobId} /></div>}
    </div>
  );
}

export function RepairTab(props: { client: OpsClient; nodes: string[]; keyspaces: KeyspaceNode[]; datacenters: string[]; idPrefix: string; onStarted?: (j: Job) => void }) {
  const [keyspace, setKeyspace] = useState("");
  const [tables, setTables] = useState<string[]>([]);
  const [mode, setMode] = useState<"full" | "incremental">("full");
  const [pr, setPr] = useState(true);
  const [parallelism, setParallelism] = useState<"parallel" | "sequential" | "dc_parallel">("parallel");
  const [dcs, setDcs] = useState<string[]>([]);
  const [st, setSt] = useState("");
  const [et, setEt] = useState("");
  const [jobThreads, setJobThreads] = useState(1);
  const [continueOnError, setContinueOnError] = useState(false);
  const { jobId, busy, start } = useStart(props.onStarted);
  const ks = props.keyspaces.find((k) => k.name === keyspace);
  const subRange = st.trim() !== "" || et.trim() !== "";
  const rangeOk = !subRange || (/^-?\d+$/.test(st.trim()) && /^-?\d+$/.test(et.trim()));
  const ready = props.nodes.length > 0 && !!keyspace && rangeOk;

  const run = () => start((c) => props.client.repair({
    nodes: props.nodes, keyspace, tables, mode, primaryRange: subRange ? false : pr, parallelism, dataCenters: dcs,
    ranges: subRange ? [{ start: st.trim(), end: et.trim() }] : [], jobThreads, continueOnError,
  }, c));

  return (
    <div className="ops-tab" data-testid="ops-repair">
      <div className="ops-form-grid">
        <KeyspaceSelect id={`${props.idPrefix}-rp-ks`} keyspaces={props.keyspaces} value={keyspace}
          onChange={(v) => { setKeyspace(v); setTables([]); }} />
        <TableChecks keyspace={ks} value={tables} onChange={setTables} />
        <fieldset className="ops-radios">
          <legend>Type</legend>
          <label className="ops-check"><input type="radio" name={`${props.idPrefix}-mode`} checked={mode === "full"} onChange={() => setMode("full")} /> Full</label>
          <label className="ops-check"><input type="radio" name={`${props.idPrefix}-mode`} checked={mode === "incremental"} onChange={() => setMode("incremental")} /> Incremental</label>
        </fieldset>
        <div className="row ops-options">
          <Check label="Primary range only (-pr)" checked={pr && !subRange} onChange={setPr} />
          <label className="ops-field">
            <span>Parallelism</span>
            <select value={parallelism} onChange={(e) => setParallelism(e.target.value as typeof parallelism)}>
              <option value="parallel">Parallel</option>
              <option value="sequential">Sequential (-seq)</option>
              <option value="dc_parallel">DC parallel (-dcpar)</option>
            </select>
          </label>
          <label className="ops-field">
            <span>Job threads (-j)</span>
            <input type="number" min={1} max={4} value={jobThreads} onChange={(e) => setJobThreads(Math.min(4, Math.max(1, Number(e.target.value) || 1)))} style={{ width: 60 }} />
          </label>
          {props.nodes.length > 1 && <Check label="Continue on error" checked={continueOnError} onChange={setContinueOnError} />}
        </div>
        {props.datacenters.length > 1 && (
          <fieldset className="ops-tables">
            <legend>Datacenters (-dc) <span className="muted">(none = all)</span></legend>
            {props.datacenters.map((dc) => (
              <label key={dc} className="ops-check">
                <input type="checkbox" checked={dcs.includes(dc)} onChange={(e) => setDcs(e.target.checked ? [...dcs, dc] : dcs.filter((x) => x !== dc))} /> {dc}
              </label>
            ))}
          </fieldset>
        )}
        <fieldset className="ops-tables">
          <legend>Sub-range (optional)</legend>
          <label className="ops-field"><span>Start token (-st)</span><input value={st} onChange={(e) => setSt(e.target.value)} placeholder="-9223372036854775808" /></label>
          <label className="ops-field"><span>End token (-et)</span><input value={et} onChange={(e) => setEt(e.target.value)} placeholder="0" /></label>
          {!rangeOk && <span className="error-text" role="alert">Tokens must be whole numbers.</span>}
        </fieldset>
      </div>
      <div className="row">
        <button className="btn primary" disabled={!ready || busy} onClick={run}>
          Repair on {props.nodes.length} node{props.nodes.length === 1 ? "" : "s"}…
        </button>
        <span className="muted">Nodes run one after another; progress comes live from the node. Cancel stops all repair sessions on the node.</span>
      </div>
      {jobId && <div className="ops-job"><JobProgress jobId={jobId} /></div>}
    </div>
  );
}
