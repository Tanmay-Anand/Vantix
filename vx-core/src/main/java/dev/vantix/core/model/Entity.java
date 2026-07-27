/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.model;

import java.util.List;
import java.util.Optional;

/**
 * A resolved entity: its scalar {@link Field}s, its {@link Relation}s, and its {@link Index}es.
 *
 * @param name the entity name in {@code schema.vx}
 * @param tableName the database table name (defaults from {@code name} unless {@code @@table} set)
 * @param schemaName reserved for {@code @@schema} placement; always {@code null} in v1 (D14) but
 *     present in the model and snapshot so adding multi-schema support later does not invalidate
 *     existing snapshots
 */
public record Entity(
        String name,
        String tableName,
        String schemaName,
        List<Field> fields,
        List<Relation> relations,
        List<Index> indexes,
        SourcePosition position) {

    public Entity {
        fields = List.copyOf(fields);
        relations = List.copyOf(relations);
        indexes = List.copyOf(indexes);
    }

    /** The primary-key field, if one is declared (semantic analysis guarantees exactly one in v1). */
    public Optional<Field> idField() {
        return fields.stream().filter(Field::id).findFirst();
    }
}
