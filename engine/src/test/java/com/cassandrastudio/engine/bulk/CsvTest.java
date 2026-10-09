package com.cassandrastudio.engine.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvTest {
    private static List<Csv.Record> read(String text, char d) throws IOException {
        List<Csv.Record> out = new ArrayList<>();
        try (Csv.Reader r = new Csv.Reader(new StringReader(text), d)) {
            Csv.Record rec;
            while ((rec = r.next()) != null) out.add(rec);
        }
        return out;
    }

    @Test
    void readsQuotedFieldsLineBreaksAndBom() throws IOException {
        List<Csv.Record> r = read("﻿id,name\r\n1,\"a, \"\"b\"\"\nc\"\r\n\n2,,\"\"\n3,x", ',');
        assertThat(r).hasSize(4);
        assertThat(r.get(0).fields()).containsExactly("id", "name");
        assertThat(r.get(1).fields()).containsExactly("1", "a, \"b\"\nc");
        assertThat(r.get(1).wasQuoted(1)).isTrue();
        assertThat(r.get(2).fields()).containsExactly("2", "", "");
        assertThat(r.get(2).wasQuoted(1)).isFalse();
        assertThat(r.get(2).wasQuoted(2)).isTrue();
        assertThat(r.get(2).line()).isEqualTo(5);
        assertThat(r.get(3).fields()).containsExactly("3", "x");
    }

    @Test
    void writesWhatItReads() throws IOException {
        List<String> values = List.of("plain", "", "with,comma", "quote\"d", "multi\nline", "tab\there");
        for (char d : new char[] {',', '\t', ';'}) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) sb.append(d);
                Csv.appendField(sb, values.get(i), false, d, "");
            }
            sb.append(d);
            Csv.appendField(sb, null, true, d, "");
            Csv.Record back = read(sb.toString(), d).get(0);
            assertThat(back.fields().subList(0, values.size())).containsExactlyElementsOf(values);
            assertThat(back.fields().get(values.size())).isEmpty();
            assertThat(back.wasQuoted(values.size())).isFalse();
            assertThat(back.wasQuoted(1)).isTrue();
        }
    }

    @Test
    void nullStringIsQuotedWhenItIsAValue() {
        StringBuilder sb = new StringBuilder();
        Csv.appendField(sb, "NULL", false, ',', "NULL");
        sb.append(',');
        Csv.appendField(sb, null, true, ',', "NULL");
        assertThat(sb.toString()).isEqualTo("\"NULL\",NULL");
    }

    @Test
    void reportsMalformedRecordsAndGoesOn() throws IOException {
        try (Csv.Reader r = new Csv.Reader(new StringReader("1,\"ab\"cd,3\n2,ok\n"), ',')) {
            assertThatThrownBy(r::next).isInstanceOf(Csv.FormatException.class).hasMessageContaining("line 1");
            assertThat(r.next().fields()).containsExactly("2", "ok");
            assertThat(r.next()).isNull();
        }
        try (Csv.Reader r = new Csv.Reader(new StringReader("1,\"open\n"), ',')) {
            assertThatThrownBy(r::next).hasMessageContaining("not closed");
        }
    }
}
