/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The snapshot-diff migration engine and the highest-value differentiator.
 *
 * <ul>
 *   <li>{@code snapshot} — read/write {@code vantix/snapshot.json} (with a leading
 *       {@code formatVersion}; see IMPLEMENTATION_PLAN.md D14).
 *   <li>{@code diff} — {@code SchemaDiffer} produces a sealed {@code List<SchemaChange>}; pure, no
 *       database.
 *   <li>{@code sql} — {@code PostgresRenderer} emits topologically-ordered DDL to Flyway-named
 *       {@code V<timestamp>__slug.sql} files (D11).
 *   <li>{@code introspect} — {@code information_schema} reader powering {@code db pull}, the drift
 *       check, and the shadow-DB verify path (D13).
 * </ul>
 */
package dev.vantix.migrate;
