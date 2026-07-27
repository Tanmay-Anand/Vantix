/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.model;

import java.util.List;

/** A resolved enum declaration and its ordered constant names. */
public record EnumDecl(String name, List<String> values, SourcePosition position) {

    public EnumDecl {
        values = List.copyOf(values);
    }
}
