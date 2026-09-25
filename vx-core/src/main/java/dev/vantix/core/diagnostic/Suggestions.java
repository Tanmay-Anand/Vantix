/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.diagnostic;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

/**
 * The "did you mean" engine: finds the known name closest to a misspelling.
 *
 * <p>Distance is optimal-string-alignment (Levenshtein plus adjacent transposition, so {@code Stirng}
 * is one edit from {@code String}). A candidate only counts if it is within roughly a third of the
 * input's length — suggesting {@code Int} for {@code Json} would be worse than suggesting nothing. A
 * case-insensitive exact match always wins ({@code string} → {@code String}).
 */
public final class Suggestions {

    private Suggestions() {}

    /** The closest candidate, or empty when nothing is close enough to be a plausible typo. */
    public static Optional<String> closest(String input, Collection<String> candidates) {
        for (String candidate : candidates) {
            if (candidate.equalsIgnoreCase(input)) {
                return Optional.of(candidate);
            }
        }
        int threshold = Math.max(1, Math.min(3, input.length() / 3));
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int d = distance(input.toLowerCase(Locale.ROOT), candidate.toLowerCase(Locale.ROOT));
            if (d <= threshold && d < bestDistance) {
                best = candidate;
                bestDistance = d;
            }
        }
        return Optional.ofNullable(best);
    }

    /** {@code "did you mean `X`?"} for the closest candidate, or {@code null} when there is none. */
    public static String didYouMean(String input, Collection<String> candidates) {
        return closest(input, candidates).map(s -> "did you mean `" + s + "`?").orElse(null);
    }

    /** Optimal string alignment distance (edits, with adjacent transpositions counting as one). */
    static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }
}
