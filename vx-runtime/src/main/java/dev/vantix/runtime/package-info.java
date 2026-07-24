/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * The only Vantix artifact that ships inside a user's application: the fluent query builder
 * (which compiles down to JPA Criteria) and the Hibernate exception translator.
 *
 * <p>Kept to near-zero dependencies (Jakarta Persistence API only, {@code provided} scope) so it
 * imposes nothing on the host app and preserves the ejectability guarantee.
 */
package dev.vantix.runtime;
