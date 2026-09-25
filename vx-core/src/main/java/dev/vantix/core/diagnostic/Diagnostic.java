/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.diagnostic;

import dev.vantix.core.model.SourcePosition;
import java.util.List;

/**
 * A single diagnostic message anchored to a source location, optionally with a "did you mean"
 * suggestion and secondary locations. Error messages are a headline feature of Vantix, so
 * diagnostics are first-class data (not just strings) and get their own regression tests.
 *
 * @param severity whether this blocks the build
 * @param message the human-readable message (without the location prefix; the renderer adds that)
 * @param position where the fix belongs (the primary caret)
 * @param suggestion an optional correction hint (e.g. {@code "did you mean `String`?"}); may be null
 * @param related other locations involved (e.g. "first declared here"), shown under the primary one
 */
public record Diagnostic(
        Severity severity, String message, SourcePosition position, String suggestion, List<Label> related) {

    /** A secondary location with a short note. */
    public record Label(SourcePosition position, String note) {}

    public Diagnostic {
        related = related == null ? List.of() : List.copyOf(related);
    }

    public Diagnostic(Severity severity, String message, SourcePosition position, String suggestion) {
        this(severity, message, position, suggestion, List.of());
    }

    public static Diagnostic error(String message, SourcePosition position) {
        return new Diagnostic(Severity.ERROR, message, position, null);
    }

    public static Diagnostic error(String message, SourcePosition position, String suggestion) {
        return new Diagnostic(Severity.ERROR, message, position, suggestion);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }
}
