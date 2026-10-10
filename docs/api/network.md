# Network, offline and updates API (NFR-NET, NFR-UPD, NFR-SEC)

Global network settings (proxy, extra CA certificates, offline mode) and the update check.
Shapes: `engine/.../net/*.java` = `ui/src/panels/settings/settingsApi.ts`. The UI is
Settings (app header) > Network, plus a non-blocking update banner.

**What leaves the machine.** Only traffic to the user's clusters (CQL, JMX, SSH, jmx_exporter
HTTP) and, when "Check for updates" is on and offline mode is off, one HTTPS GET to
`api.github.com` when the UI starts (cached 6 h). Nothing else: no telemetry, no crash upload,
no fonts or CDN loads, no auto-updater. The request carries a fixed `User-Agent:
CassandraStudio-update-check` and no identifier.

## Settings

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/settings/network` | | `View` |
| PUT | `/api/settings/network` | `NetworkSettings` fields + optional `proxyPassword` | `View` (400 with every problem listed) |

PUT replaces the whole settings object (omitted fields take their defaults): send what GET returned,
changed. `proxyPassword`: omitted or null = unchanged, `""` = remove, else the new password (stored in
the OS keychain / secret store under `net/proxyPassword`, never in the database or a response).

`NetworkSettings` (settings table, key `net.settings`):

| Field | Default | Meaning |
|---|---|---|
| `offline` | false | Air-gapped mode: every outbound call except to clusters is off (update check returns `offline`; `Net.internetClient` refuses with 409 `offline`). |
| `checkForUpdates` | true | Ask GitHub for the latest release when the UI starts. |
| `proxyMode` | `SYSTEM` | `NONE` (direct), `SYSTEM` (env `HTTPS_PROXY`/`HTTP_PROXY`/`NO_PROXY`, lower or upper case, else Java `https.proxyHost`/`https.proxyPort`/`http.nonProxyHosts`), `MANUAL` (below). |
| `proxyHost`, `proxyPort` | | HTTP proxy for `MANUAL` (required there). |
| `proxyUsername` | | Basic proxy auth user (`MANUAL`, and overrides a user in `HTTPS_PROXY` for `SYSTEM`). |
| `noProxy` | `[]` | `MANUAL` bypass list: `host`, `domain` / `.domain` / `*.domain` (with sub-domains), `10.*`, IPv4, IPv4 CIDR `10.0.0.0/8`, `*`. Loopback is always direct. |
| `proxyNodeHttp` | false | Also send HTTP to cluster nodes (jmx_exporter scrape) through the proxy. Off: node addresses stay direct. |
| `caBundlePath` | | PEM file with extra CA certificates, trusted for CQL TLS, JMX TLS and HTTPS in addition to each connection's own truststore (or the JDK CAs when it has none). Validated on save. |
| `trustOsStore` | false | Also trust the OS CA store: Windows `Windows-ROOT`, macOS keychain, Linux system PEM bundle (`/etc/ssl/certs/ca-certificates.crt`, `/etc/pki/tls/certs/ca-bundle.crt`, ...). |

`View`: `{settings, proxyPasswordSet, systemProxy, caBundle: {path, certificates, subjects[]} | null,
osTrustAvailable, warning}`. `systemProxy` is what `SYSTEM` mode resolves (e.g.
`http://proxy:3128 (HTTPS_PROXY)` or `none (...)`), never with a password. `warning` is set when the
stored CA bundle could not be loaded at start (the engine then starts without it).

Trust is "any of": a server certificate is accepted when the connection truststore, the JDK CAs (only
when the connection has no truststore), the CA bundle or the OS store accepts it. Hostname verification
is unchanged (per connection for CQL; HTTPS always verifies). New settings apply to new connections;
reconnect open clusters to pick up a new CA bundle.

## Update check

| Method | Path | Returns |
|---|---|---|
| GET | `/api/updates[?force=true]` | `UpdateStatus` |

`UpdateStatus`: `{state, current, latest, name, url, notes, publishedAt, updateAvailable, checkedAt,
message}`. `state`: `ok` (see `updateAvailable`), `disabled` (setting off), `offline`, `error`
(`message` says why: timeout, TLS failure with a CA-bundle hint, 407, rate limit). No network call is
made for `disabled` / `offline`.

- Source: `GET https://api.github.com/repos/Gorak4u/cassandra-studio/releases/latest` (pre-releases and
  drafts are not offered), 5 s connect and request timeout, proxy and CA settings applied.
- Comparison: semantic versioning 2.0 with pre-releases (`1.0.0-rc.1 < 1.0.0`, `0.1.0-SNAPSHOT < 0.1.0`),
  tags may start with `v`. A development build (`dev`) is never "outdated".
- Result cached 6 h (errors are not cached); `force=true` and every settings save bypass the cache.
- Never downloads: `url` is always a page under `https://github.com/Gorak4u/cassandra-studio/releases`
  (anything else in the response is replaced by the releases page). The banner opens it in the system
  browser. Signed auto-install comes with NFR-SIGN.

