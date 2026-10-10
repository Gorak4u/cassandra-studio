# Install and admin guide

How to install, upgrade and run Cassandra Studio 1.0, what it needs on your machine and on the
Cassandra nodes, where it keeps its data, and how to fix common problems. For using the app, see
the [user guide](user-guide.md).

## Installers

Download the file for your system from the project's GitHub Releases page. Each file has a
`.sha256` checksum next to it.

| System | File | Install |
|---|---|---|
| Windows 10/11 (x64) | `CassandraStudio-<version>-win-x64.exe` | Run it. It installs for the current user (no admin rights needed); you can change the folder. |
| macOS 12+ Apple Silicon | `CassandraStudio-<version>-mac-arm64.dmg` | Open it and drag Cassandra Studio to Applications. |
| macOS 12+ Intel | `CassandraStudio-<version>-mac-x64.dmg` | As above. |
| Ubuntu 20.04+ / Debian | `CassandraStudio-<version>-linux-amd64.deb` | `sudo apt install ./CassandraStudio-<version>-linux-amd64.deb` |
| RHEL / Rocky 8+ | `CassandraStudio-<version>-linux-x86_64.rpm` | `sudo dnf install ./CassandraStudio-<version>-linux-x86_64.rpm` |
| Any Linux (x64) | `CassandraStudio-<version>-linux-x86_64.AppImage` | `chmod +x` the file and run it. |

The installers are built by the release workflow (`.github/workflows/release.yml`) on GitHub's
Windows, macOS and Ubuntu runners; each build starts the packaged engine once as a smoke test before
it is published.

**Code signing.** The workflow signs and notarises when signing certificates are configured. Until
the project's Apple Developer ID and Windows code-signing certificate are in place (NFR-SIGN), the
installers are unsigned:

- Windows: SmartScreen warns about an unknown publisher. Choose *More info → Run anyway*.
- macOS: the app is ad-hoc signed only. After copying it to Applications, run once:
  `xattr -cr "/Applications/Cassandra Studio.app"`.

**Verify the download** before installing, especially when you carry it into a closed network:

```bash
sha256sum -c CassandraStudio-<version>-linux-amd64.deb.sha256        # Linux
shasum -a 256 CassandraStudio-<version>-mac-arm64.dmg                # macOS: compare with the .sha256 file
```

On Windows: `Get-FileHash .\CassandraStudio-<version>-win-x64.exe -Algorithm SHA256` in
PowerShell, and compare with the `.sha256` file.

### Where the app is installed

| System | Program folder |
|---|---|
| Windows | `%LOCALAPPDATA%\Programs\Cassandra Studio` unless you chose another folder |
| macOS | `/Applications/Cassandra Studio.app` |
| deb / rpm | `/opt/Cassandra Studio`, started with `cassandra-studio` |
| AppImage | wherever you keep the file |

Uninstalling removes the program but not your data folder (below).

## Bundled runtime

Nothing else needs to be installed (NFR-PLAT): the installer contains

