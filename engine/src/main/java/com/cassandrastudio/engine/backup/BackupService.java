package com.cassandrastudio.engine.backup;

import com.cassandrastudio.engine.backup.BackupSettings.Privilege;
import com.cassandrastudio.engine.backup.BackupSettings.Provider;
import com.cassandrastudio.engine.conn.ConnectionRepository;
import com.cassandrastudio.engine.cql.ClusterService.ClusterInfo;
import com.cassandrastudio.engine.cql.ClusterService.NodeInfo;
import com.cassandrastudio.engine.guard.ActionGuard;
import com.cassandrastudio.engine.jobs.Job;
import com.cassandrastudio.engine.jobs.JobContext;
import com.cassandrastudio.engine.jobs.JobService;
import com.cassandrastudio.engine.metrics.Topology;
import com.cassandrastudio.engine.model.ConnectionConfig;
import com.cassandrastudio.engine.ssh.NodeShell;
import com.cassandrastudio.engine.store.Database;
import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.management.MBeanServerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backups (BAK-1..3): the provider per cluster (estate scripts, Medusa, snapshots), detection over
 * SSH, the catalogue, and "run a backup now" for a cluster, a DC or a node as a job with per-node
 * progress and results. Every run goes through the ActionGuard (category "backup") with the exact
 * command per node as the preview.
 */
