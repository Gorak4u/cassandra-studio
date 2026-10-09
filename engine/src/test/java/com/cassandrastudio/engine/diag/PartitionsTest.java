package com.cassandrastudio.engine.diag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.diag.HotPartitions.KeyCount;
import com.cassandrastudio.engine.diag.HotPartitions.NodeResult;
import com.cassandrastudio.engine.diag.HotPartitions.SamplerResult;
import com.cassandrastudio.engine.diag.HotPartitions.TableResult;
import com.cassandrastudio.engine.diag.TableHistograms.Pcts;
import com.cassandrastudio.engine.diag.TableHistograms.TableHist;
import com.cassandrastudio.engine.util.ApiException;
import java.util.List;
import java.util.Map;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;
import org.junit.jupiter.api.Test;

/** Hot-partition result parsing and merging (PRF-1), histogram maths and flags (PRF-2), settings. */
class PartitionsTest {
    private static final String[] ROW = {"value", "count", "error", "string"};
    private static final CompositeType ROW_TYPE;

    static {
        try {
            ROW_TYPE = new CompositeType("SAMPLING_RESULT", "row", ROW, ROW,
                    new OpenType<?>[] {SimpleType.STRING, SimpleType.LONG, SimpleType.LONG, SimpleType.STRING});
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static CompositeData row(String key, long count, long error) throws Exception {
        return new CompositeDataSupport(ROW_TYPE, ROW, new Object[] {"0x" + key, count, error, key});
    }

    @Test
    void parses311CompositeWithTabularPartitions() throws Exception {
        TabularType tt = new TabularType("PARTITIONS", "p", ROW_TYPE, new String[] {"string"});
        TabularDataSupport td = new TabularDataSupport(tt);
        td.put(row("42", 811, 0));
        td.put(row("7", 12, 1));
        CompositeType ct = new CompositeType("SAMPLING_RESULTS", "r", new String[] {"cardinality", "partitions"},
                new String[] {"c", "p"}, new OpenType<?>[] {SimpleType.LONG, tt});
        CompositeData cd = new CompositeDataSupport(ct, new String[] {"cardinality", "partitions"}, new Object[] {2L, td});
        SamplerResult r = HotPartitions.parse("READS", cd, "n1");
        assertThat(r.cardinality()).isEqualTo(2);
        assertThat(r.top()).extracting(KeyCount::key).containsExactly("42", "7");
        assertThat(r.top().get(1).error()).isEqualTo(1);
    }

    @Test
    void parses4xListAndKeepsTheLargestWriteSizePerKey() throws Exception {
        SamplerResult r = HotPartitions.parse("WRITE_SIZE", List.of(row("42", 71, 0), row("42", 75, 0), row("9", 30, 0)), "n1");
        assertThat(r.top()).extracting(KeyCount::key, KeyCount::count).containsExactly(
                org.assertj.core.groups.Tuple.tuple("42", 75L), org.assertj.core.groups.Tuple.tuple("9", 30L));
        SamplerResult reads = HotPartitions.parse("READS", List.of(row("1", 5, 0), row("2", 9, 0)), "n1");
        assertThat(reads.top().get(0).key()).isEqualTo("2");
        assertThat(reads.cardinality()).isNull();
    }

    @Test
    void mergesNodes() {
        NodeResult n1 = new NodeResult("n1", "4.0+", null, List.of(new TableResult("ks", "t", List.of(
                new SamplerResult("READS", null, List.of(new KeyCount("a", 10, 0, List.of("n1")), new KeyCount("b", 3, 0, List.of("n1"))), null),
                new SamplerResult("WRITE_SIZE", null, List.of(new KeyCount("a", 100, 0, List.of("n1"))), null)))));
        NodeResult n2 = new NodeResult("n2", "4.0+", null, List.of(new TableResult("ks", "t", List.of(
                new SamplerResult("READS", null, List.of(new KeyCount("b", 9, 1, List.of("n2"))), null),
                new SamplerResult("WRITE_SIZE", null, List.of(new KeyCount("a", 300, 0, List.of("n2"))), null)))));
        NodeResult down = new NodeResult("n3", null, "JMX unreachable", null);
        List<TableResult> m = HotPartitions.merge(List.of(n1, n2, down), 1);
        assertThat(m).hasSize(1);
        SamplerResult reads = m.get(0).samplers().get(0);
        assertThat(reads.top()).hasSize(1); // top 1
        assertThat(reads.top().get(0)).isEqualTo(new KeyCount("b", 12, 1, List.of("n1", "n2")));
        SamplerResult size = m.get(0).samplers().get(1);
        assertThat(size.top().get(0).count()).isEqualTo(300); // max, not sum
    }

    @Test
    void estimatedHistogramMatchesCassandra() {
        long[] off = TableHistograms.offsets(10);
        assertThat(off).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 10, 12);
        assertThat(TableHistograms.offsets(150)[149]).isGreaterThan(1L << 40);

        long[] buckets = new long[11]; // 10 offsets + overflow
        buckets[2] = 50; // value 3
        buckets[8] = 49; // value 10
        buckets[9] = 1; // value 12
        Pcts p = TableHistograms.estimated(buckets);
        assertThat(p.count()).isEqualTo(100);
        assertThat(p.p50()).isEqualTo(3.0);
        assertThat(p.p75()).isEqualTo(10.0);
        assertThat(p.p99()).isEqualTo(10.0);
        assertThat(p.min()).isEqualTo(3.0);
        assertThat(p.max()).isEqualTo(12.0);

        buckets[10] = 1; // overflow: max unknown
        assertThat(TableHistograms.estimated(buckets).max()).isNull();
        assertThat(TableHistograms.estimated(new long[0])).isEqualTo(Pcts.EMPTY);
    }

    private static Pcts size(double max) {
        return new Pcts(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, max, 1L);
    }

    private static Pcts tomb(double p99) {
        return new Pcts(0.0, 0.0, 0.0, 0.0, p99, null, p99, 10L);
    }

    @Test
    void flagsAndMergeTakeTheWorstNode() {
        TableHistograms.Thresholds th = new DiagSettings(null, 100L, 1000.0, null).thresholds();
        TableHist a = new TableHist("ks", "t", "n1", size(10), Pcts.EMPTY, tomb(5), Pcts.EMPTY, Pcts.EMPTY, List.of());
        TableHist b = new TableHist("ks", "t", "n2", size(200.0 * 1024 * 1024), Pcts.EMPTY, tomb(1500), Pcts.EMPTY,
                Pcts.EMPTY, List.of());
        TableHist ok = new TableHist("ks", "a", "n1", size(10), Pcts.EMPTY, tomb(1), Pcts.EMPTY, Pcts.EMPTY, List.of());
        List<TableHist> m = TableHistograms.merge(List.of(ok, a, b), th);
        assertThat(m.get(0).table()).isEqualTo("t"); // flagged first
        assertThat(m.get(0).flags()).containsExactly("LARGE_PARTITION", "TOMBSTONES");
        assertThat(m.get(0).node()).isNull();
        assertThat(m.get(0).tombstonesPerRead().count()).isEqualTo(20);
        assertThat(m.get(1).flags()).isEmpty();
        assertThat(m.get(1).node()).isNull();
        assertThat(TableHistograms.systemKeyspace("system_schema")).isTrue();
        assertThat(TableHistograms.systemKeyspace("systems")).isFalse();
    }

    @Test
    void settingsDefaultsAndValidation() {
        DiagSettings e = new DiagSettings(null, null, null, " ").effective();
        assertThat(e).isEqualTo(DiagSettings.DEFAULTS);
        assertThat(e.thresholds().largePartitionBytes()).isEqualTo(100L * 1024 * 1024);
        assertThatThrownBy(() -> new DiagSettings("relative.log", null, null, null).validated())
                .isInstanceOf(ApiException.class).hasMessageContaining("absolute");
        assertThatThrownBy(() -> new DiagSettings(null, 0L, null, null).validated()).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new DiagSettings(null, null, 0.5, null).validated()).isInstanceOf(ApiException.class);
        assertThat(Map.of("x", new DiagSettings("/opt/cassandra/logs/system.log", 50L, 500.0, null).validated()))
                .isNotEmpty();
    }
}
