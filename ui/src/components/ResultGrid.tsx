import { AgGridReact } from "ag-grid-react";
import {
  AllCommunityModule,
  ModuleRegistry,
  colorSchemeDark,
  themeQuartz,
  type CellValueChangedEvent,
  type ColDef,
  type GridApi,
} from "ag-grid-community";
import { useMemo, useRef } from "react";
import type { Cell, Column } from "../lib/types";

ModuleRegistry.registerModules([AllCommunityModule]);

const lightTheme = themeQuartz.withParams({ fontSize: 12, spacing: 5, headerFontWeight: 600 });
const darkTheme = lightTheme.withPart(colorSchemeDark);

export interface GridRow {
  __id: number;
  __new?: boolean;
  [col: string]: Cell | boolean | number | undefined;
}

/** Sortable, filterable, resizable result grid; editable cells when editing is on (CQL-5, CQL-9). */
export function ResultGrid(props: {
  columns: Column[];
  rows: GridRow[];
  dark: boolean;
  editableColumns?: Set<string>;
  onCellChanged?: (row: GridRow, column: string, oldValue: Cell, newValue: Cell) => void;
  onSelectionChanged?: (rows: GridRow[]) => void;
  selectable?: boolean;
}) {
  const apiRef = useRef<GridApi | null>(null);
  const colDefs = useMemo<ColDef[]>(
    () =>
      props.columns.map((c, i) => ({
        field: "c" + i,
        headerName: c.name,
        headerTooltip: `${c.name} ${c.type}`,
        sortable: true,
        filter: true,
        resizable: true,
        editable: (p) => !!props.editableColumns?.has(c.name) || !!(p.data as GridRow).__new,
        tooltipValueGetter: (p) => (p.value === null || p.value === undefined ? "null" : String(p.value)),
        valueFormatter: (p) => (p.value === null || p.value === undefined ? "null" : String(p.value)),
        cellClassRules: { "muted": (p) => p.value === null },
        // Keep the user's typed text as-is; the engine converts it with the column's codec.
        valueParser: (p) => (p.newValue === "" ? null : p.newValue),
        comparator: (a, b) => {
          if (a === b) return 0;
          if (a === null || a === undefined) return -1;
          if (b === null || b === undefined) return 1;
          if (typeof a === "number" && typeof b === "number") return a - b;
          return String(a).localeCompare(String(b), undefined, { numeric: true });
        },
        minWidth: 80,
      })),
    [props.columns, props.editableColumns],
  );

  return (
    <div className="grid" data-testid="result-grid">
      <AgGridReact<GridRow>
        theme={props.dark ? darkTheme : lightTheme}
        rowData={props.rows}
        columnDefs={colDefs}
        getRowId={(p) => String(p.data.__id)}
        rowSelection={props.selectable ? { mode: "multiRow", enableClickSelection: false } : undefined}
        onGridReady={(e) => {
          apiRef.current = e.api;
          if (props.columns.length <= 12) e.api.sizeColumnsToFit();
        }}
        enableCellTextSelection
        ensureDomOrder
        onCellValueChanged={(e: CellValueChangedEvent<GridRow>) => {
          const idx = Number(String(e.colDef.field).slice(1));
          props.onCellChanged?.(e.data!, props.columns[idx].name, e.oldValue as Cell, e.newValue as Cell);
        }}
        onSelectionChanged={(e) => props.onSelectionChanged?.(e.api.getSelectedRows())}
        stopEditingWhenCellsLoseFocus
        tooltipShowDelay={600}
      />
    </div>
  );
}

export function toGridRows(rows: Cell[][], offset = 0): GridRow[] {
  return rows.map((r, i) => {
    const o: GridRow = { __id: offset + i };
    r.forEach((v, k) => (o["c" + k] = v));
    return o;
  });
}

export function gridValue(row: GridRow, columns: Column[], name: string): Cell {
  const i = columns.findIndex((c) => c.name === name);
  return i < 0 ? null : ((row["c" + i] as Cell) ?? null);
}
