import { useEffect, useRef, type ReactNode } from "react";
import type { Latency, NodeSnapshot } from "../../lib/monitoringTypes";
import { fmtBytes, fmtCount, fmtDuration, fmtMicros, fmtMs, fmtPct, fmtRate } from "./format";
import { stateCode } from "./health";
import { UsageBar, Val } from "./common";

function Field(props: { label: string; children: ReactNode }) {
  return <><dt>{props.label}</dt><dd>{props.children}</dd></>;
}

/** Node details (MON-12, MON-19): identity, JVM, thread pools, GC, disks, latencies, dropped. */
export function NodeDrawer(props: { node: NodeSnapshot | null; address: string; onClose: () => void }) {
  const close = useRef<HTMLButtonElement>(null);
  const { onClose } = props;
  useEffect(() => {
    close.current?.focus();
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
  const n = props.node;
  return (
    <aside className="mon-drawer" role="dialog" aria-label={`Node ${props.address}`} data-testid="monitoring-node-drawer">
      <header>
        <span className="mono">{props.address}</span>
        {n && <span className={"status " + (stateCode(n.state).startsWith("U") ? "UP" : "DOWN")}>{stateCode(n.state)}</span>}
        <span className="spacer" />
        <button ref={close} className="btn small" onClick={onClose} aria-label="Close node details">✕</button>
      </header>
      {!n ? (
        <div className="empty">This node is not in the latest snapshot.</div>
      ) : (
        <div className="mon-drawer-body stack">
          {n.error && <div className="notice error">{n.error}</div>}
          <dl className="mon-fields">
            <Field label="Host ID"><span className="mono"><Val v={n.hostId} /></span></Field>
            <Field label="DC / rack"><Val v={n.datacenter} /> / <Val v={n.rack} /></Field>
            <Field label="Route"><span className="mono"><Val v={n.route} /></span></Field>
            <Field label="Cassandra"><Val v={n.cassandraVersion} /></Field>
            <Field label="Java"><Val v={n.javaVersion && `${n.javaVersion}${n.javaVendor ? " · " + n.javaVendor : ""}`} /></Field>
            <Field label="Uptime"><Val v={fmtDuration(n.uptimeSec)} /></Field>
            <Field label="Heap"><UsageBar used={n.heapUsedBytes} total={n.heapMaxBytes} fmt={fmtBytes} label="Heap used of max" /></Field>
            <Field label="Off-heap"><Val v={fmtBytes(n.offHeapBytes)} /></Field>
            <Field label="Load / tokens"><Val v={fmtBytes(n.loadBytes)} /> / <Val v={fmtCount(n.tokens)} /></Field>
            <Field label="CPU process / system"><Val v={fmtPct(n.cpuProcessPct)} /> / <Val v={fmtPct(n.cpuSystemPct)} /></Field>
            <Field label="Open file descriptors"><UsageBar used={n.openFds} total={n.maxFds} fmt={fmtCount} warnPct={80} critPct={90} label="Open file descriptors of max" /></Field>
            <Field label="Compactions"><Val v={fmtCount(n.pendingCompactions)} /> pending · <Val v={fmtCount(n.activeCompactions)} /> active · <Val v={fmtCount(n.completedCompactions)} /> done</Field>
            <Field label="Hints"><Val v={fmtCount(n.hintsInProgress)} /> in progress · <Val v={fmtCount(n.totalHints)} /> total</Field>
            <Field label="Live SSTables"><Val v={fmtCount(n.liveSSTables)} /></Field>
            <Field label="GC time"><Val v={fmtPct(n.gcTimePct)} /> of wall clock</Field>
          </dl>

          <section>
            <h4>Thread pools</h4>
            {n.threadPools?.length ? (
              <table className="data mon-table" aria-label="Thread pools">
                <thead><tr><th>Pool</th><th className="num">Active</th><th className="num">Pending</th><th className="num">Blocked</th><th className="num">All-time blocked</th><th className="num">Completed</th></tr></thead>
                <tbody>
                  {n.threadPools.map((p) => (
                    <tr key={p.name} className={(p.blocked ?? 0) > 0 ? "mon-row-error" : (p.pending ?? 0) > 0 ? "mon-row-warn" : undefined}>
                      <td>{p.name}</td>
                      <td className="num"><Val v={fmtCount(p.active)} /></td>
                      <td className="num">{(p.pending ?? 0) > 0 && <span aria-label="pending">▲ </span>}<Val v={fmtCount(p.pending)} /></td>
                      <td className="num">{(p.blocked ?? 0) > 0 && <span aria-label="blocked">✖ </span>}<Val v={fmtCount(p.blocked)} /></td>
                      <td className="num"><Val v={fmtCount(p.allTimeBlocked)} /></td>
                      <td className="num"><Val v={fmtCount(p.completed)} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : <div className="muted">n/a</div>}
          </section>

          <section>
            <h4>Garbage collectors</h4>
            {n.gc?.length ? (
              <table className="data mon-table" aria-label="Garbage collectors">
                <thead><tr><th>Collector</th><th className="num">Collections</th><th className="num">Total time</th></tr></thead>
                <tbody>{n.gc.map((g) => <tr key={g.name}><td>{g.name}</td><td className="num"><Val v={fmtCount(g.count)} /></td><td className="num"><Val v={fmtMs(g.timeMs)} /></td></tr>)}</tbody>
              </table>
            ) : <div className="muted">n/a</div>}
          </section>

          <section>
            <h4>Data directories</h4>
            {n.dataDirs?.length ? n.dataDirs.map((d) => (
              <div key={d.path} className="mon-dir">
                <span className="mono">{d.path}</span>
                <UsageBar used={d.totalBytes !== null && d.freeBytes !== null ? d.totalBytes - d.freeBytes : null} total={d.totalBytes} fmt={fmtBytes} warnPct={80} critPct={90} label={`Disk used in ${d.path}`} />
              </div>
            )) : <div className="muted">n/a</div>}
          </section>

          <section>
            <h4>Client request latency</h4>
            {n.clientRequests ? (
              <>
                <table className="data mon-table" aria-label="Client request latency">
                  <thead><tr><th>Operation</th><th className="num">p50</th><th className="num">p95</th><th className="num">p99</th><th className="num">max</th><th className="num">Rate</th></tr></thead>
                  <tbody>
                    {([["Read", n.clientRequests.read], ["Write", n.clientRequests.write], ["Range slice", n.clientRequests.rangeSlice],
                      ["CAS read", n.clientRequests.casRead], ["CAS write", n.clientRequests.casWrite]] as [string, Latency | null][]).map(([op, l]) => (
                      <tr key={op}>
                        <td>{op}</td>
                        <td className="num"><Val v={fmtMicros(l?.p50Micros)} /></td>
                        <td className="num"><Val v={fmtMicros(l?.p95Micros)} /></td>
                        <td className="num"><Val v={fmtMicros(l?.p99Micros)} /></td>
                        <td className="num"><Val v={fmtMicros(l?.maxMicros)} /></td>
                        <td className="num"><Val v={fmtRate(l?.ratePerSec)} /></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="muted mon-footnote">
                  Timeouts R/W <Val v={fmtCount(n.clientRequests.readTimeouts)} /> / <Val v={fmtCount(n.clientRequests.writeTimeouts)} /> ·
                  Unavailables <Val v={fmtCount(n.clientRequests.readUnavailables)} /> / <Val v={fmtCount(n.clientRequests.writeUnavailables)} /> ·
                  Failures <Val v={fmtCount(n.clientRequests.readFailures)} /> / <Val v={fmtCount(n.clientRequests.writeFailures)} />
                </div>
              </>
            ) : <div className="muted">n/a</div>}
          </section>

          <section>
            <h4>Dropped messages by verb</h4>
            {n.dropped && Object.keys(n.dropped).length ? (
              <table className="data mon-table" aria-label="Dropped messages by verb">
                <thead><tr><th>Verb</th><th className="num">Dropped</th></tr></thead>
                <tbody>
                  {Object.entries(n.dropped).sort((a, b) => b[1] - a[1]).map(([verb, c]) => (
                    <tr key={verb} className={c > 0 ? "mon-row-warn" : undefined}><td className="mono">{verb}</td><td className="num">{fmtCount(c)}</td></tr>
                  ))}
                </tbody>
              </table>
            ) : <div className="muted">n/a</div>}
          </section>
        </div>
      )}
    </aside>
  );
}
