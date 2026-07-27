/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.model;

import java.util.List;

/**
 * A resolved index or unique constraint over one or more columns, from a field-level {@code @unique}
 * or an entity-level {@code @@index([...])} / {@code @@unique([...])}.
 *
 * @param name explicit name, or {@code null} to let codegen/SQL derive a deterministic one
 * @param fieldNames the participating field names, in order
 * @param unique whether this is a unique constraint rather than a plain index
 */
public record Index(String name, List<String> fieldNames, boolean unique, SourcePosition position) {

    public Index {
        fieldNames = List.copyOf(fieldNames);
    }
}
