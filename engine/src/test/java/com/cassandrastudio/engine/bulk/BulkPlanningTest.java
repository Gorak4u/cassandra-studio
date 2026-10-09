package com.cassandrastudio.engine.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cassandrastudio.engine.util.ApiException;
import com.cassandrastudio.engine.util.Json;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.metadata.token.Token;
import com.datastax.oss.driver.api.core.metadata.token.TokenRange;
import com.datastax.oss.driver.internal.core.metadata.token.Murmur3Token;
import com.datastax.oss.driver.internal.core.metadata.token.Murmur3TokenFactory;
import com.datastax.oss.driver.internal.core.metadata.token.TokenFactory;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BulkPlanningTest {
    @TempDir
    Path dir;

    /** The planned ranges cover the ring exactly once: every token falls into one range. */
    @Test
    void tokenRangesCoverTheRingOnce() {
        TokenFactory f = new Murmur3TokenFactory();
        Token a = new Murmur3Token(-5_000_000_000_000_000_000L);
        Token b = new Murmur3Token(100L);
        Token c = new Murmur3Token(6_000_000_000_000_000_000L);
        Set<TokenRange> ring = new HashSet<>(List.of(f.range(a, b), f.range(b, c), f.range(c, a)));
        List<TokenRange> plan = Unloader.plan(ring, f, 4);
        assertThat(plan.size()).isGreaterThanOrEqualTo(32);
        assertThat(plan).noneMatch(TokenRange::isWrappedAround);
        long[] probes = {Long.MIN_VALUE + 1, -5_000_000_000_000_000_000L, -1, 0, 100, 101, 6_000_000_000_000_000_000L, Long.MAX_VALUE};
        for (long p : probes) {
            Token t = new Murmur3Token(p);
            long hits = plan.stream().filter(r -> contains(r, t, f.minToken())).count();
            assertThat(hits).as("token %d", p).isEqualTo(1);
        }
        // A single-token ring is the whole ring.
        List<TokenRange> single = Unloader.plan(Set.of(f.range(b, b)), f, 1);
        assertThat(single.stream().filter(r -> contains(r, new Murmur3Token(Long.MAX_VALUE), f.minToken())).count()).isEqualTo(1);
        assertThat(single.stream().filter(r -> contains(r, new Murmur3Token(-7), f.minToken())).count()).isEqualTo(1);
    }

    /** What the query does: token > start AND (end is min ? true : token <= end). */
    private static boolean contains(TokenRange r, Token t, Token min) {
        boolean above = r.getStart().equals(min) || t.compareTo(r.getStart()) > 0;
        boolean below = r.getEnd().equals(min) || t.compareTo(r.getEnd()) <= 0;
        return above && below;
    }

    @Test
    void factoryByPartitioner() {
        assertThat(Unloader.factory("org.apache.cassandra.dht.Murmur3Partitioner")).isInstanceOf(Murmur3TokenFactory.class);
        assertThat(Unloader.factory("org.apache.cassandra.dht.RandomPartitioner")).isNotNull();
        assertThat(Unloader.factory("com.example.Custom")).isNull();
    }

    @Test
    void parsesAndValidatesOptions() throws Exception {
        var u = BulkOptions.unload(Json.MAPPER.readTree("{\"keyspace\":\"ks\",\"table\":\"t\",\"path\":\"" + dir.resolve("x.csv")
                + "\",\"delimiter\":\"\\\\t\",\"concurrency\":4,\"consistency\":\"local_quorum\",\"compression\":\"gzip\"}"));
        assertThat(u.text().delimiter()).isEqualTo('\t');
        assertThat(u.concurrency()).isEqualTo(4);
        assertThat(u.gzip()).isTrue();
        assertThat(u.consistency()).isEqualTo(DefaultConsistencyLevel.LOCAL_QUORUM);
        var dflt = BulkOptions.unload(Json.MAPPER.readTree("{\"keyspace\":\"ks\",\"table\":\"t\",\"format\":\"json\"}"));
        assertThat(dflt.path().getFileName().toString()).isEqualTo("ks.t.jsonl");
        assertThat(dflt.path().isAbsolute()).isTrue();

        assertThatThrownBy(() -> BulkOptions.unload(Json.MAPPER.readTree("{\"mode\":\"query\"}"))).hasMessageContaining("query is required");
        assertThatThrownBy(() -> BulkOptions.unload(Json.MAPPER.readTree("{\"keyspace\":\"k\",\"table\":\"t\",\"concurrency\":0}")))
                .hasMessageContaining("concurrency must be between");
        assertThatThrownBy(() -> BulkOptions.unload(Json.MAPPER.readTree("{\"keyspace\":\"k\",\"table\":\"t\",\"consistency\":\"MOST\"}")))
                .hasMessageContaining("not a consistency level");
        assertThatThrownBy(() -> BulkOptions.load(Json.MAPPER.readTree("{\"keyspace\":\"k\",\"table\":\"t\",\"path\":\"/x.csv\",\"ttlSeconds\":5,\"ttlField\":\"a\"}")))
                .hasMessageContaining("either a fixed TTL");
        assertThatThrownBy(() -> BulkOptions.load(Json.MAPPER.readTree("{\"keyspace\":\"k\",\"table\":\"t\",\"path\":\"/x.csv\",\"timeZone\":\"Mars/Base\"}")))
                .hasMessageContaining("not a known zone");
        var l = BulkOptions.load(Json.MAPPER.readTree("{\"keyspace\":\"k\",\"table\":\"t\",\"path\":\"/d/x.jsonl.gz\",\"mapping\":[{\"column\":\"a\",\"source\":\"A\"},{\"column\":\"b\",\"source\":\"\"}]}"));
        assertThat(l.format()).isEqualTo(BulkOptions.Format.JSON);
        assertThat(l.mapping()).containsExactly(new BulkOptions.Mapping("a", "A"));
        assertThat(l.consistency()).isEqualTo(DefaultConsistencyLevel.LOCAL_QUORUM);
    }

    @Test
    void fileRules() throws Exception {
        Path existing = Files.writeString(dir.resolve("a.csv"), "x\n");
        assertThatThrownBy(() -> BulkFiles.checkTarget(existing, false)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(409));
        BulkFiles.checkTarget(existing, true);
        assertThatThrownBy(() -> BulkFiles.checkTarget(dir.resolve("nope/a.csv"), false)).hasMessageContaining("does not exist");
        assertThatThrownBy(() -> BulkFiles.checkSource(dir.resolve("missing.csv"))).hasMessageContaining("does not exist");
        assertThat(BulkFiles.sibling(dir.resolve("data.csv.gz"), ".rejected.csv")).isEqualTo(dir.resolve("data.rejected.csv"));
        assertThat(BulkFiles.resolve("rel/x.csv")).isEqualTo(BulkFiles.downloadsDir().resolve("rel/x.csv"));
    }

    @Test
    void inspectsFiles() throws Exception {
        Path csv = dir.resolve("p.csv");
        StringBuilder sb = new StringBuilder("id,name\n");
        for (int i = 0; i < 50; i++) sb.append(i).append(",n").append(i).append('\n');
        Files.writeString(csv, sb);
        var text = BulkOptions.textOptions(Json.MAPPER.readTree("{}"));
        assertThat(Loader.fileColumns(csv, BulkOptions.Format.CSV, false, text)).containsExactly("id", "name");
        assertThat(Loader.estimateRows(csv, false, true)).isEqualTo(50);
        var noHeader = BulkOptions.textOptions(Json.MAPPER.readTree("{\"header\":false}"));
        assertThat(Loader.fileColumns(csv, BulkOptions.Format.CSV, false, noHeader)).containsExactly("c1", "c2");

        Path gz = dir.resolve("big.csv.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write("id,name\n".getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < 300_000; i++) out.write((i + ",some name " + (i * 7919 % 100_003) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        assertThat(BulkFiles.looksGzip(gz)).isTrue();
        long est = Loader.estimateRows(gz, true, true);
        assertThat(est).isBetween(150_000L, 600_000L);

        Path json = dir.resolve("j.jsonl");
        Files.writeString(json, "{\"a\":1}\n\n{\"b\":[1],\"a\":2}\n");
        assertThat(Loader.fileColumns(json, BulkOptions.Format.JSON, false, text)).containsExactly("a", "b");
        AtomicLong counted = new AtomicLong();
        try (var in = BulkFiles.openInput(json, false, counted)) {
            in.readAllBytes();
        }
        assertThat(counted.get()).isEqualTo(Files.size(json));
    }

    @Test
    void rateLimiterSpacesPermits() {
        long[] now = {0};
        RateLimiter r = new RateLimiter(1000, () -> now[0]);
        assertThat(r.reserve(500)).isZero();
        assertThat(r.reserve(500)).isEqualTo(500_000_000L);
        now[0] = 5_000_000_000L;
        // idle credit is capped at one second
        assertThat(r.reserve(1000)).isZero();
        assertThat(r.reserve(1000)).isZero();
        assertThat(r.reserve(1)).isEqualTo(1_000_000_000L);
    }
}
