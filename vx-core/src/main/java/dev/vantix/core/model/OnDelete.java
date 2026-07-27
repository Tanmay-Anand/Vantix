/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.model;

/** Referential action for a foreign key, from {@code @relation(onDelete: ...)}. */
public enum OnDelete {
    NO_ACTION,
    RESTRICT,
    CASCADE,
    SET_NULL,
    SET_DEFAULT
}
