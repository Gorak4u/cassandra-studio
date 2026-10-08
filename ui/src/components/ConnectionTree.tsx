import { useMemo, useState } from "react";
import type { ConnectionConfig, Folder } from "../lib/types";

/** The saved-connections tree (CON-2): folders, search, drag-and-drop, environment badges. */
export function ConnectionTree(props: {
  folders: Folder[];
  connections: ConnectionConfig[];
  connected: Set<string>;
  selectedId: string | null;
  onOpen: (c: ConnectionConfig) => void;
  onSelect: (c: ConnectionConfig) => void;
  onEdit: (c: ConnectionConfig) => void;
  onClone: (c: ConnectionConfig) => void;
  onDelete: (c: ConnectionConfig) => void;
  onNewConnection: (folderId: string | null) => void;
  onNewFolder: (parentId: string | null) => void;
  onRenameFolder: (f: Folder) => void;
  onDeleteFolder: (f: Folder) => void;
  onMoveConnection: (c: ConnectionConfig, folderId: string | null) => void;
}) {
  const [filter, setFilter] = useState("");
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [dragOver, setDragOver] = useState<string | null>(null);

  const f = filter.trim().toLowerCase();
  const matches = (c: ConnectionConfig) =>
    !f ||
    c.name.toLowerCase().includes(f) ||
    c.environment.toLowerCase() === f ||
    c.tags.some((t) => t.toLowerCase().includes(f)) ||
    c.contactPoints.some((p) => p.toLowerCase().includes(f));

  // Folders that contain a match somewhere below them stay visible while filtering.
  const visibleFolders = useMemo(() => {
    if (!f) return new Set(props.folders.map((x) => x.id));
    const keep = new Set<string>();
    const parent = new Map(props.folders.map((x) => [x.id, x.parentId ?? null]));
    for (const c of props.connections) {
      if (!matches(c)) continue;
      let p = c.folderId ?? null;
      while (p) {
        keep.add(p);
        p = parent.get(p) ?? null;
      }
    }
    for (const x of props.folders) if (x.name.toLowerCase().includes(f)) keep.add(x.id);
    return keep;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [f, props.folders, props.connections]);

  const toggle = (id: string) =>
    setCollapsed((s) => {
      const n = new Set(s);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });

  const dropProps = (folderId: string | null) => ({
    onDragOver: (e: React.DragEvent) => {
      if (e.dataTransfer.types.includes("application/x-studio-connection")) {
        e.preventDefault();
        e.stopPropagation(); // the root list is an ancestor drop target too
        setDragOver(folderId ?? "root");
      }
    },
    onDragLeave: () => setDragOver(null),
    onDrop: (e: React.DragEvent) => {
      e.stopPropagation(); // otherwise the root's handler also runs and moves it back out
      const id = e.dataTransfer.getData("application/x-studio-connection");
      setDragOver(null);
      const c = props.connections.find((x) => x.id === id);
      if (c && (c.folderId ?? null) !== folderId) props.onMoveConnection(c, folderId);
    },
  });

  const renderLevel = (parentId: string | null, depth: number): React.ReactNode => {
    const folders = props.folders.filter((x) => (x.parentId ?? null) === parentId && visibleFolders.has(x.id));
    const conns = props.connections.filter((c) => (c.folderId ?? null) === parentId && matches(c));
    return (
      <>
        {folders.map((folder) => {
          const open = !collapsed.has(folder.id) || !!f;
          return (
            <div key={folder.id}>
              <div
                className={"tree-item" + (dragOver === folder.id ? " dragover" : "")}
                style={{ paddingLeft: 8 + depth * 14 }}
                onClick={() => toggle(folder.id)}
                {...dropProps(folder.id)}
              >
                <span className="twisty">{open ? "▾" : "▸"}</span>
                <span className="name">📁 {folder.name}</span>
                <span className="row" style={{ gap: 0 }} onClick={(e) => e.stopPropagation()}>
                  <button className="btn link small" title="New connection here" onClick={() => props.onNewConnection(folder.id)}>+</button>
                  <button className="btn link small" title="New sub-folder" onClick={() => props.onNewFolder(folder.id)}>📁+</button>
                  <button className="btn link small" title="Rename folder" onClick={() => props.onRenameFolder(folder)}>✎</button>
                  <button className="btn link small" title="Delete folder" onClick={() => props.onDeleteFolder(folder)}>🗑</button>
                </span>
              </div>
              {open && renderLevel(folder.id, depth + 1)}
            </div>
          );
        })}
        {conns.map((c) => (
          <div
            key={c.id!}
            className={"tree-item" + (props.selectedId === c.id ? " selected" : "")}
            style={{ paddingLeft: 8 + depth * 14 }}
            draggable
            onDragStart={(e) => e.dataTransfer.setData("application/x-studio-connection", c.id!)}
            onClick={() => props.onSelect(c)}
            onDoubleClick={() => props.onOpen(c)}
            title={`${c.contactPoints.join(", ")}${c.notes ? "\n" + c.notes : ""}`}
            data-testid={"conn-" + c.name}
          >
            <span className="twisty" />
            {props.connected.has(c.id!) ? <span className="dot" title="Connected" /> : <span style={{ width: 8 }} />}
            <span className={"env " + c.environment}>{c.environment}</span>
            <span className="name">{c.name}</span>
            {c.readOnly && <span className="muted" title="Read-only">🔒</span>}
            <span className="row" style={{ gap: 0 }} onClick={(e) => e.stopPropagation()}>
              <button className="btn link small" title="Open" onClick={() => props.onOpen(c)}>▶</button>
              <button className="btn link small" title="Edit" onClick={() => props.onEdit(c)}>✎</button>
              <button className="btn link small" title="Clone" onClick={() => props.onClone(c)}>⧉</button>
              <button className="btn link small" title="Delete" onClick={() => props.onDelete(c)}>🗑</button>
            </span>
          </div>
        ))}
      </>
    );
  };

  return (
    <div className="stack" style={{ gap: 6, padding: 8 }}>
      <div className="row">
        <input
          placeholder="Search name, tag, host, PROD…"
          aria-label="Search connections"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          style={{ flex: 1 }}
        />
      </div>
      <div className="row">
        <button className="btn small primary" onClick={() => props.onNewConnection(null)}>+ Connection</button>
        <button className="btn small" onClick={() => props.onNewFolder(null)}>+ Folder</button>
      </div>
      <div className={"tree" + (dragOver === "root" ? " dragover" : "")} {...dropProps(null)} style={{ minHeight: 60 }}>
        {renderLevel(null, 0)}
        {props.connections.length === 0 && (
          <div className="muted" style={{ padding: 12 }}>
            No connections yet. Add one with “+ Connection”, or import a file.
          </div>
        )}
      </div>
    </div>
  );
}
