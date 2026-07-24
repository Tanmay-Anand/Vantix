/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Turns the resolved {@code Schema} model into Java source with JavaPoet: JPA entities
 * (proxy-safe {@code equals}/{@code hashCode}, correct {@code mappedBy}, LAZY fetch defaults),
 * Spring Data repositories, and — in Phase 3 — the typed metamodel ({@code UserFields}, never
 * {@code User_}; see IMPLEMENTATION_PLAN.md D3).
 *
 * <p>Generated sources are written under {@code target/generated-sources} and are disposable:
 * content-hashed before write to preserve incremental builds, never edited, excluded from VCS.
 */
package dev.vantix.codegen;
