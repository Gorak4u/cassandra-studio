package com.cassandrastudio.engine.util;

import java.util.regex.Pattern;

/** Hides passwords in statements before they are stored in history or audit. */
public final class Masking {
    private static final Pattern PASSWORD = Pattern.compile("(?i)(\\bPASSWORD\\s*(?:=|:)?\\s*)'(?:[^']|'')*'");
    private static final Pattern HASHED = Pattern.compile("(?i)(\\bHASHED\\s+PASSWORD\\s*(?:=|:)?\\s*)'(?:[^']|'')*'");

    private Masking() {}

    public static String mask(String statement) {
        if (statement == null) return null;
        String s = HASHED.matcher(statement).replaceAll("$1'*****'");
        return PASSWORD.matcher(s).replaceAll("$1'*****'");
    }
}
