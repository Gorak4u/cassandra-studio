import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { AppSettings } from "../AppSettings";
import { SettingsDialog } from "../SettingsDialog";
import { parseNoProxy, type NetworkView } from "../settingsApi";
import { ToastProvider } from "../../../components/feedback";

function json(status: number, body: unknown) {
  return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } }));
}

const view: NetworkView = {
  settings: { offline: false, checkForUpdates: true, proxyMode: "SYSTEM", proxyHost: null, proxyPort: null, proxyUsername: null,
    noProxy: [], proxyNodeHttp: false, caBundlePath: null, trustOsStore: false },
  proxyPasswordSet: false,
  systemProxy: "http://envproxy:3128 (HTTPS_PROXY)",
  caBundle: null,
  osTrustAvailable: true,
  warning: null,
};

beforeEach(() => {
  try { localStorage.clear(); } catch { /* ignore */ }
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("SettingsDialog", () => {
  it("shows the detected system proxy and saves a manual proxy with password and no-proxy list", async () => {
    const puts: Record<string, unknown>[] = [];
    vi.stubGlobal("fetch", vi.fn((url: string, init?: RequestInit) => {
      if (url.endsWith("/api/settings/network") && init?.method === "PUT") {
        const body = JSON.parse(String(init.body));
        puts.push(body);
        return json(200, { ...view, settings: { ...view.settings, ...body }, proxyPasswordSet: true });
      }
      if (url.endsWith("/api/settings/network")) return json(200, view);
      return json(404, { error: "not_found", message: "no" });
    }));
    const onClose = vi.fn();
    render(<ToastProvider><SettingsDialog onClose={onClose} /></ToastProvider>);
    expect(await screen.findByText(/Detected: http:\/\/envproxy:3128/)).toBeTruthy();

    fireEvent.click(screen.getByLabelText("Manual"));
    fireEvent.change(screen.getByLabelText("Proxy host"), { target: { value: "proxy.corp" } });
    fireEvent.change(screen.getByLabelText("Proxy port"), { target: { value: "8080" } });
    fireEvent.change(screen.getByLabelText("Proxy user (optional)"), { target: { value: "alice" } });
    fireEvent.change(screen.getByLabelText(/Proxy password/), { target: { value: "s3cret" } });
    fireEvent.change(screen.getByLabelText(/No proxy for/), { target: { value: ".corp, 10.0.0.0/8 host1" } });
    fireEvent.change(screen.getByLabelText(/CA bundle/), { target: { value: "/etc/pki/corp.pem" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(puts[0]).toMatchObject({ proxyMode: "MANUAL", proxyHost: "proxy.corp", proxyPort: 8080, proxyUsername: "alice",
      proxyPassword: "s3cret", noProxy: [".corp", "10.0.0.0/8", "host1"], caBundlePath: "/etc/pki/corp.pem" });
  });

  it("offline mode disables the update check and the proxy; engine errors are shown", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string, init?: RequestInit) => {
      if (init?.method === "PUT") return json(400, { error: "bad_request", message: "Cannot read CA bundle /nope.pem" });
      if (url.endsWith("/api/settings/network")) return json(200, view);
      return json(404, { error: "not_found", message: "no" });
    }));
    render(<ToastProvider><SettingsDialog onClose={() => undefined} /></ToastProvider>);
    const offline = await screen.findByLabelText(/Offline mode/);
    fireEvent.click(offline);
    expect((screen.getByLabelText(/Check for updates/) as HTMLInputElement).disabled).toBe(true);
    expect((screen.getByLabelText(/Check for updates/) as HTMLInputElement).checked).toBe(false);
    expect((screen.getByLabelText("Manual") as HTMLInputElement).closest("fieldset")!.disabled).toBe(true);
    expect(screen.queryByRole("button", { name: "Check now" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect((await screen.findByRole("alert")).textContent).toContain("Cannot read CA bundle");
  });

  it("keeps a stored password unless cleared", async () => {
    const puts: Record<string, unknown>[] = [];
    vi.stubGlobal("fetch", vi.fn((_url: string, init?: RequestInit) => {
      const stored = { ...view, proxyPasswordSet: true, settings: { ...view.settings, proxyMode: "MANUAL", proxyHost: "p", proxyPort: 1 } };
      if (init?.method === "PUT") { puts.push(JSON.parse(String(init.body))); return json(200, stored); }
      return json(200, stored);
    }));
    render(<ToastProvider><SettingsDialog onClose={() => undefined} /></ToastProvider>);
    await screen.findByText(/stored in keychain/);
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(puts).toHaveLength(1));
    expect("proxyPassword" in puts[0]).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "Clear" }));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(puts).toHaveLength(2));
    expect(puts[1].proxyPassword).toBe("");
  });
});

describe("AppSettings update banner", () => {
  it("shows a dismissible banner with the release link when a newer version exists", async () => {
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      if (url.endsWith("/api/updates")) return json(200, { state: "ok", current: "1.0.0", latest: "1.1.0", updateAvailable: true,
        url: "https://github.com/Gorak4u/cassandra-studio/releases/tag/v1.1.0", message: "Version 1.1.0 is available." });
      return json(404, { error: "not_found", message: "no" });
    }));
    render(<ToastProvider><AppSettings /></ToastProvider>);
    const banner = await screen.findByTestId("update-banner");
    expect(banner.textContent).toContain("1.1.0 is available (you have 1.0.0)");
    const link = screen.getByRole("link", { name: /Release notes/ }) as HTMLAnchorElement;
    expect(link.href).toBe("https://github.com/Gorak4u/cassandra-studio/releases/tag/v1.1.0");
    expect(link.target).toBe("_blank");
    fireEvent.click(screen.getByRole("button", { name: "Dismiss update 1.1.0" }));
    expect(screen.queryByTestId("update-banner")).toBeNull();
    cleanup();
    render(<ToastProvider><AppSettings /></ToastProvider>);
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.length).toBeGreaterThanOrEqual(2));
    expect(screen.queryByTestId("update-banner")).toBeNull();
  });

  it("shows nothing when disabled, offline, up to date or failing", async () => {
    for (const body of [{ state: "disabled", current: "1.0.0", updateAvailable: false },
      { state: "offline", current: "1.0.0", updateAvailable: false },
      { state: "ok", current: "1.1.0", latest: "1.1.0", updateAvailable: false }]) {
      vi.stubGlobal("fetch", vi.fn(() => json(200, body)));
      render(<ToastProvider><AppSettings /></ToastProvider>);
      await waitFor(() => expect(vi.mocked(fetch)).toHaveBeenCalled());
      expect(screen.queryByTestId("update-banner")).toBeNull();
      cleanup();
    }
    vi.stubGlobal("fetch", vi.fn(() => json(500, { error: "x", message: "boom" })));
    render(<ToastProvider><AppSettings /></ToastProvider>);
    await waitFor(() => expect(vi.mocked(fetch)).toHaveBeenCalled());
    expect(screen.queryByTestId("update-banner")).toBeNull();
    expect(screen.getByRole("button", { name: "Settings" })).toBeTruthy();
  });
});

describe("parseNoProxy", () => {
  it("splits on commas, spaces and new lines", () => {
    expect(parseNoProxy(" a.com, b.com\n10.0.0.0/8  ,, ")).toEqual(["a.com", "b.com", "10.0.0.0/8"]);
  });
});
