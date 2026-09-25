/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SuggestionsTest {

    private static final List<String> TYPES = List.of("String", "Int", "Long", "Instant", "LocalDate", "Json");

    @Test
    void suggestsTheClosestCandidate() {
        assertThat(Suggestions.closest("Strin", TYPES)).contains("String");
        assertThat(Suggestions.closest("Instnt", TYPES)).contains("Instant");
        assertThat(Suggestions.closest("Lng", TYPES)).contains("Long");
    }

    @Test
    void countsAnAdjacentTranspositionAsOneEdit() {
        assertThat(Suggestions.distance("stirng", "string")).isEqualTo(1);
        assertThat(Suggestions.closest("Stirng", TYPES)).contains("String");
    }

    @Test
    void prefersACaseInsensitiveExactMatch() {
        assertThat(Suggestions.closest("string", TYPES)).contains("String");
        assertThat(Suggestions.closest("localdate", TYPES)).contains("LocalDate");
    }

    @Test
    void staysSilentWhenNothingIsPlausible() {
        assertThat(Suggestions.closest("Money", TYPES)).isEmpty();
        assertThat(Suggestions.closest("Xyz", TYPES)).isEmpty();
        assertThat(Suggestions.didYouMean("Money", TYPES)).isNull();
    }

    @Test
    void formatsTheHint() {
        assertThat(Suggestions.didYouMean("Strng", TYPES)).isEqualTo("did you mean `String`?");
    }
}
