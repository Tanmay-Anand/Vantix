/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;
import java.util.Optional;

/**
 * The resolved, immutable schema model — the contract every downstream module (codegen, migrate)
 * consumes, and the thing serialized into {@code vantix/snapshot.json}.
 *
 * <p>{@code formatVersion} is written first (see {@link JsonPropertyOrder}) so the snapshot format
 * can evolve with a documented upgrade path without breaking existing snapshots (D14).
 */
@JsonPropertyOrder({"formatVersion", "datasource", "generator", "enums", "entities"})
public record Schema(
        int formatVersion, Datasource datasource, Generator generator, List<EnumDecl> enums, List<Entity> entities) {

    /** The current snapshot format version. Bump when the serialized shape changes incompatibly. */
    public static final int CURRENT_FORMAT_VERSION = 1;

    public Schema {
        enums = List.copyOf(enums);
        entities = List.copyOf(entities);
    }

    public Optional<Entity> entity(String name) {
        return entities.stream().filter(e -> e.name().equals(name)).findFirst();
    }

    public Optional<EnumDecl> enumByName(String name) {
        return enums.stream().filter(e -> e.name().equals(name)).findFirst();
    }
}
