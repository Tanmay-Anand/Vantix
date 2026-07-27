/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.model;

/**
 * A resolved scalar (or enum-typed) field of an {@link Entity}. Relations are not fields — see
 * {@link Relation}.
 *
 * <p>Nullable object components ({@code length}, {@code precision}, {@code defaultValue},
 * {@code rawDdl}) are {@code null} when the corresponding attribute is absent.
 *
 * @param name the field name in {@code schema.vx}
 * @param columnName the database column name (defaults to {@code name} unless {@code @column} set)
 * @param type the resolved scalar/enum type
 * @param nullable whether the column allows {@code NULL} (the {@code T?} marker)
 * @param id whether this is the primary key ({@code @id}) — exactly one per entity in v1
 * @param generated whether the id is database-generated ({@code @generated})
 * @param unique whether the column has a single-column unique constraint ({@code @unique})
 * @param updatedAt auto-managed update timestamp ({@code @updatedAt})
 * @param ignored present in Java only, absent from the DB ({@code @ignore} → {@code @Transient})
 * @param length {@code varchar(n)} length ({@code @length}); {@code null} if unset
 * @param precision numeric precision/scale ({@code @precision}); {@code null} if unset
 * @param defaultValue column default ({@code @default}); {@code null} if unset
 * @param rawDdl verbatim column DDL ({@code @raw}); {@code null} if unset
 */
public record Field(
        String name,
        String columnName,
        FieldType type,
        boolean nullable,
        boolean id,
        boolean generated,
        boolean unique,
        boolean updatedAt,
        boolean ignored,
        Integer length,
        Precision precision,
        DefaultValue defaultValue,
        String rawDdl,
        SourcePosition position) {}