## SSH through a proxy (per connection)

`ConnectionConfig.ssh.proxy` (optional, absent in older saved connections, which load unchanged):
`{type: "HTTP" | "SOCKS5", host, port (default 3128 / 1080), username}`; password in the connection
secret `sshProxyPassword`. The proxy carries the first hop: the jump host when one is set, else the
node. HTTP uses `CONNECT host:port` (Basic auth); SOCKS5 sends the host name unresolved (RFC 1928
ATYP 3, user/password RFC 1929), so node names that only resolve behind the proxy work. Errors name the
proxy and the reason ("requires authentication: user alice was rejected", "could not connect to x:22:
connection refused"). Implementation: a one-shot loopback relay (`net/ProxyRelay`) that accepts exactly
one connection, used by MINA SSHD as the dial address.

## Desktop shell (Electron)

- The window may only request the engine's own origin (plus `data:`, `blob:`, `devtools:`): every other
  request is cancelled in `session.webRequest` and logged; the UI's CSP allows `'self'` and loopback only.
- Chromium runs with `--no-proxy-server` (no WPAD/PAC lookups), background networking, component
  updates, domain reliability reporting, DNS prefetch and pings off; spellchecker off (no dictionary
  downloads); no crash reporter; no auto-updater (`electron-builder` writes no `app-update.yml`);
  permission requests denied.
- The engine inherits the environment, so `HTTPS_PROXY` / `NO_PROXY` reach `SYSTEM` mode, and is started
  with `-Djdk.http.auth.tunneling.disabledSchemes=` so Basic proxy auth works for HTTPS tunnels.

## Offline bundle check (CI)

`npm run build` ends with `node scripts/check-offline.mjs` (also `npm run check:offline`): it fails when
`dist/` could load anything from outside the engine's origin: a remote `<script>/<link>/<img>` in
`index.html`, a CSP source other than `'self'`/loopback, a remote `url()`/`@import` in CSS, or any
absolute URL in JS whose host is not on a reviewed list of reference-only hosts (docs links, XML
namespaces, Monaco's MDN references). Monaco is served from `dist/monaco` (checked); the loader's CDN
default is present in the bundle but overridden by `src/lib/monaco.ts` (checked) and blocked by the CSP.

## Admin notes (for the admin / install guide)

**Corporate proxy.** Studio needs a proxy only for the optional update check: cluster traffic is
direct (or through SSH). Settings > Network > Proxy:
- *System proxy* (default) uses `HTTPS_PROXY` / `HTTP_PROXY` / `NO_PROXY` from the environment Studio is
  started from, or the Java properties `https.proxyHost` / `https.proxyPort` / `http.nonProxyHosts`
  (e.g. via `JAVA_TOOL_OPTIONS`). OS proxy settings (Windows Internet Options, macOS Network
  preferences) and PAC/WPAD scripts are **not** read; apps started from the macOS Dock/Finder or the
  Windows Start menu usually do not see shell variables. In those cases choose *Manual*.
- *Manual*: host, port, optional user and password (kept in the OS keychain), and a no-proxy list.
  Basic authentication is supported; NTLM/Kerberos proxies need a local helper proxy (e.g. cntlm, px).
- "Also use this proxy for HTTP to cluster nodes" only matters for the jmx_exporter fallback when nodes
  are reachable only through the proxy.

**SSH through a proxy.** Per connection, Edit > SSH > "SSH through a proxy": HTTP CONNECT (most
corporate proxies; port 22 must be allowed by the proxy policy) or SOCKS5. With a bastion, the proxy
reaches the bastion; the bastion reaches the nodes.

**TLS-inspecting proxies and private CAs.** Put the corporate root CA(s) in one PEM file and set
Settings > Network > CA bundle; it is trusted for CQL TLS, JMX TLS and the update check in addition to
each connection's truststore. Or tick "Also trust the operating system's CA certificates" when the CA is
already deployed to the OS store. Reconnect open clusters after changing it. The update check reports a
TLS failure with this hint.

**Air-gapped sites.** Install from the offline installer (Java runtime, engine, UI and the Monaco
editor are all bundled; nothing is downloaded at first start). Tick Settings > Network > Offline mode:
the update check is off and no call leaves the machine except to your clusters. To verify: the packaged
UI has a CSP of `'self'` only, the desktop shell blocks and logs every non-engine request, and the build
fails on any external URL (`ui/scripts/check-offline.mjs`).

**Updates.** "Check for updates" (default on) asks GitHub's API for the latest release when Studio
starts, at most every 6 hours, and shows a banner with a link to the release page. Studio never
downloads or installs anything itself. Firewalls that allow `api.github.com:443` (and
`github.com` for the user's browser) are enough. Turning it off, or offline mode, stops the request.

**No telemetry.** Studio sends no usage data, crash reports or analytics anywhere.
