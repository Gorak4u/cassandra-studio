import { useEffect, useState } from "react";
import { api } from "../lib/api";
import type { SavedScript } from "../lib/types";
import { useToast } from "./feedback";
import { Modal } from "./Modal";

/** Saved scripts kept in Studio, in folders (CQL-8). */
export function ScriptLibrary(props: { onOpen: (s: SavedScript) => void; onClose: () => void }) {
  const toast = useToast();
  const [items, setItems] = useState<SavedScript[] | null>(null);
  const [filter, setFilter] = useState("");
  const load = () => api.scripts().then(setItems).catch(toast.error);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { load(); }, []);
  const f = filter.trim().toLowerCase();
  const shown = (items ?? []).filter((s) => !f || s.name.toLowerCase().includes(f) || s.folder.toLowerCase().includes(f));
  return (
    <Modal title="Script library" onClose={props.onClose} footer={<button className="btn" onClick={props.onClose}>Close</button>}>
      <input autoFocus placeholder="Search scripts…" value={filter} onChange={(e) => setFilter(e.target.value)} style={{ width: "100%", marginBottom: 8 }} />
      {items === null ? <div className="muted">Loading…</div> : shown.length === 0 ? (
        <div className="muted">No saved scripts. Use “Save to library” in the editor.</div>
      ) : (
        <table className="data">
          <thead><tr><th>Folder</th><th>Name</th><th>Updated</th><th /></tr></thead>
          <tbody>
            {shown.map((s) => (
              <tr key={s.id}>
                <td className="muted">{s.folder || "—"}</td>
                <td><button className="btn link" onClick={() => api.script(s.id).then(props.onOpen).catch(toast.error)}>{s.name}</button></td>
                <td className="muted">{new Date(s.updatedAt).toLocaleString()}</td>
                <td><button className="btn link small" aria-label={`Delete ${s.name}`}
                  onClick={() => confirm(`Delete script "${s.name}"?`) && api.deleteScript(s.id).then(load).catch(toast.error)}>🗑</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Modal>
  );
}

/** Name and folder for saving the current editor tab. */
export function SaveScriptDialog(props: {
  initial: { name: string; folder: string };
  onSave: (name: string, folder: string) => void;
  onClose: () => void;
}) {
  const [name, setName] = useState(props.initial.name);
  const [folder, setFolder] = useState(props.initial.folder);
  return (
    <Modal title="Save to script library" onClose={props.onClose} footer={<>
      <button className="btn" onClick={props.onClose}>Cancel</button>
      <button className="btn primary" disabled={!name.trim()} onClick={() => props.onSave(name.trim(), folder.trim())}>Save</button>
    </>}>
      <div className="grid2">
        <label className="field"><span>Name</span><input autoFocus value={name} onChange={(e) => setName(e.target.value)} /></label>
        <label className="field"><span>Folder (e.g. ops/daily)</span><input value={folder} onChange={(e) => setFolder(e.target.value)} /></label>
      </div>
    </Modal>
  );
}
