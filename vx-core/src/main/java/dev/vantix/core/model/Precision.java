/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.model;

/** Numeric precision/scale from {@code @precision(p, s)}, for {@code Decimal} fields. */
public record Precision(int precision, int scale) {}
