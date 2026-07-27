/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.model;

import java.util.Optional;

/**
 * The built-in scalar types of the schema language (v1). Enum values that are neither a scalar here
 * nor a declared {@code enum}/{@code entity} name are reported by semantic analysis with a
 * "did you mean" suggestion.
 */
public enum ScalarType {
    STRING("String"),
    INT("Int"),
    LONG("Long"),
    DECIMAL("Decimal"),
    FLOAT("Float"),
    BOOLEAN("Boolean"),
    INSTANT("Instant"),
    LOCAL_DATE("LocalDate"),
    LOCAL_DATE_TIME("LocalDateTime"),
    UUID("UUID"),
    JSON("Json"),
    BYTES("Bytes");

    private final String schemaName;

    ScalarType(String schemaName) {
        this.schemaName = schemaName;
    }

    /** The spelling used in {@code schema.vx} (e.g. {@code "LocalDateTime"}). */
    public String schemaName() {
        return schemaName;
    }

    /** Resolves a source spelling to a scalar type, if it names one. */
    public static Optional<ScalarType> fromSchemaName(String name) {
        for (ScalarType t : values()) {
            if (t.schemaName.equals(name)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
