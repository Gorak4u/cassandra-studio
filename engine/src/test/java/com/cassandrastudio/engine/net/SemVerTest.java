package com.cassandrastudio.engine.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class SemVerTest {
    private static SemVer v(String s) {
        return SemVer.parse(s);
    }

    @Test
    void ordersBySpecIncludingPreReleases() {
        // semver.org §11 example, in ascending order
        List<String> ordered = List.of("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
                "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0");
        List<SemVer> shuffled = new ArrayList<>(ordered.stream().map(SemVerTest::v).toList());
        Collections.shuffle(shuffled, new java.util.Random(42));
        Collections.sort(shuffled);
        assertThat(shuffled.stream().map(SemVer::toString).toList()).isEqualTo(ordered);
    }

    @Test
    void parsesTagsAndBuildVersions() {
        assertThat(v("v1.2.3")).isEqualTo(v("1.2.3"));
        assertThat(v("1.2")).isEqualTo(v("1.2.0"));
        assertThat(v("1.0.0+build.5").compareTo(v("1.0.0"))).isZero();
        assertThat(v("0.1.0-SNAPSHOT").compareTo(v("0.1.0"))).isNegative();
        assertThat(v("0.1.0-SNAPSHOT").isPreRelease()).isTrue();
        assertThat(v("1.0.0-rc.1").compareTo(v("0.9.9"))).isPositive();
        assertThat(v("1.10.0").compareTo(v("1.9.0"))).isPositive();
        assertThat(v("dev")).isNull();
        assertThat(v("")).isNull();
        assertThat(v(null)).isNull();
        assertThat(v("1.2.3.4")).isNull();
        assertThat(v("99999999999.0.0")).isNull();
    }
}
