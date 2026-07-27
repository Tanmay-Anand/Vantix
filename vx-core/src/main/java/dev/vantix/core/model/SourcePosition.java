/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.model;

/**
 * A location in a {@code schema.vx} source: 1-based {@code line} and {@code column}, 0-based byte
 * {@code offset}, and the {@code length} of the spanned token. Threaded through every AST node and
 * resolved-model element so any downstream error (semantic, diff, codegen) can point at the exact
 * source it came from.
 */
public record SourcePosition(int line, int column, int offset, int length) {

    /** Sentinel for elements synthesized by the tool rather than parsed from source. */
    public static final SourcePosition UNKNOWN = new SourcePosition(-1, -1, -1, 0);

    public static SourcePosition of(int line, int column, int offset, int length) {
        return new SourcePosition(line, column, offset, length);
    }
}
