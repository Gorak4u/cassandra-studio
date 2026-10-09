import { useCallback, useEffect, useMemo, useState } from "react";
import { JobProgress } from "../../components/JobProgress";
import { errorText } from "../../components/feedback";
import { download } from "../../lib/export";
import type { Job } from "../../lib/jobsTypes";
import { Empty, SortTable, Val, type Col } from "../monitoring/common";
import { fmtBytes } from "../monitoring/format";
import {
  filterThreads, frameText, type Comparison, type DiagClient, type DumpSummary, type Frame, type ThreadDump,
  type ThreadEntry, type TopRow, type TopView,
} from "./diagApi";

const STATES = ["RUNNABLE", "BLOCKED", "WAITING", "TIMED_WAITING"];
const SHOWN_THREADS = 300;

function time(ms: number): string {
  return new Date(ms).toLocaleTimeString();
}

function Stack(props: { frames: Frame[]; lock?: string | null; state?: string; max?: number }) {
  const frames = props.max ? props.frames.slice(0, props.max) : props.frames;
  if (!frames.length) return <div className="muted">(no Java frames)</div>;
  return (
    <pre className="diag-stack">
      {frames.map((f, i) => (
        <span key={i}>
          {"at " + frameText(f)}{"\n"}
          {i === 0 && props.lock && `  - ${props.state === "BLOCKED" ? "waiting to lock" : "waiting on"} ${props.lock}\n`}
          {f.locked.map((l) => `  - locked ${l}\n`).join("")}
        </span>
      ))}
      {props.max && props.frames.length > props.max ? `… ${props.frames.length - props.max} more frames\n` : ""}
    </pre>
  );
}

function StateTag(props: { state: string }) {
  const cls = props.state === "BLOCKED" ? "BLOCKED" : props.state === "RUNNABLE" ? "ok" : "";
  return <span className={"status diag-state " + cls}>{props.state}</span>;
}

