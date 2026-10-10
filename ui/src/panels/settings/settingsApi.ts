// Global network settings and the update check (docs/api/network.md).
import { request } from "../../lib/api";

export type ProxyMode = "NONE" | "SYSTEM" | "MANUAL";

export interface NetworkSettings {
  offline: boolean;
  checkForUpdates: boolean;
  proxyMode: ProxyMode;
  proxyHost?: string | null;
  proxyPort?: number | null;
  proxyUsername?: string | null;
  noProxy: string[];
  proxyNodeHttp: boolean;
  caBundlePath?: string | null;
  trustOsStore: boolean;
}

export interface NetworkView {
  settings: NetworkSettings;
  proxyPasswordSet: boolean;
  systemProxy: string;
  caBundle?: { path: string; certificates: number; subjects: string[] } | null;
  osTrustAvailable: boolean;
  warning?: string | null;
}

export interface UpdateStatus {
  state: "ok" | "disabled" | "offline" | "error";
  current: string;
  latest?: string | null;
  name?: string | null;
  url?: string | null;
  notes?: string | null;
  publishedAt?: string | null;
  updateAvailable: boolean;
  checkedAt?: string | null;
  message?: string | null;
}

export const settingsApi = {
  network: () => request<NetworkView>("GET", "/api/settings/network"),
  /** proxyPassword: undefined = unchanged, "" = remove. */
  saveNetwork: (s: NetworkSettings, proxyPassword?: string) =>
    request<NetworkView>("PUT", "/api/settings/network", proxyPassword === undefined ? s : { ...s, proxyPassword }),
  updates: (force = false) => request<UpdateStatus>("GET", `/api/updates${force ? "?force=true" : ""}`),
};

/** "a.com, b.com\n10.0.0.0/8" -> ["a.com", "b.com", "10.0.0.0/8"] */
export function parseNoProxy(text: string): string[] {
  return text.split(/[\s,]+/).map((s) => s.trim()).filter(Boolean);
}

const DISMISS_KEY = "studio.update.dismissed";

export function dismissedVersion(): string | null {
  try {
    return localStorage.getItem(DISMISS_KEY);
  } catch {
    return null;
  }
}

export function dismissVersion(v: string) {
  try {
    localStorage.setItem(DISMISS_KEY, v);
  } catch {
    // storage unavailable: the banner comes back next start
  }
}
