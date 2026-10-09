package com.cassandrastudio.engine.gclog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streaming GC log parser for both log formats (GCL-1):
 * <ul>
 *   <li>Java 8: -XX:+PrintGCDetails with PrintGCDateStamps and/or PrintGCTimeStamps, including
 *       PrintTenuringDistribution, PrintHeapAtGC and PrintGCApplicationStoppedTime noise, ParNew/CMS
 *       (phases, concurrent mode failure, promotion failed), Parallel, Serial and G1.</li>
 *   <li>Java 9+ unified logging (-Xlog:gc*), any decorations (time, utctime, uptime, uptimemillis,
 *       level, tags, pid, tid), for G1, CMS (Java 11), Parallel, Serial, ZGC (also generational,
 *       Java 21) and Shenandoah.</li>
 * </ul>
 * Lines it does not know are counted and skipped. Feed lines in file order (oldest file first),
 * then call {@link #finish}. Not thread-safe.
 */
public final class GcLogParser {
    private static final String NUM = "(\\d+(?:[.,]\\d+)?)";
    private static final String MEM = NUM + "([BKMGT])";

    // unified logging
    private static final Pattern U_PAUSE = Pattern.compile(
            "^Pause (.+?)(?: " + MEM + "->" + MEM + "\\(" + MEM + "\\))? " + NUM + "ms$");
    private static final Pattern U_CONC = Pattern.compile(
            "^(Concurrent(?: [A-Za-z][A-Za-z -]*?)?)(?: \\((.*)\\))?(?: " + MEM + "->" + MEM + "\\(" + MEM + "\\))? "
                    + NUM + "ms$");
    private static final Pattern Z_COLL = Pattern.compile(
            "^(Garbage|Major|Minor) Collection \\((.+)\\) " + MEM + "\\(\\d+%\\)->" + MEM + "\\(\\d+%\\)(?: " + NUM + "s)?$");
    private static final Pattern Z_START = Pattern.compile("^(Garbage|Major|Minor) Collection \\((.+)\\)$");
    private static final Pattern REGIONS = Pattern.compile("^(Eden|Survivor|Old|Humongous|Archive) regions: (\\d+)->(\\d+)(?:\\((\\d+)\\))?");
    private static final Pattern U_GEN = Pattern.compile(
            "^(PSYoungGen|ParOldGen|PSOldGen|ParNew|DefNew|CMS|Tenured): " + MEM + "(?:\\(" + MEM + "\\))?->" + MEM + "\\(" + MEM + "\\)");
    private static final Pattern U_META = Pattern.compile(
            "^Metaspace: " + MEM + "(?:\\(" + MEM + "\\))?->" + MEM + "\\(" + MEM + "\\)");
    private static final Pattern TRACE_META = Pattern.compile("^\\s*Metaspace\\s+used (\\d+)K, (?:capacity \\d+K, )?committed (\\d+)K");
    private static final Pattern TRACE_REGION = Pattern.compile("region size (\\d+)K");
    private static final Pattern REGION_SIZE = Pattern.compile("^Heap [Rr]egion [Ss]ize: " + MEM);
    private static final Pattern MAX_CAPACITY = Pattern.compile("^(?:Heap )?Max Capacity: " + MEM);
    private static final Pattern MAX_HEAP_BYTES = Pattern.compile("Maximum heap (\\d+)");
    private static final Pattern SP_OLD = Pattern.compile(
            "Total time for which application threads were stopped: " + NUM + " seconds(?:, Stopping threads took: " + NUM + " seconds)?");
    private static final Pattern SP_NEW = Pattern.compile(
            "^Safepoint \"([^\"]+)\", Time since last: \\d+ ns, Reaching safepoint: (\\d+) ns, .*Total: (\\d+) ns");
    private static final Pattern STALL = Pattern.compile("^(?:[yYO]: )?(Allocation|Relocation) Stall \\(.*\\) " + NUM + "ms$");

    // Java 8
    private static final Pattern J8_CONC = Pattern.compile(
            "(?:(\\d{4}-\\d\\d-\\d\\dT[\\d:.]+[+-]\\d{2}:?\\d{2}): )?(?:(\\d+[.,]\\d+): )?\\[CMS-concurrent-([a-z-]+?)(?:-start)?(?:: "
                    + NUM + "/" + NUM + " secs)?\\]");
    private static final Pattern J8_TIMES = Pattern.compile("\\s*\\[Times: [^\\]]*\\]");
    private static final Pattern J8_ERGO = Pattern.compile("(?:\\d+[.,]\\d+: )?\\[G1Ergonomics[^\\]]*\\]");
    private static final Pattern J8_GEN = Pattern.compile(
            "\\[(ParNew|DefNew|PSYoungGen|ASParNew|ASCMS|CMS|ParOldGen|PSOldGen|Tenured|Metaspace|PSPermGen|CMS Perm|Perm)"
                    + "(?:\\s*\\(([^)]*)\\))?\\s*: (\\d+)K(?:\\((\\d+)K\\))?->(\\d+)K\\((\\d+)K\\)");
    private static final Pattern J8_SECS = Pattern.compile(NUM + " secs\\]");
    private static final Pattern HEAP_TRIPLE = Pattern.compile(MEM + "->" + MEM + "\\(" + MEM + "\\)");
    private static final Pattern J8_HEAP_PAIR = Pattern.compile("(\\d+)K\\((\\d+)K\\), " + NUM + " secs\\]");
    private static final Pattern J8_G1_DETAIL = Pattern.compile(
            "\\[Eden: " + MEM + "\\(" + MEM + "\\)->" + MEM + "\\(" + MEM + "\\) Survivors: " + MEM + "->" + MEM
                    + " Heap: " + MEM + "\\(" + MEM + "\\)->" + MEM + "\\(" + MEM + "\\)\\]");
    private static final Pattern J8_G1_META = Pattern.compile("\\[Metaspace: (\\d+)K->(\\d+)K\\((\\d+)K\\)\\]");
    private static final Pattern J8_VERSION = Pattern.compile("JRE \\(([^)]+)\\)");
    private static final Pattern J8_MAXHEAP = Pattern.compile("-XX:MaxHeapSize=(\\d+)");

    private final GcLog log = new GcLog();
    private long java8Lines, unifiedLines, recognised;

    // the stamps of the current line
    private Long curTs;
    private Double curUp;
    private Long firstTs;
    private Double firstUp;
    private Long lastTs;
    private Double lastUp;
    private boolean everyLineDated = true;

    // Java 8: a record spread over several lines, and PrintHeapAtGC blocks
    private StringBuilder pending;
    private Long pendTs;
    private Double pendUp;
    private int j8HeapSection; // 0 none, 1 before, 2 after
    private Long j8MetaBefore;
    private GcEvent lastJ8;

    // unified: per GC id, what came before the summary line, and the summary event
    private final Map<Integer, GcEvent> pre = bounded();
    private final Map<Integer, GcEvent> done = bounded();
    private final Map<Integer, Long[]> zStartTs = bounded();
    private final Map<Integer, Double> zStartUp = bounded();
    private int uHeapSection; // 0 none, 1 before, 2 after
    private Integer uHeapSectionId;
    private String lastSafepointReason;
    private boolean generationalZ;

    private static <V> Map<Integer, V> bounded() {
        return new LinkedHashMap<>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, V> e) {
                return size() > 64;
            }
        };
    }

    /** Parses a whole stream (UTF-8 text), then {@link #finish} is still up to the caller. */
    public void feed(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
        String line;
        while ((line = r.readLine()) != null) {
            log.bytes += line.length() + 1;
            feed(line);
        }
        endOfFile();
    }

    /** Ends a file: a Java 8 record cannot continue into the next file. */
    public void endOfFile() {
        flushPending();
        j8HeapSection = 0;
        uHeapSection = 0;
    }

    public void feed(String line) {
        log.lines++;
        if (line.isEmpty()) return;
        char c0 = line.charAt(0);
        if (c0 == '[' && unifiedLine(line)) return;
        if (c0 == 'G' && line.startsWith("GC(")) { // unified logging without decorations
            curTs = null;
            curUp = null;
            unifiedMessage(line);
            return;
        }
        java8Line(line);
    }

    // ---- unified logging ---------------------------------------------------------------

    /** False when the line does not start with unified-logging decorations. */
    private boolean unifiedLine(String line) {
        int i = 0, n = line.length(), decorations = 0;
        Long ts = null;
        Double up = null;
        while (i < n && line.charAt(i) == '[') {
            int j = line.indexOf(']', i);
            if (j < 0) break;
            String d = line.substring(i + 1, j).trim();
            int kind = decoration(d);
            if (kind == 0) break;
            if (kind == 1) ts = cachedDate(d);
            else if (kind == 2) up = parseNum(d.substring(0, d.length() - 1));
            else if (kind == 3) {
                double v = parseNum(d.substring(0, d.length() - 2));
                if (v > 1e11) ts = (long) v;
                else up = v / 1000.0;
            } else if (kind == 4) up = parseNum(d.substring(0, d.length() - 2)) / 1e9;
            decorations++;
            i = j + 1;
        }
        if (decorations == 0) return false;
        unifiedLines++;
        stamp(ts, up);
        if (i < n && line.charAt(i) == ' ') i++;
        unifiedMessage(line.substring(i));
        return true;
    }

    /** 0 not a decoration, 1 date, 2 uptime seconds, 3 milliseconds, 4 nanoseconds, 5 level/tags/pid/tid. */
    private static int decoration(String d) {
        int n = d.length();
        if (n == 0) return 0;
        if (n >= 19 && d.charAt(4) == '-' && d.charAt(10) == 'T') return 1;
        char last = d.charAt(n - 1);
        if (Character.isDigit(d.charAt(0))) {
            if (last == 's') {
                if (d.endsWith("ms") && numeric(d, n - 2)) return 3;
                if (d.endsWith("ns") && numeric(d, n - 2)) return 4;
                if (numeric(d, n - 1)) return 2;
                return 0;
            }
            return numeric(d, n) ? 5 : 0;
        }
        for (int k = 0; k < n; k++) {
            char c = d.charAt(k);
            if (!(c >= 'a' && c <= 'z') && !Character.isDigit(c) && c != ',' && c != '_' && c != ' ') return 0;
        }
        return 5;
    }

    private static boolean numeric(String s, int end) {
        if (end <= 0) return false;
        for (int k = 0; k < end; k++) {
            char c = s.charAt(k);
            if (!Character.isDigit(c) && c != '.' && c != ',') return false;
        }
        return true;
    }

    private String lastDateText;
    private Long lastDateValue;

    /** Bursts of lines share one time stamp; parse it once. */
    private Long cachedDate(String d) {
        if (!d.equals(lastDateText)) {
            lastDateText = d;
            lastDateValue = parseDate(d);
        }
        return lastDateValue;
    }

    private void unifiedMessage(String msg) {
        Integer id = null;
        String rest = msg;
        if (msg.startsWith("GC(")) {
            int close = msg.indexOf(')');
            if (close > 3) {
                try {
                    id = Integer.parseInt(msg.substring(3, close));
                } catch (NumberFormatException e) {
                    id = null;
                }
                rest = msg.substring(Math.min(close + 2, msg.length()));
            }
        }
        if (id == null) {
            unifiedGlobal(msg);
            return;
        }
        String prefix = null;
        if (rest.length() > 3 && rest.charAt(1) == ':' && rest.charAt(2) == ' '
                && (rest.charAt(0) == 'y' || rest.charAt(0) == 'Y' || rest.charAt(0) == 'O')) {
            prefix = rest.substring(0, 1);
            rest = rest.substring(3);
            generationalZ = true;
        }
        if (rest.isEmpty()) return;
        switch (rest.charAt(0)) {
            case 'P' -> {
                if (rest.startsWith("Pause ")) unifiedPause(id, rest, prefix);
                else if (rest.startsWith("PS") || rest.startsWith("Par")) unifiedGen(id, rest);
            }
            case 'C' -> {
                if (rest.startsWith("Concurrent")) unifiedConcurrent(id, rest);
                else if (rest.startsWith("CMS:")) unifiedGen(id, rest);
            }
            case 'G', 'M' -> {
                if (rest.startsWith("Garbage Collection") || rest.startsWith("Major Collection")
                        || rest.startsWith("Minor Collection")) zCollection(id, rest);
                else if (rest.startsWith("Metaspace:")) unifiedMeta(id, rest);
                else if (rest.startsWith("Max Capacity:")) maxCapacity(rest);
            }
            case 'E', 'S', 'O', 'H', 'A' -> {
                if (rest.contains(" regions: ")) regions(id, rest);
                else if (rest.startsWith("Heap before GC")) heapSection(1, id);
                else if (rest.startsWith("Heap after GC")) heapSection(2, id);
                else if (rest.startsWith("Evacuation Failure")) preFlag(id, "evacuation failure");
                else if (rest.startsWith("Allocation Stall")) stall(rest);
            }
            case 'R' -> {
                if (rest.startsWith("Relocation Stall")) stall(rest);
            }
            case 'T' -> {
                if (rest.startsWith("To-space exhausted")) preFlag(id, "to-space exhausted");
                else if (rest.startsWith("Tenured:")) unifiedGen(id, rest);
            }
            case 'D' -> {
                if (rest.startsWith("DefNew:")) unifiedGen(id, rest);
            }
            case ' ' -> heapTrace(id, rest);
            default -> {
                // fall through to the checks below
            }
        }
        if (rest.contains("ailure") && rest.toLowerCase().contains("concurrent mode failure")) {
            log.concurrentModeFailures++;
            recognised++;
            preFlag(id, "concurrent mode failure");
        }
    }

    private void unifiedGlobal(String msg) {
        if (msg.startsWith("Total time for which")) {
            safepointOld(msg);
        } else if (msg.startsWith("Safepoint \"")) {
            Matcher m = SP_NEW.matcher(msg);
            if (m.find()) {
                safepoint(m.group(1), Long.parseLong(m.group(3)) / 1e6, Long.parseLong(m.group(2)) / 1e6);
            }
        } else if (msg.startsWith("Entering safepoint region: ")) {
            lastSafepointReason = msg.substring("Entering safepoint region: ".length()).trim();
        } else if (msg.startsWith("Using ")) {
            String c = msg.substring(6).trim();
            log.collector = switch (c) {
                case "G1" -> "G1";
                case "Concurrent Mark Sweep" -> "CMS";
                case "Parallel" -> "Parallel";
                case "Serial" -> "Serial";
                case "The Z Garbage Collector" -> "ZGC";
                case "Shenandoah" -> "Shenandoah";
                case "Epsilon" -> "Epsilon";
                default -> log.collector;
            };
            recognised++;
        } else if (msg.startsWith("Version: ")) {
            String v = msg.substring(9).trim();
            int sp = v.indexOf(' ');
            log.jvmVersion = sp > 0 ? v.substring(0, sp) : v;
        } else if (msg.startsWith("Heap Region Size") || msg.startsWith("Heap region size")) {
            Matcher m = REGION_SIZE.matcher(msg);
            if (m.find()) log.regionSizeK = kb(m.group(1), m.group(2));
        } else if (msg.startsWith("Heap Max Capacity") || msg.startsWith("Max Capacity")) {
            maxCapacity(msg);
        } else if (msg.startsWith("Minimum heap")) {
            Matcher m = MAX_HEAP_BYTES.matcher(msg);
            if (m.find()) log.heapMaxK = Long.parseLong(m.group(1)) / 1024;
        } else if (msg.startsWith("Allocation Stall") || msg.startsWith("Relocation Stall")) {
            stall(msg);
        } else if (msg.startsWith("Cancelling GC: Allocation Failure")) {
            log.shenandoahCancels++;
        } else if (msg.startsWith("Heap before GC")) {
            heapSection(1, null);
        } else if (msg.startsWith("Heap after GC")) {
            heapSection(2, null);
        } else if (msg.startsWith(" ") || msg.startsWith("\t")) {
            heapTrace(null, msg);
        }
    }

    private void maxCapacity(String msg) {
        Matcher m = MAX_CAPACITY.matcher(msg);
        if (m.find()) log.heapMaxK = kb(m.group(1), m.group(2));
    }

    private void unifiedPause(int id, String rest, String zPrefix) {
        if (!rest.endsWith("ms")) return; // gc,start line: the summary follows
        Matcher m = U_PAUSE.matcher(rest);
        if (!m.matches()) return;
        recognised++;
        GcEvent e = event(GcEvent.PAUSE);
        e.gcId = id;
        e.durationMs = ms(parseNum(m.group(8)));
        if (m.group(1) != null) {
            NameParts np = nameParts(m.group(1));
            String base = np.base;
            String sub = null;
            List<String> causes = new ArrayList<>();
            for (String g : np.groups) {
                if (g.startsWith("Evacuation Failure")) {
                    e.flag("evacuation failure");
                } else if (base.equals("Young") && sub == null && isG1Subtype(g)) {
                    sub = g;
                } else {
                    causes.add(g);
                }
            }
            e.cause = causes.isEmpty() ? null : String.join(" / ", causes);
            if (base.equals("Young")) {
                boolean mixed = "Mixed".equals(sub);
                e.category = mixed ? GcEvent.MIXED : GcEvent.YOUNG;
                e.type = mixed ? "Mixed" : sub == null || sub.equals("Normal") ? "Young" : "Young (" + sub + ")";
            } else if (base.equals("Full")) {
                e.category = GcEvent.FULL;
                e.type = "Full";
            } else if (base.equals("Mixed")) {
                e.category = GcEvent.MIXED;
                e.type = "Mixed";
            } else {
                e.category = GcEvent.PHASE;
                e.type = base;
                if (base.startsWith("Degenerated")) e.flag("degenerated");
            }
            if (zPrefix != null) e.type = (zPrefix.equals("O") ? "Old " : "Young ") + e.type;
        }
        if (m.group(2) != null) {
            e.heapBeforeK = kb(m.group(2), m.group(3));
            e.heapAfterK = kb(m.group(4), m.group(5));
            e.heapTotalK = kb(m.group(6), m.group(7));
        }
        if (e.cause != null && e.cause.contains("Humongous")) e.flag("humongous");
        mergePre(id, e);
        add(e);
        done.put(id, e);
    }

    private static boolean isG1Subtype(String g) {
        return g.equals("Normal") || g.equals("Concurrent Start") || g.equals("Prepare Mixed") || g.equals("Mixed")
                || g.equals("Concurrent End") || g.equals("Initial Mark");
    }

    private void unifiedConcurrent(Integer id, String rest) {
        if (!rest.endsWith("ms")) return;
        Matcher m = U_CONC.matcher(rest);
        if (!m.matches()) return;
        recognised++;
        GcEvent e = event(GcEvent.CONCURRENT);
        e.gcId = id;
        e.category = GcEvent.CONCURRENT;
        e.type = m.group(1);
        e.cause = m.group(2);
        e.durationMs = ms(parseNum(m.group(9)));
        if (m.group(3) != null) {
            e.heapBeforeK = kb(m.group(3), m.group(4));
            e.heapAfterK = kb(m.group(5), m.group(6));
            e.heapTotalK = kb(m.group(7), m.group(8));
        }
        add(e);
    }

    private void zCollection(int id, String rest) {
        Matcher s = Z_START.matcher(rest);
        if (s.matches() && !rest.endsWith("s") && !rest.endsWith("%)")) {
            zStartTs.put(id, new Long[] {curTs});
            zStartUp.put(id, curUp);
            return;
        }
        Matcher m = Z_COLL.matcher(rest);
        if (!m.matches()) return;
        recognised++;
        GcEvent e = event(GcEvent.CONCURRENT);
        e.gcId = id;
        e.category = GcEvent.CONCURRENT;
        e.type = switch (m.group(1)) {
            case "Major" -> "ZGC Major";
            case "Minor" -> "ZGC Minor";
            default -> "ZGC Cycle";
        };
        if (!m.group(1).equals("Garbage")) generationalZ = true;
        e.cause = m.group(2);
        e.heapBeforeK = kb(m.group(3), m.group(4));
        e.heapAfterK = kb(m.group(5), m.group(6));
        e.heapTotalK = log.heapMaxK;
        if (m.group(7) != null) {
            e.durationMs = ms(parseNum(m.group(7)) * 1000);
        } else {
            Long[] st = zStartTs.get(id);
            Double su = zStartUp.get(id);
            if (su != null && curUp != null) e.durationMs = Math.max(0, (curUp - su) * 1000);
            else if (st != null && st[0] != null && curTs != null) e.durationMs = Math.max(0, curTs - st[0]);
        }
        add(e);
    }

    private GcEvent pre(int id) {
        return pre.computeIfAbsent(id, k -> new GcEvent());
    }

    private void preFlag(Integer id, String flag) {
        if (id == null) return;
        GcEvent d = done.get(id);
        if (d != null && d.isPause()) d.flag(flag);
        else pre(id).flag(flag);
        recognised++;
    }

    private void regions(int id, String rest) {
        Matcher m = REGIONS.matcher(rest);
        if (!m.find()) return;
        recognised++;
        GcEvent p = pre(id);
        int b = Integer.parseInt(m.group(2)), a = Integer.parseInt(m.group(3));
        Integer cap = m.group(4) == null ? null : Integer.parseInt(m.group(4));
        switch (m.group(1)) {
            case "Eden" -> { p.edenBefore = b; p.edenAfter = a; p.edenCap = cap; }
            case "Survivor" -> { p.survBefore = b; p.survAfter = a; p.survCap = cap; }
            case "Old" -> { p.oldRegBefore = b; p.oldRegAfter = a; }
            case "Humongous" -> { p.humBefore = b; p.humAfter = a; }
            default -> { /* archive regions: not reported */ }
        }
    }

    private void unifiedGen(int id, String rest) {
        Matcher m = U_GEN.matcher(rest);
        if (!m.find()) return;
        recognised++;
        GcEvent p = pre(id);
        long before = kb(m.group(2), m.group(3)), after = kb(m.group(6), m.group(7)), total = kb(m.group(8), m.group(9));
        switch (m.group(1)) {
            case "PSYoungGen", "ParNew", "DefNew" -> { p.youngBeforeK = before; p.youngAfterK = after; p.youngTotalK = total; }
            default -> { p.oldBeforeK = before; p.oldAfterK = after; p.oldTotalK = total; }
        }
    }

    private void unifiedMeta(int id, String rest) {
        Matcher m = U_META.matcher(rest);
        if (!m.find()) return;
        recognised++;
        GcEvent p = done.containsKey(id) ? done.get(id) : pre(id);
        p.metaBeforeK = kb(m.group(1), m.group(2));
        p.metaAfterK = kb(m.group(5), m.group(6));
        p.metaTotalK = kb(m.group(7), m.group(8));
    }

    private void heapSection(int section, Integer id) {
        uHeapSection = section;
        uHeapSectionId = id;
        j8HeapSection = section;
    }

    /** -Xlog:gc+heap=debug/trace "Heap before/after GC" blocks: metaspace used and the G1 region size. */
    private void heapTrace(Integer id, String rest) {
        if (rest.contains("Metaspace")) {
            Matcher m = TRACE_META.matcher(rest);
            if (!m.find()) return;
            long used = Long.parseLong(m.group(1)), committed = Long.parseLong(m.group(2));
            Integer gid = id != null ? id : uHeapSectionId;
            if (gid == null) return;
            if (uHeapSection == 1) {
                GcEvent p = pre(gid);
                p.metaBeforeK = used;
            } else if (uHeapSection == 2) {
                // Java 17+ prints the block before the summary line, Java 11 after it
                GcEvent d = pre.containsKey(gid) ? pre.get(gid) : done.get(gid);
                if (d != null && d.metaAfterK == null) {
                    d.metaAfterK = used;
                    d.metaTotalK = committed;
                }
            }
        } else if (log.regionSizeK == null && rest.contains("region size")) {
            Matcher m = TRACE_REGION.matcher(rest);
            if (m.find()) log.regionSizeK = Long.parseLong(m.group(1));
        }
    }

    /** Copies what the lines before the summary line said about this collection. */
    private void mergePre(int id, GcEvent e) {
        GcEvent p = pre.remove(id);
        if (p == null) return;
        e.edenBefore = p.edenBefore;
        e.edenAfter = p.edenAfter;
        e.edenCap = p.edenCap;
        e.survBefore = p.survBefore;
        e.survAfter = p.survAfter;
        e.survCap = p.survCap;
        e.oldRegBefore = p.oldRegBefore;
        e.oldRegAfter = p.oldRegAfter;
        e.humBefore = p.humBefore;
        e.humAfter = p.humAfter;
        if (p.youngBeforeK != null) {
            e.youngBeforeK = p.youngBeforeK;
            e.youngAfterK = p.youngAfterK;
            e.youngTotalK = p.youngTotalK;
        }
        if (p.oldBeforeK != null) {
            e.oldBeforeK = p.oldBeforeK;
            e.oldAfterK = p.oldAfterK;
            e.oldTotalK = p.oldTotalK;
        }
        if (p.metaBeforeK != null) e.metaBeforeK = p.metaBeforeK;
        if (p.metaAfterK != null) {
            e.metaAfterK = p.metaAfterK;
            e.metaTotalK = p.metaTotalK;
        }
        if (p.flags != null) p.flags.forEach(e::flag);
    }

    private void stall(String msg) {
        Matcher m = STALL.matcher(msg);
        if (!m.find()) return;
        recognised++;
        double ms = parseNum(m.group(2));
        log.allocationStalls++;
        log.allocationStallTotalMs += ms;
        log.allocationStallMaxMs = Math.max(log.allocationStallMaxMs, ms);
        log.stallPoints.add(curTs, curUp, ms);
    }

    // ---- Java 8 ------------------------------------------------------------------------

    private void java8Line(String line) {
        int p = 0, n = line.length();
        Long ts = null;
        Double up = null;
        if (n > 24 && Character.isDigit(line.charAt(0)) && line.charAt(4) == '-' && line.charAt(10) == 'T') {
            int k = line.indexOf(": ");
            if (k > 0) {
                ts = cachedDate(line.substring(0, k));
                if (ts != null) p = k + 2;
            }
        }
        int k = p;
        while (k < n && (Character.isDigit(line.charAt(k)) || line.charAt(k) == '.' || line.charAt(k) == ',')) k++;
        if (k > p && k + 1 < n && line.charAt(k) == ':' && line.charAt(k + 1) == ' ') {
            up = parseNum(line.substring(p, k));
            p = k + 2;
        }
        boolean stamped = ts != null || up != null;
        String body = line.substring(p);
        if (stamped) {
            java8Lines++;
            stamp(ts, up);
        }
        if (body.startsWith("Total time for which")) {
            safepointOld(body);
            return;
        }
        if (body.startsWith("Application time:")) return;
        if (!stamped && !body.startsWith("[GC") && !body.startsWith("[Full GC")) {
            java8Continuation(line);
            return;
        }
        if (!body.startsWith("[")) {
            if (body.contains("[CMS-concurrent-")) extractConcurrent(body, ts, up);
            return;
        }
        if (!stamped) java8Lines++;
        if (j8HeapSection == 1) j8HeapSection = 0; // the "Heap before GC" block ends at the record
        if (pending != null) {
            if (balanced(pending)) {
                flushPending();
            } else if (balanced(body) && (body.startsWith("[CMS-concurrent-") || body.startsWith("[GC concurrent-"))) {
                // a concurrent phase printed in the middle of a collection record
                java8Record(body, ts, up);
                return;
            } else {
                flushPending();
            }
        }
        if (balanced(body)) {
            java8Record(body, ts, up);
        } else {
            pending = new StringBuilder(body);
            pendTs = ts;
            pendUp = up;
        }
    }

    private void java8Continuation(String line) {
        if (line.startsWith("OpenJDK") || line.startsWith("Java HotSpot")) {
            Matcher m = J8_VERSION.matcher(line);
            if (m.find()) log.jvmVersion = m.group(1);
            return;
        }
        if (line.startsWith("CommandLine flags: ")) {
            log.jvmFlags = line.substring("CommandLine flags: ".length()).trim();
            Matcher m = J8_MAXHEAP.matcher(log.jvmFlags);
            if (m.find()) log.heapMaxK = Long.parseLong(m.group(1)) / 1024;
            if (log.jvmFlags.contains("+UseConcMarkSweepGC")) log.collector = "CMS";
            else if (log.jvmFlags.contains("+UseG1GC")) log.collector = "G1";
            else if (log.jvmFlags.contains("+UseParallelGC") || log.jvmFlags.contains("+UseParallelOldGC")) log.collector = "Parallel";
            else if (log.jvmFlags.contains("+UseSerialGC")) log.collector = "Serial";
            return;
        }
        if (line.startsWith("{Heap before GC")) {
            j8HeapSection = 1;
            return;
        }
        if (line.startsWith("Heap after GC")) {
            j8HeapSection = 2;
            return;
        }
        if (line.startsWith("}")) {
            j8HeapSection = 0;
            return;
        }
        if (j8HeapSection != 0) {
            if (line.contains("Metaspace")) {
                Matcher m = TRACE_META.matcher(line);
                if (m.find()) {
                    long used = Long.parseLong(m.group(1));
                    if (j8HeapSection == 1) {
                        j8MetaBefore = used;
                    } else if (lastJ8 != null && lastJ8.metaAfterK == null) {
                        lastJ8.metaAfterK = used;
                        lastJ8.metaTotalK = Long.parseLong(m.group(2));
                    }
                }
            } else if (log.regionSizeK == null && line.contains("region size")) {
                Matcher m = TRACE_REGION.matcher(line);
                if (m.find()) log.regionSizeK = Long.parseLong(m.group(1));
            }
            return;
        }
        if (line.startsWith("Desired survivor") || line.startsWith("- age")) return;
        if (pending != null) {
            pending.append(line); // concurrent phases inside are taken out when the record is parsed
            if (balanced(pending)) flushPending();
            return;
        }
        if (line.contains("[CMS-concurrent-")) extractConcurrent(line, null, null);
        else if (lastJ8 != null && line.contains("[Eden: ")) g1Detail(line);
    }

    private void flushPending() {
        if (pending == null) return;
        String text = pending.toString();
        pending = null;
        java8Record(text, pendTs, pendUp);
    }

    private static boolean balanced(CharSequence s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') depth--;
        }
        return depth <= 0;
    }

    /** CMS concurrent phases, also when printed inside another record; returns the text without them. */
    private String extractConcurrent(String text, Long ts, Double up) {
        if (!text.contains("[CMS-concurrent-")) return text;
        Matcher m = J8_CONC.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            last = m.end();
            if (m.group(5) == null) continue; // a -start marker
            recognised++;
            Long ets = m.group(1) != null ? parseDate(m.group(1)) : ts;
            Double eup = m.group(2) != null ? parseNum(m.group(2)) : up;
            GcEvent e = new GcEvent();
            e.ts = ets != null ? ets : curTs;
            e.uptime = eup != null ? eup : curUp;
            e.kind = GcEvent.CONCURRENT;
            e.category = GcEvent.CONCURRENT;
            e.type = "Concurrent " + titleCase(m.group(3));
            e.durationMs = ms(parseNum(m.group(5)) * 1000);
            add(e);
        }
        out.append(text.substring(last));
        return out.toString();
    }

    private void java8Record(String raw, Long ts, Double up) {
        String text = extractConcurrent(raw, ts, up);
        if (text.contains("[G1Ergonomics")) text = J8_ERGO.matcher(text).replaceAll("");
        text = J8_TIMES.matcher(text).replaceAll("").trim();
        if (text.isEmpty() || !text.startsWith("[")) return;
        if (text.startsWith("[GC concurrent-")) {
            int end = text.indexOf("-end, ");
            if (end < 0) {
                if (text.contains("concurrent-mark-abort")) log.warnings.add("G1 concurrent mark aborted");
                return;
            }
            Matcher s = J8_SECS.matcher(text);
            if (!s.find()) return;
            recognised++;
            GcEvent e = newJ8(ts, up, GcEvent.CONCURRENT);
            e.category = GcEvent.CONCURRENT;
            e.type = "Concurrent " + titleCase(text.substring("[GC concurrent-".length(), end));
            e.durationMs = ms(parseNum(s.group(1)) * 1000);
            add(e);
            return;
        }
        if (!text.startsWith("[GC") && !text.startsWith("[Full GC")) return;
        Double secs = null;
        Matcher sm = J8_SECS.matcher(text);
        while (sm.find()) secs = parseNum(sm.group(1));
        if (secs == null) return;
        recognised++;
        GcEvent e = newJ8(ts, up, GcEvent.PAUSE);
        e.durationMs = ms(secs * 1000);
        String lower = text.toLowerCase();
        boolean full = text.startsWith("[Full GC");
        String afterHead;
        if (text.startsWith("[GC pause")) {
            afterHead = text.substring("[GC pause".length());
            NameParts np = nameParts("x" + afterHead.substring(0, Math.max(0, afterHead.indexOf(','))));
            String sub = "young";
            List<String> causes = new ArrayList<>();
            for (String g : np.groups) {
                if (g.equals("young") || g.equals("mixed")) sub = g;
                else if (g.startsWith("to-space")) e.flag("to-space exhausted");
                else if (!g.equals("initial-mark")) causes.add(g);
                if (g.equals("initial-mark")) e.flag("initial-mark");
            }
            e.cause = causes.isEmpty() ? null : String.join(" / ", causes);
            e.category = sub.equals("mixed") ? GcEvent.MIXED : GcEvent.YOUNG;
            e.type = sub.equals("mixed") ? "Mixed" : "Young";
            if (log.collector == null) log.collector = "G1";
        } else if (text.startsWith("[GC remark") || text.startsWith("[GC cleanup")) {
            e.category = GcEvent.PHASE;
            e.type = text.startsWith("[GC remark") ? "Remark" : "Cleanup";
        } else {
            afterHead = text.substring(full ? "[Full GC".length() : "[GC".length()).trim();
            String cause = afterHead.startsWith("(") ? firstGroup(afterHead) : null;
            e.cause = cause;
            if ("CMS Initial Mark".equals(cause)) {
                e.category = GcEvent.PHASE;
                e.type = "Initial Mark";
            } else if ("CMS Final Remark".equals(cause)) {
                e.category = GcEvent.PHASE;
                e.type = "Remark";
            } else {
                e.category = full ? GcEvent.FULL : GcEvent.YOUNG;
                e.type = full ? "Full" : "Young";
            }
        }
        if (lower.contains("concurrent mode failure")) {
            e.flag("concurrent mode failure");
            log.concurrentModeFailures++;
        }
        if (lower.contains("promotion failed")) {
            e.flag("promotion failed");
            log.promotionFailures++;
        }
        if (lower.contains("to-space exhausted") || lower.contains("to-space overflow")) e.flag("to-space exhausted");
        if (e.cause != null && e.cause.contains("Humongous")) e.flag("humongous");
        // generations
        StringBuilder rest = new StringBuilder();
        Matcher g = J8_GEN.matcher(text);
        int last = 0;
        boolean cmsOld = false;
        while (g.find()) {
            String name = g.group(1);
            long b = Long.parseLong(g.group(3)), a = Long.parseLong(g.group(5)), t = Long.parseLong(g.group(6));
            switch (name) {
                case "ParNew", "DefNew", "PSYoungGen", "ASParNew" -> { e.youngBeforeK = b; e.youngAfterK = a; e.youngTotalK = t; }
                case "Metaspace", "PSPermGen", "CMS Perm", "Perm" -> { e.metaBeforeK = b; e.metaAfterK = a; e.metaTotalK = t; }
                default -> {
                    e.oldBeforeK = b;
                    e.oldAfterK = a;
                    e.oldTotalK = t;
                    if (name.equals("CMS") || name.equals("ASCMS")) cmsOld = true;
                }
            }
            int close = text.indexOf(']', g.end());
            rest.append(text, last, g.start());
            last = close < 0 ? text.length() : close + 1;
            if (last < g.end()) last = g.end();
        }
        rest.append(text.substring(Math.min(last, text.length())));
        if (cmsOld && GcEvent.YOUNG.equals(e.category)) {
            // ParNew failed and CMS collected the old generation in the foreground: a full collection
            e.category = GcEvent.FULL;
            e.type = "Full";
        }
        Matcher h = HEAP_TRIPLE.matcher(rest);
        if (h.find()) {
            e.heapBeforeK = kb(h.group(1), h.group(2));
            e.heapAfterK = kb(h.group(3), h.group(4));
            e.heapTotalK = kb(h.group(5), h.group(6));
        } else {
            Matcher hp = J8_HEAP_PAIR.matcher(rest);
            if (hp.find()) {
                e.heapBeforeK = e.heapAfterK = Long.parseLong(hp.group(1));
                e.heapTotalK = Long.parseLong(hp.group(2));
            }
        }
        if (j8MetaBefore != null) {
            if (e.metaBeforeK == null) e.metaBeforeK = j8MetaBefore;
            j8MetaBefore = null;
        }
        if (log.collector == null) {
            if (e.youngTotalK != null && text.contains("[ParNew")) log.collector = "CMS";
            else if (text.contains("[PSYoungGen")) log.collector = "Parallel";
            else if (text.contains("[DefNew")) log.collector = "Serial";
        }
        add(e);
        lastJ8 = e;
    }

    /** G1 (Java 8) detail line: [Eden: ... Survivors: ... Heap: ...] and [Metaspace: ...] after a pause. */
    private void g1Detail(String line) {
        Matcher m = J8_G1_DETAIL.matcher(line);
        if (!m.find()) return;
        GcEvent e = lastJ8;
        long edenB = kb(m.group(1), m.group(2)), edenA = kb(m.group(5), m.group(6)), edenCapA = kb(m.group(7), m.group(8));
        long survB = kb(m.group(9), m.group(10)), survA = kb(m.group(11), m.group(12));
        e.youngBeforeK = edenB + survB;
        e.youngAfterK = edenA + survA;
        e.youngTotalK = edenCapA + survA;
        e.heapBeforeK = kb(m.group(13), m.group(14));
        e.heapTotalK = kb(m.group(19), m.group(20));
        e.heapAfterK = kb(m.group(17), m.group(18));
        Matcher mm = J8_G1_META.matcher(line);
        if (mm.find()) {
            e.metaBeforeK = Long.parseLong(mm.group(1));
            e.metaAfterK = Long.parseLong(mm.group(2));
            e.metaTotalK = Long.parseLong(mm.group(3));
        }
    }

    private GcEvent newJ8(Long ts, Double up, String kind) {
        GcEvent e = new GcEvent();
        e.ts = ts;
        e.uptime = up;
        e.kind = kind;
        return e;
    }

    // ---- shared ------------------------------------------------------------------------

    private void safepointOld(String msg) {
        Matcher m = SP_OLD.matcher(msg);
        if (!m.find()) return;
        double ttsp = m.group(2) == null ? 0 : parseNum(m.group(2)) * 1000;
        safepoint(lastSafepointReason, parseNum(m.group(1)) * 1000, ttsp);
        lastSafepointReason = null;
    }

    private void safepoint(String reason, double ms, double ttspMs) {
        recognised++;
        log.safepoints++;
        log.safepointTotalMs += ms;
        log.safepointMaxMs = Math.max(log.safepointMaxMs, ms);
        log.ttspTotalMs += ttspMs;
        log.ttspMaxMs = Math.max(log.ttspMaxMs, ttspMs);
        log.safepointPoints.add(curTs, curUp, ms);
        if (reason != null) {
            double[] r = log.safepointReasons.get(reason);
            if (r == null) {
                if (log.safepointReasons.size() >= 200) return;
                log.safepointReasons.put(reason, r = new double[3]);
            }
            r[0]++;
            r[1] += ms;
            r[2] = Math.max(r[2], ms);
        }
    }

    private GcEvent event(String kind) {
        GcEvent e = new GcEvent();
        e.kind = kind;
        e.ts = curTs;
        e.uptime = curUp;
        return e;
    }

    private void add(GcEvent e) {
        log.events.add(e);
    }

    private void stamp(Long ts, Double up) {
        curTs = ts;
        curUp = up;
        if (ts == null) everyLineDated = false;
        if (ts != null && firstTs == null) firstTs = ts;
        if (up != null && firstUp == null) firstUp = up;
        if (ts != null) lastTs = ts;
        if (up != null) lastUp = up;
    }

    /** Ends parsing: G1 regions to KiB, generation sizes, the time axis; returns the log. */
    public GcLog finish() {
        flushPending();
        List<GcEvent> ev = log.events;
        log.format = java8Lines > 0 && unifiedLines > 0 ? "mixed" : java8Lines > 0 ? "java8" : unifiedLines > 0 ? "unified" : "unknown";
        if (log.collector == null) log.collector = inferCollector(ev);
        if ("ZGC".equals(log.collector) && generationalZ) log.collector = "ZGC (generational)";
        Long rs = log.regionSizeK;
        if (rs == null && ev.stream().anyMatch(e -> e.edenBefore != null)) {
            long heap = log.heapMaxK != null ? log.heapMaxK
                    : ev.stream().filter(e -> e.heapTotalK != null).mapToLong(e -> e.heapTotalK).max().orElse(0);
            if (heap > 0) {
                // G1's default: about 2048 regions, a power of two between 1 and 32 MB
                long mb = Math.max(1, Math.min(32, Long.highestOneBit(Math.max(1, heap / 1024 / 2048))));
                rs = mb * 1024;
                log.regionSizeK = rs;
                log.warnings.add("G1 region size not in the log; assumed " + mb + " MB");
            }
        }
        for (GcEvent e : ev) {
            if (rs != null && e.edenBefore != null) {
                int sb = e.survBefore == null ? 0 : e.survBefore, sa = e.survAfter == null ? 0 : e.survAfter;
                e.youngBeforeK = (e.edenBefore + sb) * rs;
                e.youngAfterK = (e.edenAfter + sa) * rs;
                if (e.edenCap != null && e.survCap != null) e.youngTotalK = (long) (e.edenCap + e.survCap) * rs;
            }
            if (rs != null && e.oldRegBefore != null) {
                e.oldBeforeK = (long) e.oldRegBefore * rs;
                e.oldAfterK = (long) e.oldRegAfter * rs;
            }
            if (rs != null && e.humBefore != null) {
                e.humongousBeforeK = (long) e.humBefore * rs;
                e.humongousAfterK = (long) e.humAfter * rs;
            }
            if (e.oldBeforeK == null && e.youngBeforeK != null && e.heapBeforeK != null && e.heapAfterK != null) {
                e.oldBeforeK = Math.max(0, e.heapBeforeK - e.youngBeforeK);
                e.oldAfterK = Math.max(0, e.heapAfterK - e.youngAfterK);
            }
            if (e.oldTotalK == null && e.youngTotalK != null && e.heapTotalK != null && e.edenBefore == null) {
                e.oldTotalK = Math.max(0, e.heapTotalK - e.youngTotalK);
            }
        }
        boolean wall = firstTs != null && everyEventDated(ev);
        log.timeAxis = wall ? "wall" : "uptime";
        if (wall) {
            long start = firstTs;
            for (GcEvent e : ev) start = Math.min(start, e.ts);
            log.startTs = start;
            for (GcEvent e : ev) e.x = (e.ts - start) / 1000.0;
            ev.sort(Comparator.comparingDouble(e -> e.x));
            setPointX(log.safepointPoints, start, true);
            setPointX(log.stallPoints, start, true);
            log.endX = lastTs == null ? 0 : (lastTs - start) / 1000.0;
            log.startX = (firstTs - start) / 1000.0;
        } else {
            double prev = 0;
            for (GcEvent e : ev) {
                e.x = e.uptime != null ? e.uptime : prev;
                prev = e.x;
            }
            setPointX(log.safepointPoints, 0, false);
            setPointX(log.stallPoints, 0, false);
            log.endX = lastUp == null ? prev : lastUp;
            log.startX = firstUp != null ? Math.min(firstUp, ev.isEmpty() ? firstUp : ev.get(0).x) : ev.isEmpty() ? 0 : ev.get(0).x;
            if (!everyLineDated && firstTs != null) log.warnings.add("Some lines have no date stamp; the timeline uses JVM uptime");
            for (int i = 1; i < ev.size(); i++) {
                if (ev.get(i).x + 10 < ev.get(i - 1).x) {
                    log.warnings.add("The JVM restarted within these files and the log has no date stamps; restarts overlap on the timeline");
                    break;
                }
            }
        }
        for (GcEvent e : ev) log.endX = Math.max(log.endX, e.x + e.durationMs / 1000.0);
        for (GcEvent e : ev) {
            if (e.hasFlag("to-space exhausted")) log.toSpaceExhausted++;
            if (e.hasFlag("evacuation failure")) log.evacuationFailures++;
        }
        if (recognised == 0 && log.lines > 0) log.warnings.add("No GC events were recognised: is this a GC log?");
        return log;
    }

    private static boolean everyEventDated(List<GcEvent> ev) {
        for (GcEvent e : ev) if (e.ts == null) return false;
        return true;
    }

    private static void setPointX(GcLog.Points p, long start, boolean wall) {
        double prev = 0;
        for (int i = 0; i < p.size; i++) {
            double v = wall ? (p.ts[i] - start) / 1000.0 : p.up[i];
            if (Double.isNaN(v)) v = prev;
            p.xs[i] = v;
            prev = v;
        }
    }

    private static String inferCollector(List<GcEvent> ev) {
        for (GcEvent e : ev) {
            String t = e.type == null ? "" : e.type;
            if (t.startsWith("ZGC")) return "ZGC";
            if (t.contains("Init Mark") || t.contains("Final Mark") || t.contains("Update Refs") || t.startsWith("Degenerated")) {
                return "Shenandoah";
            }
            if (e.edenBefore != null || "Mixed".equals(t)) return "G1";
            if (t.equals("Concurrent Sweep") || t.equals("Concurrent Abortable Preclean")) return "CMS";
        }
        return null;
    }

    // ---- helpers -----------------------------------------------------------------------

    record NameParts(String base, List<String> groups) {}

    /** "Young (Normal) (G1 Evacuation Pause)" to base "Young" and groups; parentheses may nest. */
    static NameParts nameParts(String s) {
        int i = s.indexOf(" (");
        String base = (i < 0 ? s : s.substring(0, i)).trim();
        List<String> groups = new ArrayList<>();
        if (i >= 0) {
            int depth = 0, start = -1;
            for (int k = i; k < s.length(); k++) {
                char c = s.charAt(k);
                if (c == '(') {
                    if (depth++ == 0) start = k + 1;
                } else if (c == ')' && depth > 0) {
                    if (--depth == 0) groups.add(s.substring(start, k).trim());
                }
            }
        }
        return new NameParts(base, groups);
    }

    /** "(System.gc()) [PSYoungGen..." to "System.gc()". */
    private static String firstGroup(String s) {
        int depth = 0;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return s.substring(1, k).trim();
        }
        return null;
    }

    private static String titleCase(String dashed) {
        StringBuilder b = new StringBuilder();
        for (String w : dashed.split("-")) {
            if (w.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    /** Milliseconds rounded to microseconds (seconds * 1000 is not exact in binary). */
    private static double ms(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    static double parseNum(String s) {
        return Double.parseDouble(s.indexOf(',') >= 0 ? s.replace(',', '.') : s);
    }

    /** A size with a unit (B, K, M, G, T) in KiB. */
    static long kb(String num, String unit) {
        double v = parseNum(num);
        double k = switch (unit) {
            case "B" -> v / 1024;
            case "K" -> v;
            case "M" -> v * 1024;
            case "G" -> v * 1024 * 1024;
            case "T" -> v * 1024 * 1024 * 1024;
            default -> v;
        };
        return Math.round(k);
    }

    /** yyyy-MM-ddTHH:mm:ss.SSS followed by +HHMM, +HH:MM or Z; null when it is not one. */
    static Long parseDate(String s) {
        try {
            if (s.length() < 19) return null;
            int y = Integer.parseInt(s, 0, 4, 10), mo = Integer.parseInt(s, 5, 7, 10), d = Integer.parseInt(s, 8, 10, 10);
            int h = Integer.parseInt(s, 11, 13, 10), mi = Integer.parseInt(s, 14, 16, 10), se = Integer.parseInt(s, 17, 19, 10);
            int p = 19, ms = 0;
            if (p < s.length() && s.charAt(p) == '.') {
                int q = p + 1;
                while (q < s.length() && Character.isDigit(s.charAt(q))) q++;
                String frac = (s.substring(p + 1, q) + "000").substring(0, 3);
                ms = Integer.parseInt(frac);
                p = q;
            }
            int offsetMin = 0;
            if (p < s.length()) {
                char sign = s.charAt(p);
                if (sign == '+' || sign == '-') {
                    String z = s.substring(p + 1).replace(":", "");
                    if (z.length() >= 4) {
                        offsetMin = Integer.parseInt(z, 0, 2, 10) * 60 + Integer.parseInt(z, 2, 4, 10);
                        if (sign == '-') offsetMin = -offsetMin;
                    }
                }
            }
            long days = LocalDate.of(y, mo, d).toEpochDay();
            long sec = days * 86400 + h * 3600L + mi * 60L + se - offsetMin * 60L;
            return sec * 1000 + ms;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