- a Java 21 runtime made with `jlink` (in the program's `resources/runtime` folder),
- Studio's engine (`resources/engine/cassandra-studio-engine-all.jar`), and
- the user interface (`resources/ui`), served by the engine.

When Studio starts, the desktop shell starts the engine with the bundled Java (`-Xmx1g`). The engine
listens on `127.0.0.1` only, on a free port, and requires a random token generated for each launch,
so other users and machines cannot use it. Closing the window stops the engine.

Studio needs no Cassandra, cqlsh or nodetool on your machine: it speaks CQL, JMX and SSH itself.
The Linux packages declare no dependencies; on a desktop install the libraries Electron needs (GTK 3,
NSS, libsecret and the usual X11 libraries) are already present. On a minimal system install them
from your distribution first.

## Data and config locations

Studio's data folder (NFR-DATA):

| System | Folder |
|---|---|
| Windows | `%APPDATA%\CassandraStudio` |
| macOS | `~/Library/Application Support/CassandraStudio` |
| Linux | `$XDG_DATA_HOME/cassandra-studio`, by default `~/.local/share/cassandra-studio` |

It contains:

| File | What |
|---|---|
| `studio.db` (with `studio.db-wal` and `studio.db-shm` while Studio runs) | SQLite database: folders and connections (no secrets), query history, audit log, saved scripts, and per-cluster settings (health thresholds, backup provider, diagnostics settings, Hiera settings) |
| `secret.key`, `secrets.json` | Only when no OS keychain is usable: the encrypted secret store and its key (owner-only permissions) |

The desktop shell keeps its own small browser profile (for example the light/dark theme choice) in
`%APPDATA%\Cassandra Studio` (Windows), `~/Library/Application Support/Cassandra Studio` (macOS) or
`~/.config/Cassandra Studio` (Linux).

Not stored on disk: monitoring history, jobs and their logs, thread dumps, GC log analyses and
collected config. They live in memory and are gone when Studio quits.

Bulk unload files go where you choose; a relative path is under your Downloads folder
(`XDG_DOWNLOAD_DIR` or `~/Downloads`). Reject files from a load are written next to the source file.

### Back up and restore Studio's own data

1. Quit Studio (so the database is not being written).
2. Copy the whole data folder.

To restore, quit Studio and put the copy back. Secrets are not in the folder when an OS keychain is
used: on the same machine and user they are still in the keychain; on another machine enter the
passwords again per connection. To move only connections, use **Export connections** / **Import**
in the top bar (without secrets).

## Logs

The engine writes its log to standard error; v1.0 writes no log file. To see it:

- Linux: start `cassandra-studio` (or the AppImage) from a terminal.
- macOS: start `"/Applications/Cassandra Studio.app/Contents/MacOS/Cassandra Studio"` from Terminal.
- Windows: the desktop app has no console. If the engine stops, Studio shows a dialog with the
  engine's last output. To capture a full log, start the engine by hand (next paragraph).

To run the engine by hand on any system, with the bundled Java, and use Studio in a browser:

```bash
cd "<program folder>/resources"          # macOS: "/Applications/Cassandra Studio.app/Contents/Resources"
runtime/bin/java -Dcom.sun.jndi.rmiURLParsing=legacy -jar engine/cassandra-studio-engine-all.jar \
  --ui-dir ui --port 8080 --token mytoken 2> studio-engine.log
# then open http://127.0.0.1:8080/#token=mytoken
```

It uses the same data folder as the desktop app; do not run both at the same time.

Cluster-side errors are also visible in the app: every failed operation keeps its reason in the
job log and in the audit log.

## Upgrades and downgrades

- **Upgrade**: install the new version over the old one (same steps as a new install). Your data
  folder is kept. On first start, Studio upgrades its database automatically: schema changes are
  numbered migrations, applied in order inside a transaction and recorded in the database, so an
  interrupted upgrade is rolled back and retried on the next start.
- **Downgrade**: an older Studio refuses to open a database written by a newer one, with the message
  *"Studio database is version N but this Studio only knows up to M. It was written by a newer
  Studio; upgrade Studio instead of downgrading."* It never opens or changes such a database, so
  a downgrade cannot corrupt it. To go back to an older version, back up the data folder before you
  upgrade (see above) and restore that copy after reinstalling the older version.
- Connection export files carry a format version; an older Studio refuses a file from a newer one
  with a clear message.

## Keychain

Secrets go to the OS keychain under the service name `cassandra-studio`, one entry per secret
(`conn/<connection id>/<secret name>`):

- **Windows**: Credential Manager (Windows Credentials).
- **macOS**: the login keychain. macOS may ask once to allow access; choose *Always Allow*.
- **Linux**: the Secret Service API (GNOME Keyring, or KWallet with its Secret Service bridge). The
  keyring must be running and unlocked in your desktop session.

At start-up Studio writes and deletes a test entry. If that fails, it falls back to the encrypted
file store in the data folder and logs `OS keychain unavailable (...); using encrypted file store`.
The footer of the connection tree shows the store in use. Deleting a connection deletes its secrets.

## Offline and air-gapped install

The installers are complete and need no network access to install or run:

- Java, the engine and the UI are inside the installer; nothing is downloaded at start-up or later.
- Studio sends no telemetry and checks for no updates in v1.0.
- The in-app help is bundled with the app.

To install in a closed network, copy the installer and its `.sha256` file across, verify the
checksum (see [Installers](#installers)), and install as usual. On Linux, install the desktop
libraries mentioned under [Bundled runtime](#bundled-runtime) from your internal mirror if missing.

## Corporate proxy and custom CA bundles

Studio connects only to your clusters: CQL to the nodes, SSH to the nodes or a jump host, JMX
directly or through SSH, and HTTP to a node's jmx_exporter. It makes no connections to the internet.

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

## Node prerequisites (SSH and JMX)

What the Cassandra nodes need, by feature:

| Feature | Needs on the node |
|---|---|
| Query, schema, roles, bulk | CQL native port (9042) reachable from your machine; credentials and TLS as the cluster requires |
| Monitoring | JMX (see below); for disk usage also SSH |
| Operations, thread dumps, top threads, hot partitions, histograms | JMX through an SSH tunnel or direct JMX (not jmx_exporter) |
| Config collection | JMX; SSH for OS limits and for `cassandra.yaml` on Cassandra 3.x |
| GC logs from a node | JMX to find the log, SSH to read it (read permission on the GC log files) |
| `system.log` warnings, tombstone scan | SSH, read permission on the log; the estate's `tombstone-scan.sh` for the scan |
| Backups (estate scripts, Medusa) | SSH; the scripts or `medusa` installed; usually `sudo -n` (below) |
| Backups (snapshots) | JMX |

### JMX

- **Localhost-only JMX (`LOCAL_JMX=yes`, the Cassandra default and the estate's setup)**: choose
  *SSH tunnel to localhost:7199*. Studio logs in over SSH and connects to `127.0.0.1:7199` on the
  node; nothing else needs to change on the node. JMX authentication, if enabled, works as usual
  (JMX username and password on the connection).
- **Remote JMX (`LOCAL_JMX=no`)**: choose *Direct JMX/RMI*. The JMX port (and RMI port, if it is
  different) must be reachable from your machine, and `java.rmi.server.hostname` must be an address
  your machine can reach. Tick *JMX over SSL* when `com.sun.management.jmxremote.ssl` is on.
- **jmx_exporter fallback**: if the Prometheus jmx_exporter Java agent runs on the nodes (port 7071
  by default), Studio reads it automatically when JMX fails, or always when the connection's method
  is *jmx_exporter*. That gives monitoring only, no operations (assumption A-2).

### SSH

- An SSH user that may log in to every node (or through the jump host), with key, ssh-agent or
  password authentication. Host keys are checked against `~/.ssh/known_hosts` (or the file you set)
  unless you turn checking off; Studio never writes to known_hosts. Add keys with
  `ssh-keyscan -p <port> <node> >> ~/.ssh/known_hosts`.
- With a jump host, the jump host must reach each node's SSH port.
- Studio runs only read commands (`df`, `cat`, `grep`, reading `/proc`) unless you start a backup or
  a tombstone scan. Commands have timeouts and run one SSH session per node.

### sudo for the estate scripts

The estate's backup scripts and their root-only config (`/etc/backup/config.json`) need root, and
`cass-ops` refuses to run as another user. With **Run as: root via sudo -n** (the default for the
estate provider) Studio prefixes those commands with `sudo -n`, so the SSH user needs passwordless
sudo for at least these commands (adjust the directory to your install):

```text
studio ALL=(root) NOPASSWD: /usr/local/bin/full-backup-to-s3.sh, /usr/local/bin/incremental-backup-to-s3.sh, \
    /usr/local/bin/restore-from-s3.sh --list-backups, /usr/local/bin/backup-status.sh --json
```

For a full catalogue (sizes, retention, object lock) Studio runs a read-only listing script that
sources the estate's `backup-storage-lib.sh`, run as `sudo -n bash -c '…'`; that needs sudo rights for
`bash` (in effect a root shell). When
sudo allows only the scripts, the catalogue falls back to `restore-from-s3.sh --list-backups` and
`backup-status.sh --json`. **Detect on nodes** in the Backups panel shows what sudo allows.

Backup runs start detached on the node (`setsid nohup`, output in `/tmp/cassandra-studio-<uid>/`),
so a dropped SSH session does not stop them.

## Troubleshooting

The messages below are what Studio shows; `<node>` is the node address.

### Connecting (CQL)

| Message | Meaning and fix |
|---|---|
| `Cannot connect to <name>: All nodes failed: <host>: <reason>` | No contact point answered. Check the address and port, firewall, and whether the node advertises an address your machine can reach (`broadcast_rpc_address`). |
| `… Authentication error …` / `Provided username … and/or password are incorrect` | Wrong CQL credentials; edit the connection and retype the password. |
| SSL / handshake errors | TLS is on in the cluster but not in the connection, or the truststore does not contain the CA. Use a PEM bundle with the CA, or turn off *Verify node hostnames* if the certificates do not name the node addresses. |
| `Missing or wrong engine token` | You opened the engine's URL without the `#token=…` part (only when running the engine by hand). |

### JMX

| Message | Meaning and fix |
|---|---|
| `JMX not listening on <node>:7199 (from the node: …)` | The SSH tunnel works but nothing listens on the node's JMX port: Cassandra is down, or JMX uses another port. |
| `JMX not listening on <node>:7199` | Direct JMX: the port is closed or blocked. If JMX is localhost-only, use the SSH tunnel method. |
| `timed out after N s connecting to JMX on <node>:7199` | A firewall drops the traffic, or (direct JMX) the RMI server advertises an address you cannot reach; check `java.rmi.server.hostname`. |
| `JMX on <node>:7199 requires a user name and password` / `JMX login failed for user <u> on <node>:7199` | JMX authentication is on: set or fix the JMX username and password. |
| `JMX on <node>:7199 closed the connection (is JMX using SSL?)` | JMX expects SSL: tick *JMX over SSL*. |
| `TLS handshake with JMX on <node>:7199 failed: …` | The truststore does not trust the node's JMX certificate. |
| `no JMX server named 'jmxrmi' on <node>:7199` | The port is not a JMX registry (wrong port). |
| `… (next retry in N s)` | Studio backs off after failures and retries by itself; the node shows as unreachable meanwhile. |
| `jmx_exporter not listening on <node>:7071` / `jmx_exporter on <node>:7071 answered HTTP 404` | The fallback exporter is not running or uses another port; set the jmx_exporter port. |
| `Operations need JMX; this connection reaches nodes via EXPORTER …` (`jmx_required`) | Set the connection's JMX method to SSH tunnel or direct. |

### SSH

| Message | Meaning and fix |
|---|---|
| `SSH user name is not set for this connection` / `SSH key file is not set …` / `SSH password is not set …` | Fill in the SSH tab. |
| `SSH auth failed for user <u> (agent authentication)` (or key / password) | Wrong user, key not authorised on the node, or wrong password or passphrase. |
| `no ssh-agent: SSH_AUTH_SOCK is not set; start ssh-agent and ssh-add your key, …` | Start an agent and add your key, or switch to *Private key file*. On Windows: `no ssh-agent: start the Windows 'OpenSSH Authentication Agent' service and …`. |
| `ssh-agent has no keys: ssh-add your key first` | Run `ssh-add`. |
| `host key for <host> not in known_hosts <file> (…); add it with ssh-keyscan, or turn off strict host key checking` | Add the node's key with `ssh-keyscan`. |
| `host key for <host> does not match <file> (got …; the key changed or someone is intercepting the connection)` | The node's key changed. Confirm with the node's owner before updating known_hosts. |
| `SSH connection to <host>:<port> refused` / `no route to host <host>` / `unknown host <host>` | Wrong address or port, or a firewall. |
| `jump host <jump> cannot reach <host>:<port> …` | The bastion cannot reach the node's SSH port. |
| `'<command>' exited with <n>: …` / `'<command>' did not finish within N s` | A command on the node failed or hung; the message has the command's own error (for example a permission problem reading a log). |

### Features

| Message | Meaning and fix |
|---|---|
| `'<name>' is a read-only connection; … is not allowed.` | The connection is read-only. Untick *Read-only* to make changes. |
| `PRODUCTION: type the connection name '<name>' to confirm.` | Type the connection name exactly in the confirmation dialog. |
| `… is not available on this Cassandra version` | That nodetool operation does not exist on the node's Cassandra version. |
| `Monitoring is not started for this cluster` | Open the Monitoring panel (or click Resume). |
| `No configuration collected yet: run Collect first` | Click **Collect config** in the Config panel. |
| `Choose a backup provider for this cluster first (Detect, then Save).` | Set up the provider in the Backups panel. |
| `No GC events found in …: is it a GC log …` | The file is not a GC log, or uses a format Studio does not parse. |
| `<file> already exists. Choose another name or allow overwriting.` | Bulk unload target exists; pick another name or tick overwrite. |
| `This job cannot be cancelled` | The job has no safe way to stop (for example a snapshot run); wait for it to finish. |
| `Studio database is version N but this Studio only knows up to M …` | You started an older Studio on a newer database; see [Upgrades and downgrades](#upgrades-and-downgrades). |
| `Secret could not be decrypted (key file changed?)` | The encrypted file store's `secret.key` no longer matches `secrets.json`; re-enter the passwords. |

### The app does not start

- *"Cassandra Studio could not start … Engine did not start within 60 s"* or *"The engine stopped
  (exit code N)"*: the dialog shows the engine's last output. Common causes: another program locks
  the data folder (a second Studio started from a script or by hand), or the data folder is not
  writable. Run the engine by hand (see [Logs](#logs)) to see the full error.
- Only one Studio window runs per user; starting it again focuses the existing window.
