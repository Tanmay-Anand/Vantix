/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.model;

/**
 * The resolved {@code datasource} block. The {@code url} is kept as its source expression (e.g.
 * {@code env("DATABASE_URL")}) and resolved to an actual value only at the point of use — Vantix
 * never persists a connection string into a snapshot, log, or error.
 *
 * @param provider database provider (only {@code "postgresql"} in v1)
 * @param url the URL expression as written in the schema
 * @param schema default schema name, or {@code null}
 */
public record Datasource(String provider, String url, String schema) {}
