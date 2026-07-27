/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.model;

/**
 * The resolved {@code generator} block: where and how code is emitted.
 *
 * @param packageName base package for generated types
 * @param outputDirectory where generated sources are written (default under {@code target/})
 * @param entitySuffix optional suffix on generated entity names (e.g. {@code "Entity"})
 * @param generateRepositories whether to emit Spring Data repositories
 * @param generateMetamodel whether to emit the typed metamodel (Phase 3; {@code UserFields}, D3)
 * @param generateDtos whether to emit DTOs/mappers (Phase 5, opt-in)
 * @param useGenerationGap whether to emit {@code abstract XBase} + a scaffolded editable {@code X}
 */
public record Generator(
        String packageName,
        String outputDirectory,
        String entitySuffix,
        boolean generateRepositories,
        boolean generateMetamodel,
        boolean generateDtos,
        boolean useGenerationGap) {}
