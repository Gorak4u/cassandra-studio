import { useCallback, useEffect, useState } from "react";
import { api } from "../lib/api";
import type { ConnectionConfig, Permission, RolesView } from "../lib/types";
import { useGuarded, useToast } from "../components/feedback";
import { Modal } from "../components/Modal";

const PERMISSIONS = ["ALL", "SELECT", "MODIFY", "CREATE", "ALTER", "DROP", "AUTHORIZE", "DESCRIBE", "EXECUTE"];
const RESOURCES: [string, string][] = [
  ["ALL_KEYSPACES", "All keyspaces"], ["KEYSPACE", "Keyspace"], ["TABLE", "Table (ks.table)"],
  ["ALL_ROLES", "All roles"], ["ROLE", "Role"], ["ALL_FUNCTIONS", "All functions"], ["MBEANS", "All MBeans"],
];

/** Users, roles and permissions (SEC-1, SEC-2, SEC-4). */
export function RolesPanel(props: { conn: ConnectionConfig }) {
  const toast = useToast();
  const guarded = useGuarded();
  const [view, setView] = useState<RolesView | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [effective, setEffective] = useState<Permission[] | null>(null);
  const [dialog, setDialog] = useState<null | "create" | "password" | "grant">(null);
  // Bumped after every change so the selected role's effective permissions reload too.
  const [revision, setRevision] = useState(0);

  const load = useCallback(() => { api.roles(props.conn.id!).then(setView).catch(toast.error); }, [props.conn.id, toast]);
  useEffect(load, [load]);
  useEffect(() => {
    setEffective(null);
    if (selected) api.rolePermissions(props.conn.id!, selected).then(setEffective).catch(() => setEffective([]));
  }, [selected, props.conn.id, revision]);

  const run = async (op: string, body: unknown) => {
    try {
      const { cql } = await api.rolesCql(op, body);
      const res = await guarded((c) => api.query(props.conn.id!, { cql, ...c }));
      if (!res) return false;
      if (res.results[0].status === "error") throw new Error(res.results[0].error);
      toast.ok("Done");
      load();
      setRevision((r) => r + 1);
      return true;
    } catch (e) {
      toast.error(e);
      return false;
    }
  };

  return (
    <div className="split">
      <div className="stack" style={{ padding: 8, gap: 6 }}>
        <div className="row">
          <button className="btn small primary" onClick={() => setDialog("create")}>+ Role</button>
          <button className="btn small" onClick={load}>⟳</button>
        </div>
        {view?.rolesError && <div className="notice error">{view.rolesError}</div>}
        <div className="tree">
          {view?.roles.map((r) => (
            <div key={r.name} className={"tree-item" + (selected === r.name ? " selected" : "")} onClick={() => setSelected(r.name)}>
              <span className="name">{r.login ? "👤" : "👥"} {r.name}</span>
              {r.superuser && <span className="status DOWN" title="Superuser">SU</span>}
            </div>
          ))}
        </div>
      </div>
      <div className="pad stack">
        {selected && view && (() => {
          const r = view.roles.find((x) => x.name === selected);
          if (!r) return null;
          return (
            <div className="panel stack">
              <div className="row">
                <h3 style={{ margin: 0 }}>{r.name}</h3>
                <span className="spacer" />
                <button className="btn small" onClick={() => setDialog("password")}>Change password / login</button>
                <button className="btn small" onClick={() => setDialog("grant")}>Grant…</button>
                <button className="btn small danger" onClick={() => run("dropRole", { name: r.name }).then((ok) => ok && setSelected(null))}>Drop</button>
              </div>
              <div>Login: <b>{String(r.login)}</b> · Superuser: <b>{String(r.superuser)}</b> · Member of: {r.memberOf.length ? r.memberOf.join(", ") : "—"}</div>
              <b>Effective permissions (including inherited)</b>
              {effective === null ? <div className="muted">Loading…</div> : effective.length === 0 ? <div className="muted">None, or permissions cannot be listed (AllowAllAuthorizer).</div> : (
                <table className="data">
                  <thead><tr><th>Resource</th><th>Permission</th><th>Granted to</th><th /></tr></thead>
                  <tbody>
                    {effective.map((p, i) => (
                      <tr key={i}>
                        <td className="mono">{p.resource}</td><td>{p.permission}</td><td>{p.role}</td>
                        <td>{p.role === r.name && <button className="btn link small" onClick={() => {
                          const parsed = parseResource(p.resource);
                          if (parsed) run("revokePermission", { permission: p.permission, ...parsed, role: r.name });
                        }}>revoke</button>}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          );
        })()}
        {view?.warnings.map((w, i) => <div key={i} className="notice warn" data-testid="auth-warning">⚠ {w}</div>)}
        {view?.permissionsError && <div className="notice warn">Permissions: {view.permissionsError}</div>}
        {!selected && <div className="empty">Select a role. Roles come from system_auth.roles.</div>}
      </div>
      {dialog === "create" && <RoleDialog title="Create role" onClose={() => setDialog(null)}
        onSubmit={(spec) => run("createRole", { ...spec, ifNotExists: true }).then((ok) => ok && setDialog(null))} />}
      {dialog === "password" && selected && <RoleDialog title={`Alter ${selected}`} name={selected} onClose={() => setDialog(null)}
        onSubmit={(spec) => run("alterRole", spec).then((ok) => ok && setDialog(null))} />}
      {dialog === "grant" && selected && <GrantDialog role={selected} roles={view?.roles.map((x) => x.name) ?? []}
        onClose={() => setDialog(null)} onGrant={async (op, body) => { if (await run(op, body)) setDialog(null); }} />}
    </div>
  );
}

/** "data", "<keyspace ks>", "<table ks.t>", "<all roles>", "roles/x" ... as LIST PERMISSIONS prints them. */
function parseResource(r: string): { resourceType: string; resourceName?: string } | null {
  const m = /^<(\w[\w ]*?)(?: (.+))?>$/.exec(r);
  if (!m) return null;
  const kind = m[1].toLowerCase();
  if (kind === "all keyspaces") return { resourceType: "ALL_KEYSPACES" };
  if (kind === "keyspace") return { resourceType: "KEYSPACE", resourceName: m[2] };
  if (kind === "table") return { resourceType: "TABLE", resourceName: m[2] };
  if (kind === "all roles") return { resourceType: "ALL_ROLES" };
  if (kind === "role") return { resourceType: "ROLE", resourceName: m[2] };
  if (kind === "all functions") return { resourceType: "ALL_FUNCTIONS" };
  return null;
}

function RoleDialog(props: { title: string; name?: string; onClose: () => void; onSubmit: (s: Record<string, unknown>) => void }) {
  const [name, setName] = useState(props.name ?? "");
  const [password, setPassword] = useState("");
  const [login, setLogin] = useState(true);
  const [superuser, setSuperuser] = useState(false);
  return (
    <Modal title={props.title} onClose={props.onClose} footer={<>
      <button className="btn" onClick={props.onClose}>Cancel</button>
      <button className="btn primary" disabled={!name.trim()} onClick={() => props.onSubmit({ name: name.trim(), password, login, superuser })}>Review CQL…</button>
    </>}>
      <div className="grid2">
        <label className="field"><span>Role name</span><input value={name} disabled={!!props.name} onChange={(e) => setName(e.target.value)} /></label>
        <label className="field"><span>Password {props.name && "(blank = unchanged)"}</span><input type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} /></label>
        <label className="check"><input type="checkbox" checked={login} onChange={(e) => setLogin(e.target.checked)} /> Can log in</label>
        <label className="check"><input type="checkbox" checked={superuser} onChange={(e) => setSuperuser(e.target.checked)} /> Superuser</label>
      </div>
      <p className="muted">Passwords are masked in Studio's history and audit log.</p>
    </Modal>
  );
}

function GrantDialog(props: { role: string; roles: string[]; onClose: () => void; onGrant: (op: string, body: unknown) => void }) {
  const [mode, setMode] = useState<"permission" | "role">("permission");
  const [permission, setPermission] = useState("SELECT");
  const [resourceType, setResourceType] = useState("KEYSPACE");
  const [resourceName, setResourceName] = useState("");
  const [memberOf, setMemberOf] = useState(props.roles.find((r) => r !== props.role) ?? "");
  const needsName = ["KEYSPACE", "TABLE", "ROLE"].includes(resourceType);
  return (
    <Modal title={`Grant to ${props.role}`} onClose={props.onClose} footer={<>
      <button className="btn" onClick={props.onClose}>Cancel</button>
      <button className="btn primary" onClick={() => mode === "permission"
        ? props.onGrant("grantPermission", { permission, resourceType, resourceName, role: props.role })
        : props.onGrant("grantRole", { role: memberOf, to: props.role })}>Review CQL…</button>
    </>}>
      <div className="stack">
        <div className="row">
          <label className="check"><input type="radio" checked={mode === "permission"} onChange={() => setMode("permission")} /> Permission</label>
          <label className="check"><input type="radio" checked={mode === "role"} onChange={() => setMode("role")} /> Role membership</label>
        </div>
        {mode === "permission" ? (
          <div className="grid3">
            <label className="field"><span>Permission</span><select value={permission} onChange={(e) => setPermission(e.target.value)}>{PERMISSIONS.map((p) => <option key={p}>{p}</option>)}</select></label>
            <label className="field"><span>On</span><select value={resourceType} onChange={(e) => setResourceType(e.target.value)}>{RESOURCES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></label>
            {needsName && <label className="field"><span>Name</span><input value={resourceName} onChange={(e) => setResourceName(e.target.value)} /></label>}
          </div>
        ) : (
          <label className="field"><span>Make {props.role} a member of</span>
            <select value={memberOf} onChange={(e) => setMemberOf(e.target.value)}>{props.roles.filter((r) => r !== props.role).map((r) => <option key={r}>{r}</option>)}</select>
          </label>
        )}
      </div>
    </Modal>
  );
}
