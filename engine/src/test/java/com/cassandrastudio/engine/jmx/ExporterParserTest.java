package com.cassandrastudio.engine.jmx;

import static org.assertj.core.api.Assertions.assertThat;

import com.cassandrastudio.engine.jmx.JmxAccess.ExporterSample;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExporterParserTest {

    @Test
    void parsesSamplesLabelsAndSkipsComments() {
        List<ExporterSample> s = ExporterParser.parse("""
                # HELP cassandra_table_live_ss_table_count Live SSTables
                # TYPE cassandra_table_live_ss_table_count gauge
                cassandra_table_live_ss_table_count{keyspace="ks",table="t1",} 3.0
                jvm_memory_bytes_used{area="heap"} 1.2345E8 1712345678000

                up 1
                """);
        assertThat(s).containsExactly(
                new ExporterSample("cassandra_table_live_ss_table_count", Map.of("keyspace", "ks", "table", "t1"), 3.0),
                new ExporterSample("jvm_memory_bytes_used", Map.of("area", "heap"), 1.2345E8),
                new ExporterSample("up", Map.of(), 1.0));
    }

    @Test
    void unescapesLabelValuesAndKeepsTheirOrder() {
        ExporterSample s = ExporterParser.parseLine("m{path=\"C:\\\\data\",msg=\"say \\\"hi\\\"\\nbye\",empty=\"\"} 7");
        assertThat(s.labels()).containsExactly(
                Map.entry("path", "C:\\data"), Map.entry("msg", "say \"hi\"\nbye"), Map.entry("empty", ""));
        assertThat(ExporterParser.parseLine("m{a=\"x,}y\"} 1").labels()).containsEntry("a", "x,}y");
    }

    @Test
    void specialValues() {
        assertThat(ExporterParser.parseLine("a NaN").value()).isNaN();
        assertThat(ExporterParser.parseLine("a +Inf").value()).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(ExporterParser.parseLine("a -Inf").value()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(ExporterParser.parseLine("a -0.5e-3").value()).isEqualTo(-0.0005);
        assertThat(ExporterParser.parseLine("a_bucket{le=\"+Inf\"} 42 # {trace_id=\"x\"} 1.0").value()).isEqualTo(42.0);
    }

    @Test
    void malformedLinesAreSkipped() {
        assertThat(ExporterParser.parse("""
                good 1
                bad{a="x" 2
                novalue
                9starts_with_digit 1
                bad2{a=x} 3
                text abc
                # EOF
                """)).extracting(ExporterSample::name).containsExactly("good");
    }
}
