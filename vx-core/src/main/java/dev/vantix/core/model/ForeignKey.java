/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.model;

/**
 * The foreign-key detail of the owning side of a relation: the FK {@code columnName} on this
 * entity's table, the {@code referencedColumn} on the target table, and the {@code onDelete}
 * action.
 */
public record ForeignKey(String columnName, String referencedColumn, OnDelete onDelete) {}