public final class BackupService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(BackupService.class);
    static final Duration LIST_TIMEOUT = Duration.ofSeconds(120);
    static final Duration DETECT_TIMEOUT = Duration.ofSeconds(20);
    static final int MAX_RUNS = 50;
    static final String HISTORY_KEY = "backup.history/";
    private static final Pattern KEYSPACE = Pattern.compile("[A-Za-z0-9_]{1,48}");

    /** How the service reaches nodes; the engine wires SSH and JMX, tests fake them. */
    public interface Nodes {
        /** Standard output of {@code command} on {@code host} over SSH; throws with a readable message. */
        String exec(ConnectionConfig cfg, String host, String command, Duration timeout);

        /** Runs {@code call} on the node's JMX (operations connection, no read timeout). */
        <T> T jmx(ConnectionConfig cfg, NodeInfo node, JmxCall<T> call) throws Exception;

        /** Tables a full estate backup uploads (user keyspaces + system_schema/auth/distributed); 0 = unknown. */
        default int expectedTables(String connectionId) {
            return 0;
        }
    }

    @FunctionalInterface
    public interface JmxCall<T> {
        T call(MBeanServerConnection m) throws Exception;
    }

    private final Database db;
    private final ConnectionRepository connections;
    private final ActionGuard guard;
    private final JobService jobs;
    private final Topology topology;
    private final Nodes nodes;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    volatile Duration pollEvery = Duration.ofSeconds(2);

    /** How often a running node backup's output is polled (default 2 s). */
    public void pollEvery(Duration d) {
        pollEvery = d;
    }

    public BackupService(Database db, ConnectionRepository connections, ActionGuard guard, JobService jobs,
                         Topology topology, Nodes nodes) {
        this.db = db;
        this.connections = connections;
        this.guard = guard;
        this.jobs = jobs;
        this.topology = topology;
        this.nodes = nodes;
    }

    // ---- settings (BAK-1) --------------------------------------------------------------------

    public BackupSettings settings(String connectionId) {
        connections.get(connectionId);
        return BackupSettings.load(db, connectionId);
    }

    public BackupSettings saveSettings(String connectionId, BackupSettings s) {
        connections.get(connectionId);
        BackupSettings v = (s == null ? BackupSettings.DEFAULTS : s).validated();
        BackupSettings.save(db, connectionId, v);
        return v;
    }

    // ---- detection (BAK-1) -------------------------------------------------------------------

    public record NodeDetection(String node, String datacenter, String state, boolean reachable, String error,
                                String host, List<String> scripts, String scriptsOnPath, boolean configPresent,
                                boolean configReadable, String medusa, String medusaConfig, boolean sudo) {}

    public record Detection(List<NodeDetection> nodes, boolean jmxSnapshots, String jmxError, Provider recommended,
                            Privilege recommendedPrivilege, String recommendedScriptDir, List<String> notes) {}

    public Detection detect(String connectionId) {
        ConnectionConfig cfg = connections.get(connectionId);
        BackupSettings s = settings(connectionId);
        List<NodeInfo> all = topology.info(connectionId).nodes();
        String cmd = detectCommand(s);
        List<NodeDetection> found = parallel(all, n -> {
            try {
                return parseDetection(n, nodes.exec(cfg, n.address(), cmd, DETECT_TIMEOUT));
            } catch (RuntimeException e) {
                return new NodeDetection(n.address(), n.datacenter(), n.state(), false, readable(e), null, List.of(),
                        null, false, false, null, null, false);
            }
        });
        String jmxError = null;
        boolean jmxOk = false;
        NodeInfo up = all.stream().filter(n -> "UP".equals(n.state())).findFirst().orElse(null);
        if (up == null) {
            jmxError = "no node is up";
        } else {
            try {
                nodes.jmx(cfg, up, m -> m.getMBeanInfo(SnapshotJmx.STORAGE));
                jmxOk = true;
            } catch (Exception e) {
                jmxError = readable(e);
            }
        }
        return recommend(found, jmxOk, jmxError, s);
    }

    static String detectCommand(BackupSettings s) {
        BackupSettings e = s.effective();
        StringBuilder b = new StringBuilder("d=" + NodeShell.quote(e.scriptDir()) + "; for f in");
        for (String f : EstateFormat.SCRIPTS) b.append(' ').append(f);
        b.append("; do [ -f \"$d/$f\" ] && echo \"SCRIPT $f\"; done;")
                .append(" p=$(command -v ").append(EstateFormat.FULL_SCRIPT).append(" 2>/dev/null) && echo \"ONPATH $p\";")
                .append(" c=").append(NodeShell.quote(e.configFile())).append(";")
                .append(" [ -e \"$c\" ] && echo CONFIG_PRESENT; [ -r \"$c\" ] && echo CONFIG_READABLE;")
                .append(" m=$(command -v ").append(NodeShell.quote(e.medusaCommand())).append(" 2>/dev/null) && echo \"MEDUSA $m\";")
                .append(" [ -f /etc/medusa/medusa.ini ] && echo MEDUSA_CONFIG /etc/medusa/medusa.ini;")
                .append(" sudo -n true 2>/dev/null && echo SUDO;")
                .append(" echo \"HOST $(hostname -s)\"");
        return b.toString();
    }

    static NodeDetection parseDetection(NodeInfo n, String out) {
        List<String> scripts = new ArrayList<>();
        String onPath = null, medusa = null, medusaCfg = null, host = null;
        boolean present = false, readable = false, sudo = false;
        for (String line : out.split("\\R")) {
            if (line.startsWith("SCRIPT ")) scripts.add(line.substring(7).strip());
            else if (line.startsWith("ONPATH ")) onPath = line.substring(7).strip();
            else if (line.equals("CONFIG_PRESENT")) present = true;
            else if (line.equals("CONFIG_READABLE")) readable = true;
            else if (line.startsWith("MEDUSA ")) medusa = line.substring(7).strip();
            else if (line.startsWith("MEDUSA_CONFIG ")) medusaCfg = line.substring(14).strip();
            else if (line.equals("SUDO")) sudo = true;
            else if (line.startsWith("HOST ")) host = line.substring(5).strip();
        }
        return new NodeDetection(n.address(), n.datacenter(), n.state(), true, null, host, scripts, onPath, present,
                readable, medusa, medusaCfg, sudo);
    }

    static Detection recommend(List<NodeDetection> found, boolean jmxOk, String jmxError, BackupSettings s) {
        List<NodeDetection> reached = found.stream().filter(NodeDetection::reachable).toList();
        List<String> notes = new ArrayList<>();
        boolean estate = !reached.isEmpty() && reached.stream().allMatch(d ->
                d.scripts().contains(EstateFormat.FULL_SCRIPT) && d.scripts().contains(EstateFormat.STORAGE_LIB));
        String scriptDir = s.effective().scriptDir();
        if (!estate && !reached.isEmpty() && reached.stream().allMatch(d -> d.scriptsOnPath() != null)) {
            String p = reached.get(0).scriptsOnPath();
            scriptDir = p.substring(0, Math.max(1, p.lastIndexOf('/')));
            estate = true;
            notes.add("The estate scripts are on the PATH in " + scriptDir + ", not in " + s.effective().scriptDir() + ".");
        }
        boolean medusa = !reached.isEmpty() && reached.stream().allMatch(d -> d.medusa() != null);
        boolean sudo = !reached.isEmpty() && reached.stream().allMatch(NodeDetection::sudo);
        Provider rec = estate ? Provider.ESTATE : medusa ? Provider.MEDUSA : jmxOk ? Provider.SNAPSHOT : null;
        if (reached.isEmpty()) notes.add("No node could be reached over SSH: only snapshots (JMX) can be used.");
        else if (reached.size() < found.size()) notes.add((found.size() - reached.size()) + " node(s) could not be reached over SSH.");
        if (estate && reached.stream().anyMatch(d -> !d.configPresent())) {
            notes.add("/etc/backup/config.json is missing on some nodes: the scripts there will refuse to run.");
        }
        if (estate && !sudo) {
            notes.add("sudo -n does not work for this SSH user on every node; the estate scripts need root. "
                    + "Choose 'run as the SSH user' only if that user may run them.");
        }
        if (medusa && estate) notes.add("Medusa is installed as well; the estate scripts are recommended as the estate standard.");
        return new Detection(found, jmxOk, jmxError, rec, sudo ? Privilege.SUDO : Privilege.NONE, scriptDir, notes);
    }

    // ---- catalogue (BAK-2) -------------------------------------------------------------------

    public record NodeListing(String node, String datacenter, boolean ok, String error, int backups, String method) {}

    public record Catalogue(Provider provider, long generatedAtMs, List<BackupEntry> backups, List<NodeListing> nodes,
                            List<String> notes) {}

    public Catalogue catalogue(String connectionId) {
        ConnectionConfig cfg = connections.get(connectionId);
        BackupSettings s = settings(connectionId);
        if (s.provider() == null) {
            throw new ApiException(409, "no_provider", "Choose a backup provider for this cluster first (Detect, then Save).");
        }
        List<NodeInfo> all = topology.info(connectionId).nodes();
        List<BackupEntry> entries = new ArrayList<>();
        List<NodeListing> listings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        switch (s.provider()) {
            case ESTATE -> {
                record R(List<BackupEntry> e, NodeListing l, boolean truncated) {}
                for (R r : parallel(all, n -> {
                    try {
                        EstateResult er = estateList(cfg, s, n);
                        return new R(er.entries, new NodeListing(n.address(), n.datacenter(), true, null,
                                er.entries.size(), er.method), er.truncated);
                    } catch (RuntimeException e) {
                        return new R(List.of(), new NodeListing(n.address(), n.datacenter(), false, readable(e), 0, null), false);
                    }
                })) {
                    entries.addAll(r.e);
                    listings.add(r.l);
                    if (r.truncated) notes.add(r.l.node() + ": only the newest " + EstateFormat.LIST_LIMIT + " backup sets are listed.");
                }
            }
            case MEDUSA -> {
                NodeInfo up = all.stream().filter(n -> "UP".equals(n.state())).findFirst()
                        .orElseThrow(() -> new ApiException(409, "no_node", "No node is up to run medusa on"));
                try {
                    entries.addAll(medusaList(cfg, s, up, notes));
                    listings.add(new NodeListing(up.address(), up.datacenter(), true, null, entries.size(), "medusa list-backups"));
                } catch (RuntimeException e) {
                    listings.add(new NodeListing(up.address(), up.datacenter(), false, readable(e), 0, "medusa list-backups"));
                }
            }
            case SNAPSHOT -> {
                record R(List<BackupEntry> e, NodeListing l) {}
                for (R r : parallel(all, n -> {
                    if (!"UP".equals(n.state())) {
                        return new R(List.of(), new NodeListing(n.address(), n.datacenter(), false, "node is " + n.state(), 0, "JMX"));
                    }
                    try {
                        List<BackupEntry> e = nodes.jmx(cfg, n, m -> SnapshotJmx.entries(
                                SnapshotJmx.rows(SnapshotJmx.details(m)), n.address(), null, n.datacenter()));
                        return new R(e, new NodeListing(n.address(), n.datacenter(), true, null, e.size(), "JMX getSnapshotDetails"));
                    } catch (Exception e) {
                        return new R(List.of(), new NodeListing(n.address(), n.datacenter(), false, readable(e), 0, "JMX"));
                    }
                })) {
                    entries.addAll(r.e);
                    listings.add(r.l);
                }
            }
        }
        Map<String, String> history = history(connectionId);
        List<BackupEntry> out = new ArrayList<>();
        for (BackupEntry e : entries) {
            String v = history.get(historyKey(e.provider(), e.node(), e.id()));
            out.add(v == null ? e : e.withSchemaVersion(v));
        }
        out.sort(Comparator.comparing(BackupEntry::timeMs, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(e -> e.node() == null ? "" : e.node()));
        notes.add("Schema version is known only for backups started from Studio (recorded at start).");
        return new Catalogue(s.provider(), System.currentTimeMillis(), out, listings, notes);
    }

    private record EstateResult(List<BackupEntry> entries, String method, boolean truncated) {}

    private EstateResult estateList(ConnectionConfig cfg, BackupSettings s, NodeInfo n) {
        String probeError;
        try {
            EstateFormat.Probe p = EstateFormat.parseProbe(nodes.exec(cfg, n.address(), EstateFormat.probeCommand(s),
                    LIST_TIMEOUT));
            if (p.error() == null) {
                return new EstateResult(EstateFormat.entries(p, n.address(), n.datacenter()), "backup-storage-lib.sh listing",
                        p.truncated());
            }
            probeError = p.error();
        } catch (RuntimeException e) {
            probeError = readable(e);
        }
        // A sudoers rule may allow only the scripts themselves: fall back to their read-only modes.
        try {
            String list = nodes.exec(cfg, n.address(), s.prefix() + NodeShell.quote(s.script(EstateFormat.RESTORE_SCRIPT))
                    + " --list-backups", LIST_TIMEOUT);
            EstateFormat.Listing l = EstateFormat.parseListBackups(list);
            JsonNode status = null;
            try {
                status = EstateFormat.parseStatusJson(nodes.exec(cfg, n.address(), s.prefix()
                        + NodeShell.quote(s.script(EstateFormat.STATUS_SCRIPT)) + " --json", LIST_TIMEOUT));
            } catch (RuntimeException e) {
                // exits 1 when there is no backup yet; the listing alone is still useful
            }
            return new EstateResult(EstateFormat.entriesFromListing(l, status, n.address(), n.datacenter()),
                    "restore-from-s3.sh --list-backups", false);
        } catch (RuntimeException e) {
            throw new IllegalStateException(probeError + " (fallback restore-from-s3.sh --list-backups: " + readable(e) + ")");
        }
    }

    private List<BackupEntry> medusaList(ConnectionConfig cfg, BackupSettings s, NodeInfo n, List<String> notes) {
        List<MedusaFormat.Listed> listed = MedusaFormat.parseList(nodes.exec(cfg, n.address(), MedusaFormat.listCommand(s),
                LIST_TIMEOUT));
        listed = listed.stream().sorted(Comparator.comparing(MedusaFormat.Listed::startedMs,
                Comparator.nullsLast(Comparator.reverseOrder()))).toList();
        List<BackupEntry> out = new ArrayList<>();
        int detailed = 0;
        for (MedusaFormat.Listed l : listed) {
            MedusaFormat.Status st = null;
            if (detailed < 10) {
                detailed++;
                try {
                    st = MedusaFormat.parseStatus(nodes.exec(cfg, n.address(), MedusaFormat.statusCommand(s, l.name()),
                            LIST_TIMEOUT));
                } catch (RuntimeException e) {
                    st = null;
                }
            }
            out.add(MedusaFormat.entry(l, st));
        }
        if (listed.size() > 10) notes.add("Size and node counts are read for the newest 10 Medusa backups only.");
        return out;
    }

    // ---- run now (BAK-3) ---------------------------------------------------------------------

    public enum Scope { CLUSTER, DC, NODE }

    public record RunRequest(Scope scope, String datacenter, String node, String mode, Integer concurrency,
                             String throttle, String name, List<String> keyspaces) {}

    public record NodeResult(String node, String datacenter, String state, Double progress, String message,
                             String command, String backupId, Integer exitCode, String summary, Long startedAtMs,
                             Long finishedAtMs) {}

    public record RunStatus(String jobId, Provider provider, String mode, int concurrency, List<NodeResult> nodes) {}

    /** Validated plan: nodes, the per-node command (preview) and the guard action. */
    record Plan(Provider provider, String mode, String name, List<String> keyspaces, int concurrency, List<NodeInfo> targets,
                List<NodeInfo> skipped, Map<String, String> commands, String title, ActionGuard.Action action) {}

    Plan plan(String connectionId, RunRequest req, BackupSettings s) {
        if (s.provider() == null) {
            throw new ApiException(409, "no_provider", "Choose a backup provider for this cluster first (Detect, then Save).");
        }
        if (req.scope() == null) throw ApiException.badRequest("scope is required (CLUSTER, DC or NODE)");
        ClusterInfo info = topology.info(connectionId);
        List<NodeInfo> scoped = switch (req.scope()) {
            case CLUSTER -> info.nodes();
            case DC -> {
                if (req.datacenter() == null) throw ApiException.badRequest("datacenter is required for scope DC");
                List<NodeInfo> l = info.nodes().stream().filter(n -> req.datacenter().equals(n.datacenter())).toList();
                if (l.isEmpty()) throw ApiException.badRequest("No nodes in datacenter " + req.datacenter());
                yield l;
            }
            case NODE -> {
                if (req.node() == null) throw ApiException.badRequest("node is required for scope NODE");
                yield info.nodes().stream().filter(n -> req.node().equals(n.address())).findFirst()
                        .map(List::of).orElseThrow(() -> ApiException.badRequest("Unknown node " + req.node()));
            }
        };
        int concurrency = req.concurrency() == null ? 1 : req.concurrency();
        if (concurrency < 1 || concurrency > 16) throw ApiException.badRequest("concurrency must be 1-16");
        List<NodeInfo> targets = scoped.stream().filter(n -> "UP".equals(n.state())).toList();
        List<NodeInfo> skipped = scoped.stream().filter(n -> !"UP".equals(n.state())).toList();
        if (targets.isEmpty()) throw new ApiException(409, "no_node", "None of the selected nodes is up");

        String mode = req.mode() == null ? defaultMode(s.provider()) : req.mode().toLowerCase(Locale.ROOT);
        String name = null;
        List<String> ks = req.keyspaces() == null ? List.of() : List.copyOf(req.keyspaces());
        Map<String, String> commands = new LinkedHashMap<>();
        String what;
        switch (s.provider()) {
            case ESTATE -> {
                if (!mode.equals("full") && !mode.equals("incremental")) {
                    throw ApiException.badRequest("mode must be full or incremental for the estate scripts");
                }
                if (req.throttle() != null && !EstateFormat.validThrottle(req.throttle())) {
                    throw ApiException.badRequest("throttle must look like 50M/s or 1G/s");
                }
                for (NodeInfo n : targets) commands.put(n.address(), EstateFormat.runCommand(s, mode, req.throttle()));
                what = (mode.equals("full") ? "Full" : "Incremental") + " backup (estate scripts)";
            }
            case MEDUSA -> {
                if (!mode.equals("full") && !mode.equals("differential")) {
                    throw ApiException.badRequest("mode must be full or differential for Medusa");
                }
                name = req.name() == null ? MedusaFormat.defaultName(System.currentTimeMillis()) : req.name();
                if (!MedusaFormat.NAME.matcher(name).matches()) {
                    throw ApiException.badRequest("name may contain letters, digits, '.', '_' and '-' only");
                }
                for (NodeInfo n : targets) commands.put(n.address(), MedusaFormat.backupNodeCommand(s, name, mode));
                what = "Medusa " + mode + " backup " + name;
            }
            default -> {
                if (!mode.equals("snapshot")) throw ApiException.badRequest("mode must be snapshot for the snapshot provider");
                name = req.name() == null ? "studio-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
                        .format(Instant.now().atOffset(ZoneOffset.UTC)) : req.name();
                if (!SnapshotJmx.TAG.matcher(name).matches()) {
                    throw ApiException.badRequest("snapshot tag may contain letters, digits, '.', '_' and '-' only");
                }
                for (String k : ks) {
                    if (!KEYSPACE.matcher(k).matches()) throw ApiException.badRequest("Invalid keyspace name: " + k);
                }
                for (NodeInfo n : targets) {
                    commands.put(n.address(), "nodetool -h " + n.address() + " snapshot -t " + name
                            + (ks.isEmpty() ? "" : " -- " + String.join(" ", ks)));
                }
                what = "Snapshot " + name;
            }
        }
        String where = req.scope() == Scope.NODE ? "node " + targets.get(0).address()
                : req.scope() == Scope.DC ? "datacenter " + req.datacenter() + " (" + targets.size() + " nodes)"
                : "the cluster (" + targets.size() + " nodes)";
        String title = what + " on " + where;
        List<String> preview = new ArrayList<>();
        commands.forEach((node, c) -> preview.add(node + ": " + c));
        List<String> warnings = new ArrayList<>();
        if (targets.size() > 1) {
            warnings.add(concurrency == 1 ? "Runs on one node at a time." : "Runs on up to " + concurrency + " nodes at a time.");
        }
        for (NodeInfo n : skipped) warnings.add(n.address() + " is " + n.state() + " and will be skipped.");
        if (s.provider() != Provider.SNAPSHOT) {
            warnings.add("Each node takes a snapshot and uploads it: expect extra disk, CPU and network load while it runs.");
        }
        ActionGuard.Action action = new ActionGuard.Action("backup", title, preview, warnings, false,
                targets.size() == 1 ? targets.get(0).address() : null);
        return new Plan(s.provider(), mode, name, ks, concurrency, targets, skipped, commands, title, action);
    }

    private static String defaultMode(Provider p) {
        return p == Provider.SNAPSHOT ? "snapshot" : "full";
    }

    public Job run(String connectionId, RunRequest req, ActionGuard.Confirmation confirmation) {
        ConnectionConfig cfg = connections.get(connectionId);
        BackupSettings s = settings(connectionId);
        Plan plan = plan(connectionId, req, s);
        guard.check(cfg, plan.action(), confirmation);
        Run run = new Run(plan);
        boolean cancellable = plan.provider() != Provider.SNAPSHOT;
        Job job = jobs.submit(new JobService.Spec(connectionId, "backup", plan.title(),
                plan.targets().size() == 1 ? plan.targets().get(0).address() : null, "backup", cancellable),
                ctx -> execute(cfg, s, run, ctx));
        run.jobId = job.id();
        runs.put(job.id(), run);
        while (runs.size() > MAX_RUNS) {
            runs.values().stream().filter(r -> r.finished).min(Comparator.comparingLong(r -> r.createdAt))
                    .ifPresentOrElse(r -> runs.remove(r.jobId), () -> runs.remove(runs.keySet().iterator().next()));
        }
        return job;
    }

    public RunStatus runStatus(String connectionId, String jobId) {
        Run r = runs.get(jobId);
        if (r == null || r.jobId == null) throw ApiException.notFound("backup run " + jobId);
        Job j = jobs.get(jobId);
        if (!connectionId.equals(j.connectionId())) throw ApiException.notFound("backup run " + jobId);
        return r.status();
    }

    /** One node's live state; mutable under the run's monitor. */
    static final class NodeRun {
        final NodeInfo node;
        final String command;
        String state = "QUEUED";
        Double progress = 0.0;
        String message;
        String backupId;
        Integer exitCode;
        String summary;
        Long startedAt;
        Long finishedAt;

        NodeRun(NodeInfo node, String command) {
            this.node = node;
            this.command = command;
        }
    }

    final class Run {
        final Plan plan;
        final List<NodeRun> nodes = new ArrayList<>();
        final long createdAt = System.currentTimeMillis();
        final String key = UUID.randomUUID().toString().substring(0, 8);
        volatile String jobId;
        volatile boolean finished;

        Run(Plan plan) {
            this.plan = plan;
            for (NodeInfo n : plan.targets()) nodes.add(new NodeRun(n, plan.commands().get(n.address())));
            for (NodeInfo n : plan.skipped()) {
                NodeRun nr = new NodeRun(n, null);
                nr.state = "SKIPPED";
                nr.message = "node is " + n.state();
                nr.progress = null;
                nodes.add(nr);
            }
        }

        synchronized RunStatus status() {
            List<NodeResult> l = new ArrayList<>();
            for (NodeRun n : nodes) {
                l.add(new NodeResult(n.node.address(), n.node.datacenter(), n.state, n.progress, n.message, n.command,
                        n.backupId, n.exitCode, n.summary, n.startedAt, n.finishedAt));
            }
            return new RunStatus(jobId, plan.provider(), plan.mode(), plan.concurrency(), l);
        }

        synchronized void update(NodeRun n, String state, Double progress, String message) {
            if (state != null) n.state = state;
            if (progress != null) n.progress = progress;
            if (message != null) n.message = message;
        }

        synchronized double overall() {
            double sum = 0;
            int count = 0;
            for (NodeRun n : nodes) {
                if ("SKIPPED".equals(n.state)) continue;
                count++;
                sum += isDone(n.state) ? 1.0 : n.progress == null ? 0 : n.progress;
            }
            return count == 0 ? 1.0 : sum / count;
        }

        synchronized String headline() {
            long done = nodes.stream().filter(n -> isDone(n.state)).count();
            long running = nodes.stream().filter(n -> "RUNNING".equals(n.state)).count();
            long active = nodes.stream().filter(n -> !"SKIPPED".equals(n.state)).count();
            return done + " of " + active + " nodes done" + (running > 0 ? ", " + running + " running" : "");
        }
    }

    private static boolean isDone(String state) {
        return "SUCCEEDED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state);
    }

    private Object execute(ConnectionConfig cfg, BackupSettings s, Run run, JobContext ctx) throws Exception {
        int expected = run.plan.provider() == Provider.ESTATE && run.plan.mode().equals("full")
                ? safeExpectedTables(cfg.id()) : 0;
        Semaphore permits = new Semaphore(run.plan.concurrency());
        List<Future<?>> futures = new ArrayList<>();
        List<NodeRun> active = run.nodes.stream().filter(n -> !"SKIPPED".equals(n.state)).toList();
        ctx.progress(0.0, run.headline());
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < active.size(); i++) {
                NodeRun nr = active.get(i);
                String runId = "backup-" + run.key + "-" + i;
                futures.add(exec.submit(() -> {
                    try {
                        permits.acquire();
                    } catch (InterruptedException e) {
                        finishNode(run, nr, "CANCELLED", "cancelled before it started", null);
                        return;
                    }
                    try {
                        if (ctx.cancelled()) {
                            finishNode(run, nr, "CANCELLED", "cancelled before it started", null);
                            return;
                        }
                        runNode(cfg, s, run, nr, runId, expected, ctx);
                    } finally {
                        permits.release();
                        ctx.progress(run.overall(), run.headline());
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    // Cancelled: the node tasks see ctx.cancelled() and stop their remote runs; wait for that.
                    awaitQuietly(futures);
                    throw new CancellationException("cancelled");
                } catch (ExecutionException e) {
                    LOG.info("backup node task failed: {}", e.getCause().toString());
                }
            }
        } finally {
            run.finished = true;
        }
        RunStatus st = run.status();
        List<NodeResult> failed = st.nodes().stream().filter(n -> "FAILED".equals(n.state())).toList();
        if (ctx.cancelled() || st.nodes().stream().anyMatch(n -> "CANCELLED".equals(n.state()))) {
            throw new CancellationException("cancelled");
        }
        if (!failed.isEmpty()) {
            StringBuilder m = new StringBuilder(failed.size() + " of "
                    + st.nodes().stream().filter(n -> !"SKIPPED".equals(n.state())).count() + " nodes failed: ");
            for (int i = 0; i < failed.size(); i++) {
                if (i > 0) m.append("; ");
                m.append(failed.get(i).node()).append(": ").append(failed.get(i).message());
            }
            throw new IllegalStateException(m.toString());
        }
        ctx.progress(1.0, run.headline());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", st.nodes());
        result.put("succeeded", st.nodes().stream().filter(n -> "SUCCEEDED".equals(n.state())).count());
        result.put("skipped", st.nodes().stream().filter(n -> "SKIPPED".equals(n.state())).count());
        return result;
    }

    private static void awaitQuietly(List<Future<?>> futures) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        for (Future<?> f : futures) {
            while (true) {
                try {
                    f.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    break;
                } catch (InterruptedException e) {
                    // keep waiting for the node tasks to stop their remote runs
                } catch (ExecutionException | CancellationException | TimeoutException e) {
                    break;
                }
            }
        }
    }

    private int safeExpectedTables(String connectionId) {
        try {
            return nodes.expectedTables(connectionId);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private void runNode(ConnectionConfig cfg, BackupSettings s, Run run, NodeRun nr, String runId, int expected,
                         JobContext ctx) {
        String addr = nr.node.address();
        String schemaVersion = nr.node.schemaVersion();
        synchronized (run) {
            nr.state = "RUNNING";
            nr.startedAt = System.currentTimeMillis();
            nr.message = "starting";
        }
        ctx.progress(run.overall(), run.headline());
        ctx.log("[" + addr + "] $ " + nr.command);
        try {
            switch (run.plan.provider()) {
                case SNAPSHOT -> {
                    nodes.jmx(cfg, nr.node, m -> {
                        SnapshotJmx.take(m, run.plan.name(), run.plan.keyspaces());
                        return null;
                    });
                    synchronized (run) {
                        nr.backupId = run.plan.name();
                        nr.summary = "snapshot " + run.plan.name() + " taken";
                    }
                    remember(cfg.id(), "snapshot", addr, run.plan.name(), schemaVersion);
                    finishNode(run, nr, "SUCCEEDED", "snapshot taken", 0);
                }
                case ESTATE, MEDUSA -> {
                    boolean estate = run.plan.provider() == Provider.ESTATE;
                    EstateFormat.Progress p = new EstateFormat.Progress(run.plan.mode().equals("full"), expected);
                    String[] lastLine = {null};
                    ctx.onCancel(() -> RemoteRun.kill((h, c, t) -> nodes.exec(cfg, h, c, t), addr, runId, s.prefix()));
                    int rc = RemoteRun.run((h, c, t) -> nodes.exec(cfg, h, c, t), addr, runId, nr.command, s.prefix(),
                            Duration.ofMinutes(s.effective().nodeTimeoutMinutes()), pollEvery, raw -> {
                                if (raw.startsWith("nohup: ")) return;
                                String line = estate ? p.accept(raw) : EstateFormat.stripAnsi(raw).strip();
                                if (line.isEmpty()) return;
                                ctx.log("[" + addr + "] " + line);
                                if (!estate && line.contains("ERROR")) p.lastError = line;
                                lastLine[0] = line;
                                synchronized (run) {
                                    nr.progress = estate ? p.fraction : null;
                                    nr.message = estate ? p.phase : line.length() > 160 ? line.substring(0, 160) + "…" : line;
                                    if (p.backupId != null) nr.backupId = p.backupId;
                                }
                                ctx.progress(run.overall(), run.headline());
                            }, ctx::cancelled);
                    synchronized (run) {
                        nr.exitCode = rc;
                        nr.summary = p.summary;
                        if (!estate) nr.backupId = run.plan.name();
                    }
                    if (rc == 0) {
                        String id = estate ? p.backupId : run.plan.name();
                        if (id != null && !(estate && p.nothingToDo)) remember(cfg.id(), estate ? "estate" : "medusa",
                                estate ? addr : null, id, schemaVersion);
                        finishNode(run, nr, "SUCCEEDED", p.summary != null ? p.summary : "finished", 0);
                    } else {
                        String why = p.lastError != null ? p.lastError : lastLine[0] != null ? lastLine[0] : "no output";
                        finishNode(run, nr, "FAILED", "exited with " + rc + ": " + why, rc);
                    }
                }
            }
        } catch (CancellationException e) {
            finishNode(run, nr, "CANCELLED", "cancelled; the remote command was signalled to stop", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finishNode(run, nr, "CANCELLED", "cancelled", null);
        } catch (Exception e) {
            finishNode(run, nr, "FAILED", readable(e), null);
        }
        ctx.log("[" + addr + "] " + nr.state + (nr.message == null ? "" : ": " + nr.message));
    }

    private void finishNode(Run run, NodeRun nr, String state, String message, Integer rc) {
        synchronized (run) {
            nr.state = state;
            nr.message = message;
            if (rc != null) nr.exitCode = rc;
            nr.finishedAt = System.currentTimeMillis();
            if ("SUCCEEDED".equals(state)) nr.progress = 1.0;
        }
    }

    // ---- snapshot clear ----------------------------------------------------------------------

    public Map<String, Object> clearSnapshot(String connectionId, String node, String tag, ActionGuard.Confirmation c) {
        ConnectionConfig cfg = connections.get(connectionId);
        if (tag == null || !SnapshotJmx.TAG.matcher(tag).matches()) throw ApiException.badRequest("Invalid snapshot tag");
        NodeInfo n = topology.info(connectionId).nodes().stream().filter(x -> x.address().equals(node)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Unknown node " + node));
        guard.check(cfg, new ActionGuard.Action("backup", "Clear snapshot " + tag + " on " + node,
                List.of("nodetool -h " + node + " clearsnapshot -t " + tag), List.of("The snapshot's files are deleted from the node."),
                true, node), c);
        try {
            nodes.jmx(cfg, n, m -> {
                SnapshotJmx.clear(m, tag, List.of());
                return null;
            });
        } catch (Exception e) {
            throw new ApiException(502, "clear_failed", "Could not clear snapshot " + tag + " on " + node + ": " + readable(e));
        }
        return Map.of("node", node, "tag", tag, "cleared", true);
    }

    // ---- schema versions of Studio-started backups -------------------------------------------

    static String historyKey(String provider, String node, String id) {
        return provider + "|" + (node == null ? "" : node) + "|" + id;
    }

    synchronized void remember(String connectionId, String provider, String node, String id, String schemaVersion) {
        if (db == null || schemaVersion == null) return;
        Map<String, String> h = new LinkedHashMap<>(history(connectionId));
        h.put(historyKey(provider, node, id), schemaVersion);
        while (h.size() > 1000) h.remove(h.keySet().iterator().next());
        db.update("INSERT OR REPLACE INTO settings(key, value) VALUES (?, ?)", HISTORY_KEY + connectionId, Json.write(h));
    }

    Map<String, String> history(String connectionId) {
        if (db == null) return Map.of();
        List<Map<String, Object>> rows = db.query("SELECT value FROM settings WHERE key=?", HISTORY_KEY + connectionId);
        if (rows.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        try {
            JsonNode n = Json.MAPPER.readTree(String.valueOf(rows.get(0).get("value")));
            if (n instanceof ObjectNode o) o.properties().forEach(e -> out.put(e.getKey(), e.getValue().asText()));
        } catch (Exception e) {
            return Map.of();
        }
        return out;
    }

    // ---- helpers -----------------------------------------------------------------------------

    private <T> List<T> parallel(List<NodeInfo> list, Function<NodeInfo, T> f) {
        List<Future<T>> fs = new ArrayList<>();
        for (NodeInfo n : list) fs.add(pool.submit(() -> f.apply(n)));
        List<T> out = new ArrayList<>();
        for (Future<T> fu : fs) {
            try {
                out.add(fu.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(503, "interrupted", "Interrupted");
            } catch (ExecutionException e) {
                throw new IllegalStateException(readable(e.getCause()));
            }
        }
        return out;
    }

    static String readable(Throwable e) {
        if (e instanceof Exception ex && (e instanceof javax.management.JMException)) return SnapshotJmx.message(ex);
        String m = e.getMessage();
        if (m == null || m.isBlank()) return e.getClass().getSimpleName();
        m = withoutCommand(m);
        return m.length() > 400 ? m.substring(0, 400) + "…" : m;
    }

    /** SSH errors quote the whole (long) command; keep only what went wrong. */
    static String withoutCommand(String m) {
        for (String marker : List.of("' over SSH: ", "' exited with ", "' did not finish within ")) {
            int i = m.lastIndexOf(marker);
            if (i > 0 && (m.startsWith("'") || m.startsWith("cannot run '"))) {
                String rest = m.substring(i + 2);
                return rest.startsWith("over SSH: ") ? "SSH: " + rest.substring(10) : rest;
            }
        }
        return m;
    }

    public void forget(String connectionId) {
        runs.values().removeIf(r -> r.finished && r.jobId != null && connectionId.equals(jobConnection(r.jobId)));
    }

    private String jobConnection(String jobId) {
        try {
            return jobs.get(jobId).connectionId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