/** One dump: state counts, deadlocks, blocked threads with owner chains, stacks grouped or per thread. */
function DumpView(props: { dump: ThreadDump; client: DiagClient; onDeleted: () => void }) {
  const { dump } = props;
  const [mode, setMode] = useState<"groups" | "threads">("groups");
  const [q, setQ] = useState("");
  const [state, setState] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const blocked = dump.threads.filter((t) => t.state === "BLOCKED" || t.deadlocked);
  const threads = useMemo(() => filterThreads(dump.threads, q, state), [dump, q, state]);
  const groups = useMemo(() => {
    if (!q && !state) return dump.groups;
    const keep = new Set(threads.map((t) => t.stackKey));
    return dump.groups.filter((g) => keep.has(g.key));
  }, [dump, threads, q, state]);
  const exportTxt = () => {
    setErr(null);
    props.client.dumpText(dump.id)
      .then((txt) => download(`threads-${dump.node}-${new Date(dump.takenAtMs).toISOString().replace(/:/g, "")}.txt`, txt, "text/plain"))
      .catch((e) => setErr(errorText(e)));
  };
  return (
    <section className="stack diag-dump" aria-label="Thread dump" data-testid="diag-dump">
      <div className="row">
        <b>{dump.node}</b>
        <span className="muted">{new Date(dump.takenAtMs).toLocaleString()} · {dump.jvm ?? "JVM"} · {dump.threadCount} threads</span>
        <span className="spacer" />
        <button className="btn small" onClick={exportTxt}>Export .txt</button>
        <button className="btn small" onClick={() => props.client.deleteDump(dump.id).then(props.onDeleted).catch((e) => setErr(errorText(e)))}>Delete</button>
      </div>
      {err && <div className="notice error" role="alert">{err}</div>}
      <div className="row diag-chips" aria-label="Threads by state">
        {Object.entries(dump.byState).filter(([, n]) => n > 0).map(([s, n]) => (
          <button key={s} className={"btn small" + (state === s ? " primary" : "")} aria-pressed={state === s}
            onClick={() => setState(state === s ? "" : s)}>{s} {n}</button>
        ))}
      </div>
      {dump.deadlocks.map((d, i) => (
        <div key={i} className="notice error" role="alert" data-testid="diag-deadlock">
          <b>Deadlock {i + 1}: {d.threadIds.length} threads</b>
          <ul className="diag-list">{d.lines.map((l) => <li key={l}>{l}</li>)}</ul>
        </div>
      ))}
      {blocked.length > 0 && (
        <div className="notice warn" data-testid="diag-blocked">
          <b>{blocked.length} blocked thread{blocked.length === 1 ? "" : "s"}</b>
          <ul className="diag-list">
            {blocked.slice(0, 50).map((t) => (
              <li key={t.id}>
                <b>{t.name}</b> waits for {t.lock ?? "a lock"}
                {t.ownerChain.length > 0 && <> held by {t.ownerChain.join(" → ")}</>}
              </li>
            ))}
          </ul>
        </div>
      )}
      <div className="row">
        <div className="diag-seg" role="group" aria-label="Stack view">
          <button className={"btn small" + (mode === "groups" ? " primary" : "")} aria-pressed={mode === "groups"} onClick={() => setMode("groups")}>
            By stack ({groups.length})
          </button>
          <button className={"btn small" + (mode === "threads" ? " primary" : "")} aria-pressed={mode === "threads"} onClick={() => setMode("threads")}>
            All threads ({threads.length})
          </button>
        </div>
        <label className="row"><span className="muted">Filter</span>
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="thread name or frame" aria-label="Filter threads by name or frame" />
        </label>
        <label className="row"><span className="muted">State</span>
          <select value={state} onChange={(e) => setState(e.target.value)} aria-label="Filter by state">
            <option value="">All</option>
            {STATES.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
        </label>
      </div>
      {mode === "groups" ? (
        <div className="diag-groups">
          {groups.map((g) => (
            <details key={g.key} className="diag-item">
              <summary>
                <b>{g.count} thread{g.count === 1 ? "" : "s"}</b>{" "}
                {Object.entries(g.states).map(([s, n]) => <span key={s}><StateTag state={s} />{g.count > 1 ? ` ${n} ` : " "}</span>)}
                <span className="muted diag-names">{g.threadNames.slice(0, 4).join(", ")}{g.threadNames.length > 4 ? ", …" : ""}</span>
                {g.stack[0] && <code className="diag-top">{frameText(g.stack[0])}</code>}
              </summary>
              {g.count > 4 && <div className="muted diag-allnames">{g.threadNames.join(", ")}</div>}
              <Stack frames={g.stack} />
            </details>
          ))}
          {!groups.length && <Empty>No thread matches.</Empty>}
        </div>
      ) : (
        <div className="diag-groups">
          {threads.slice(0, SHOWN_THREADS).map((t) => <ThreadItem key={t.id} t={t} />)}
          {threads.length > SHOWN_THREADS && <div className="muted">Showing {SHOWN_THREADS} of {threads.length}; narrow the filter.</div>}
          {!threads.length && <Empty>No thread matches.</Empty>}
        </div>
      )}
    </section>
  );
}

function ThreadItem(props: { t: ThreadEntry }) {
  const { t } = props;
  return (
    <details className={"diag-item" + (t.deadlocked ? " diag-deadlocked" : t.state === "BLOCKED" ? " diag-blockedrow" : "")}>
      <summary>
        <StateTag state={t.state} /> <b>{t.name}</b> <span className="muted">#{t.id}{t.daemon ? " daemon" : ""}</span>
        {t.deadlocked && <span className="status error">DEADLOCKED</span>}
        {t.stack[0] && <code className="diag-top">{frameText(t.stack[0])}</code>}
      </summary>
      {t.ownerChain.length > 0 && <div className="muted">Lock owner chain: {t.ownerChain.join(" → ")}</div>}
      <Stack frames={t.stack} lock={t.lock} state={t.state} />
      {t.lockedSynchronizers.length > 0 && <div className="muted">Locked synchronizers: {t.lockedSynchronizers.join(", ")}</div>}
    </details>
  );
}

function CompareView(props: { c: Comparison }) {
  const { c } = props;
  const [hideIdle, setHideIdle] = useState(true);
  const stuck = hideIdle ? c.stuck.filter((s) => !s.likelyIdle) : c.stuck;
  return (
    <section className="stack" aria-label="Dump comparison" data-testid="diag-compare">
      <div className="muted">{c.a.node}: {time(c.a.takenAtMs)} → {time(c.b.takenAtMs)} ({(c.intervalMs / 1000).toFixed(1)} s apart)</div>
      <table className="data diag-small-table" aria-label="Threads by state in both dumps">
        <thead><tr><th>State</th><th className="num">First</th><th className="num">Second</th></tr></thead>
        <tbody>
          {Object.entries(c.stateCounts).filter(([, v]) => v[0] || v[1]).map(([s, v]) => (
            <tr key={s}><td>{s}</td><td className="num">{v[0]}</td><td className="num">{v[1]}</td></tr>
          ))}
        </tbody>
      </table>
      <div className="diag-cols">
        <div><h4>New threads ({c.added.length})</h4><ul className="diag-list">{c.added.map((t) => <li key={t.id}>{t.name} <StateTag state={t.state} /></li>)}</ul></div>
        <div><h4>Gone ({c.removed.length})</h4><ul className="diag-list">{c.removed.map((t) => <li key={t.id}>{t.name}</li>)}</ul></div>
        <div><h4>Changed state ({c.changed.length})</h4><ul className="diag-list">{c.changed.map((t) => <li key={t.id}>{t.name}: {t.before} → {t.after}</li>)}</ul></div>
      </div>
      <div className="row">
        <h4>Same stack in both dumps ({stuck.length})</h4>
        <label className="row"><input type="checkbox" checked={hideIdle} onChange={(e) => setHideIdle(e.target.checked)} /> Hide threads idle in native code (epoll, accept)</label>
      </div>
      <div className="diag-groups">
        {stuck.map((s) => (
          <details key={s.id} className={"diag-item" + (s.state === "BLOCKED" ? " diag-blockedrow" : "")}>
            <summary><StateTag state={s.state} /> <b>{s.name}</b> {s.top[0] && <code className="diag-top">{frameText(s.top[0])}</code>}</summary>
            <Stack frames={s.top} />
          </details>
        ))}
        {!stuck.length && <Empty>No thread was stuck in the same place.</Empty>}
      </div>
    </section>
  );
}

/** Thread dumps (JVM-1): take one or a series, browse, compare, export. */
export function DumpsView(props: { client: DiagClient; node: string }) {
  const { client, node } = props;
  const [dumps, setDumps] = useState<DumpSummary[]>([]);
  const [current, setCurrent] = useState<ThreadDump | null>(null);
  const [cmp, setCmp] = useState<Comparison | null>(null);
  const [a, setA] = useState("");
  const [b, setB] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [count, setCount] = useState(3);
  const [interval, setIntervalSec] = useState(5);
  const [jobId, setJobId] = useState<string | null>(null);

  const reload = useCallback(() => client.dumps().then(setDumps).catch((e) => setErr(errorText(e))), [client]);
  useEffect(() => { reload(); }, [reload]);

  const take = () => {
    setBusy(true);
    setErr(null);
    client.takeDump(node).then((d) => { setCurrent(d); setCmp(null); reload(); })
      .catch((e) => setErr(errorText(e))).finally(() => setBusy(false));
  };
  const open = (id: string) => client.dump(id).then((d) => { setCurrent(d); setCmp(null); }).catch((e) => setErr(errorText(e)));
  const series = () => {
    setErr(null);
    client.series(node, count, interval).then((j) => setJobId(j.id)).catch((e) => setErr(errorText(e)));
  };
  const seriesDone = (j: Job) => {
    reload();
    const ids = (j.result as { dumpIds?: string[] } | null)?.dumpIds;
    if (ids && ids.length >= 2) { setA(ids[0]); setB(ids[ids.length - 1]); }
  };
  const compare = () => {
    setErr(null);
    client.compare(a, b).then((c) => { setCmp(c); setCurrent(null); }).catch((e) => setErr(errorText(e)));
  };

  return (
    <div className="stack">
      <div className="row">
        <button className="btn primary" onClick={take} disabled={busy || !node}>{busy ? "Taking dump…" : "Take thread dump"}</button>
        <span className="muted">or a series of</span>
        <input type="number" min={2} max={20} value={count} onChange={(e) => setCount(Number(e.target.value))} className="diag-num" aria-label="Number of dumps" />
        <span className="muted">dumps every</span>
        <input type="number" min={1} max={300} value={interval} onChange={(e) => setIntervalSec(Number(e.target.value))} className="diag-num" aria-label="Seconds between dumps" />
        <span className="muted">s</span>
        <button className="btn" onClick={series} disabled={!node}>Take series</button>
      </div>
      {err && <div className="notice error" role="alert">{err}</div>}
      {jobId && <JobProgress jobId={jobId} onDone={seriesDone} />}
      {dumps.length > 0 && (
        <details className="diag-item" open={!current && !cmp}>
          <summary><b>Stored dumps ({dumps.length})</b> <span className="muted">kept in the engine until disconnect</span></summary>
          <table className="data diag-small-table" aria-label="Stored thread dumps" data-testid="diag-dumps">
            <thead><tr><th>Taken</th><th>Node</th><th className="num">Threads</th><th className="num">Blocked</th><th className="num">Deadlocked</th><th>Series</th><th>Action</th></tr></thead>
            <tbody>
              {dumps.map((d) => (
                <tr key={d.id}>
                  <td>{new Date(d.takenAtMs).toLocaleString()}</td><td>{d.node}</td><td className="num">{d.threadCount}</td>
                  <td className="num">{d.blockedCount}</td><td className="num">{d.deadlockedCount ? <span className="status error">{d.deadlockedCount}</span> : 0}</td>
                  <td>{d.seriesId ? d.seriesId.slice(0, 8) : ""}</td>
                  <td><button className="btn small" onClick={() => open(d.id)} aria-label={`Open dump of ${d.node} at ${time(d.takenAtMs)}`}>Open</button></td>
                </tr>
              ))}
            </tbody>
          </table>
          {dumps.length >= 2 && (
            <div className="row diag-pad-top">
              <label className="row"><span className="muted">Compare</span>
                <select value={a} onChange={(e) => setA(e.target.value)} aria-label="First dump">
                  <option value="">first dump…</option>
                  {dumps.map((d) => <option key={d.id} value={d.id}>{d.node} {time(d.takenAtMs)}</option>)}
                </select>
              </label>
              <label className="row"><span className="muted">with</span>
                <select value={b} onChange={(e) => setB(e.target.value)} aria-label="Second dump">
                  <option value="">second dump…</option>
                  {dumps.map((d) => <option key={d.id} value={d.id}>{d.node} {time(d.takenAtMs)}</option>)}
                </select>
              </label>
              <button className="btn small" onClick={compare} disabled={!a || !b || a === b}>Compare</button>
            </div>
          )}
        </details>
      )}
      {current && <DumpView dump={current} client={client} onDeleted={() => { setCurrent(null); reload(); }} />}
      {cmp && <CompareView c={cmp} />}
      {!current && !cmp && !dumps.length && <Empty>Take a thread dump of the selected node over JMX.</Empty>}
    </div>
  );
}

const topCols: Col<TopRow>[] = [
  { key: "name", label: "Thread", sort: (r) => r.name, render: (r) => <span><b>{r.name}</b>{r.id != null && <span className="muted"> #{r.id}</span>}</span> },
  { key: "threads", label: "Threads", num: true, sort: (r) => r.threads, render: (r) => r.threads },
  { key: "state", label: "State", sort: (r) => r.state, render: (r) => <StateTag state={r.state} /> },
  { key: "cpu", label: "CPU %", title: "Of one core over the interval", num: true, sort: (r) => r.cpuPct, render: (r) => <CpuBar pct={r.cpuPct} /> },
  { key: "user", label: "User %", num: true, sort: (r) => r.userPct, render: (r) => r.userPct.toFixed(1) },
  { key: "alloc", label: "Alloc / s", num: true, sort: (r) => r.allocBytesPerSec, render: (r) => <Val v={r.allocBytesPerSec == null ? null : fmtBytes(r.allocBytesPerSec) + "/s"} /> },
  { key: "total", label: "CPU total", title: "CPU time since the thread started", num: true, sort: (r) => r.cpuTotalMs, render: (r) => (r.cpuTotalMs / 1000).toFixed(1) + " s" },
];

function CpuBar(props: { pct: number }) {
  const w = Math.max(0, Math.min(100, props.pct));
  return (
    <span className="diag-cpu">
      <span className="diag-cpu-bar" aria-hidden="true"><span style={{ width: w + "%" }} /></span>
      {props.pct.toFixed(1)}
    </span>
  );
}

/** Top threads by CPU like sjk ttop (JVM-2), refreshed live. */
export function TopThreadsView(props: { client: DiagClient; node: string }) {
  const { client, node } = props;
  const [live, setLive] = useState(false);
  const [every, setEvery] = useState(3);
  const [group, setGroup] = useState(false);
  const [limit, setLimit] = useState(30);
  const [view, setView] = useState<TopView | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => { setView(null); setLive(false); }, [node]);

  useEffect(() => {
    if (!live || !node) return;
    let stop = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const tick = () => {
      setLoading(true);
      client.top(node, limit, group)
        .then((v) => { if (!stop) { setView(v); setErr(null); } })
        .catch((e) => { if (!stop) { setErr(errorText(e)); setLive(false); } })
        .finally(() => {
          if (stop) return;
          setLoading(false);
          timer = setTimeout(tick, every * 1000);
        });
    };
    tick();
    return () => { stop = true; if (timer) clearTimeout(timer); };
  }, [live, node, every, group, limit, client]);

  return (
    <div className="stack" data-testid="diag-top">
      <div className="row">
        <button className={"btn" + (live ? "" : " primary")} onClick={() => setLive(!live)} disabled={!node}>{live ? "Stop" : "Start live view"}</button>
        <label className="row"><span className="muted">Refresh every</span>
          <select value={every} onChange={(e) => setEvery(Number(e.target.value))} aria-label="Refresh interval">
            {[2, 3, 5, 10].map((s) => <option key={s} value={s}>{s} s</option>)}
          </select>
        </label>
        <label className="row"><span className="muted">Show</span>
          <select value={limit} onChange={(e) => setLimit(Number(e.target.value))} aria-label="Rows shown">
            {[20, 30, 50, 100].map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
        </label>
        <label className="row"><input type="checkbox" checked={group} onChange={(e) => setGroup(e.target.checked)} /> Group thread pools</label>
        {live && loading && <span className="muted" role="status">sampling…</span>}
      </div>
      {err && <div className="notice error" role="alert">{err}</div>}
      {view ? (
        <>
          <div className="row diag-kpis" aria-live="polite">
            <span>Process CPU <b>{view.processCpuPct == null ? "n/a" : view.processCpuPct.toFixed(1) + " %"}</b>{view.processors ? <span className="muted"> of {view.processors} cores</span> : null}</span>
            <span>Threads CPU <b>{view.threadsCpuPct.toFixed(1)} %</b> <span className="muted">(100 % = one core)</span></span>
            <span>{view.threadCount} threads</span>
            <span className="muted">interval {(view.intervalMs / 1000).toFixed(1)} s · {time(view.atMs)}{view.allocSupported ? "" : " · allocation not reported by this JVM"}</span>
          </div>
          <SortTable rows={view.rows} cols={topCols} rowKey={(r) => r.name + "#" + (r.id ?? "")} label="Top threads by CPU" testId="diag-top-table" />
        </>
      ) : (
        <Empty>Start the live view to sample per-thread CPU and allocation over JMX.</Empty>
      )}
    </div>
  );
}
