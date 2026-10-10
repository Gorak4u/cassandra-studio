import { describe, expect, it, vi } from "vitest";
import {
  autoConnectOnRestore, clampSidebar, debounced, diagnosticsText, EMPTY_UI_STATE, parseUiState, pruneUiState, SIDEBAR_DEFAULT,
  SIDEBAR_MAX, SIDEBAR_MIN, type UiState,
} from "./studioApi";

describe("remembered UI state", () => {
  it("parses defensively", () => {
    expect(parseUiState(null)).toEqual(EMPTY_UI_STATE);
    expect(parseUiState("x")).toEqual(EMPTY_UI_STATE);
    const s = parseUiState({ open: ["a", 3, "b"], active: "a", connected: ["a"], workspaces: { a: { tab: "monitoring" }, b: { tab: 7 } },
      collapsed: ["f1"], sidebarWidth: 333 });
    expect(s.open).toEqual(["a", "b"]);
    expect(s.workspaces).toEqual({ a: { tab: "monitoring" } });
    expect(s.sidebarWidth).toBe(333);
    expect(parseUiState({ sidebarWidth: "wide" }).sidebarWidth).toBeUndefined();
  });

  it("drops deleted connections and folders", () => {
    const s: UiState = { v: 1, open: ["a", "gone", "b", "a"], active: "gone", connected: ["gone", "b"],
      workspaces: { a: { tab: "query" }, gone: { tab: "schema" } }, collapsed: ["f1", "f-gone"] };
    const p = pruneUiState(s, new Set(["a", "b"]), new Set(["f1"]));
    expect(p.open).toEqual(["a", "b"]);
    expect(p.active).toBe("a");
    expect(p.connected).toEqual(["b"]);
    expect(Object.keys(p.workspaces)).toEqual(["a"]);
    expect(p.collapsed).toEqual(["f1"]);
    expect(pruneUiState({ ...s, active: "audit" }, new Set(["a"]), new Set()).active).toBe("audit");
  });

  it("reconnects PROD tabs only when they were connected", () => {
    const s: UiState = { ...EMPTY_UI_STATE, open: ["p1", "p2", "d1"], connected: ["p1"] };
    expect(autoConnectOnRestore(s, "p1", "PROD")).toBe(true);
    expect(autoConnectOnRestore(s, "p2", "PROD")).toBe(false);
    expect(autoConnectOnRestore(s, "d1", "DEV")).toBe(true);
  });

  it("clamps the pane width", () => {
    expect(clampSidebar(undefined)).toBe(SIDEBAR_DEFAULT);
    expect(clampSidebar(10)).toBe(SIDEBAR_MIN);
    expect(clampSidebar(5000)).toBe(SIDEBAR_MAX);
    expect(clampSidebar(301.6)).toBe(302);
  });

  it("debounces saves and flushes the last one", () => {
    vi.useFakeTimers();
    const save = vi.fn();
    const d = debounced(save, 400);
    d.push(1);
    d.push(2);
    vi.advanceTimersByTime(399);
    expect(save).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1);
    expect(save).toHaveBeenCalledExactlyOnceWith(2);
    d.push(3);
    d.flush();
    expect(save).toHaveBeenLastCalledWith(3);
    d.flush();
    expect(save).toHaveBeenCalledTimes(2);
    vi.useRealTimers();
  });
});

describe("diagnostics text", () => {
  it("has versions, platform and errors", () => {
    const t = diagnosticsText({
      studioVersion: "1.0.0", dbVersion: 3, os: "Linux 6 (amd64)", java: "21.0.4 Eclipse Adoptium", uptimeSec: 12, heapUsedMb: 80,
      heapMaxMb: 1024, threads: 40, processors: 8, secretStore: "OS keychain", savedConnections: 4, crashLog: "/data/logs/crash.log",
      recentErrors: [{ at: "2026-10-10T00:00:00Z", source: "api", message: "GET /api/x: boom" }],
    }, { userAgent: "Chrome", uiErrors: ["TypeError: y"] });
    expect(t).toContain("Cassandra Studio 1.0.0 (database schema v3)");
    expect(t).toContain("Java: 21.0.4");
    expect(t).toContain("[api] GET /api/x: boom");
    expect(t).toContain("[ui, this window] TypeError: y");
    expect(t).toContain("Recent errors (2)");
  });
});
