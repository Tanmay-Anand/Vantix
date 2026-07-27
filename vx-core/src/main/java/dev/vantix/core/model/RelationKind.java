/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.model;

/**
 * The cardinality of a resolved {@link Relation}. Many-to-many is deliberately absent in v1
 * (Phase 2.5, D7).
 */
public enum RelationKind {
    ONE_TO_ONE,
    ONE_TO_MANY,
    MANY_TO_ONE
}
