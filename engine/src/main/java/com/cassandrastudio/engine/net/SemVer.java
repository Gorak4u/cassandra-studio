package com.cassandrastudio.engine.net;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A semantic version (semver.org 2.0): {@code MAJOR.MINOR.PATCH[-pre.release][+build]}, with an optional
 * leading "v" as in release tags. Ordering follows the spec: a pre-release sorts before its release,
 * numeric identifiers compare numerically and sort before alphanumeric ones, build metadata is ignored.
 */
public record SemVer(int major, int minor, int patch, List<String> preRelease) implements Comparable<SemVer> {
    private static final Pattern P = Pattern.compile(
            "v?(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?");

    public SemVer {
        preRelease = List.copyOf(preRelease);
    }

    /** Parses a version or tag; null when it is not a version (e.g. "dev"). A missing patch counts as 0. */
    public static SemVer parse(String s) {
        if (s == null) return null;
        Matcher m = P.matcher(s.trim());
        if (!m.matches()) return null;
        try {
            return new SemVer(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    m.group(3) == null ? 0 : Integer.parseInt(m.group(3)),
                    m.group(4) == null ? List.of() : List.of(m.group(4).split("\\.")));
        } catch (NumberFormatException e) {
            return null; // absurdly large numbers
        }
    }

    public boolean isPreRelease() {
        return !preRelease.isEmpty();
    }

    @Override
    public int compareTo(SemVer o) {
        int c = Integer.compare(major, o.major);
        if (c == 0) c = Integer.compare(minor, o.minor);
        if (c == 0) c = Integer.compare(patch, o.patch);
        if (c != 0) return c;
        if (preRelease.isEmpty() || o.preRelease.isEmpty()) {
            return Boolean.compare(preRelease.isEmpty(), o.preRelease.isEmpty()); // release > pre-release
        }
        for (int i = 0; i < Math.min(preRelease.size(), o.preRelease.size()); i++) {
            c = compareIdentifier(preRelease.get(i), o.preRelease.get(i));
            if (c != 0) return c;
        }
        return Integer.compare(preRelease.size(), o.preRelease.size());
    }

    private static int compareIdentifier(String a, String b) {
        boolean na = a.matches("\\d+");
        boolean nb = b.matches("\\d+");
        if (na && nb) {
            int c = Integer.compare(a.length(), b.length()); // no leading zeros in valid semver: longer = bigger
            return c != 0 ? c : a.compareTo(b);
        }
        if (na != nb) return na ? -1 : 1;
        return a.compareTo(b);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (preRelease.isEmpty() ? "" : "-" + String.join(".", preRelease));
    }
}
