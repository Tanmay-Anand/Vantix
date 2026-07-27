/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.diagnostic;

/** Severity of a {@link Diagnostic}. Only {@code ERROR} fails a {@code validate}/{@code generate}. */
public enum Severity {
    ERROR,
    WARNING
}
