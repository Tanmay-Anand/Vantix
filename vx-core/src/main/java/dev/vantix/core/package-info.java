/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * SDL front end: lexer, recursive-descent parser, semantic analysis, and the resolved
 * {@code Schema} model that every downstream module (codegen, migrate) consumes.
 *
 * <p>This module has zero Spring and zero JavaPoet dependency by design — enforced by the
 * {@code ban-spring-in-core} rule in this module's POM.
 */
package dev.vantix.core;
