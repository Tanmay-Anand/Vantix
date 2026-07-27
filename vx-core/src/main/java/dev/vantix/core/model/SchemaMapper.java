/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Serializes the resolved {@link Schema} model to and from JSON — the persisted form used for the
 * migration snapshot ({@code vantix/snapshot.json}).
 *
 * <p>Output is deterministic (stable property order, indented) so snapshots produce reviewable
 * diffs and byte-identical output for identical input. {@code null} object properties are omitted
 * to keep the snapshot clean; absent properties deserialize back to {@code null}.
 */
public final class SchemaMapper {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private SchemaMapper() {}

    public static String toJson(Schema schema) {
        try {
            return MAPPER.writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize schema model to JSON", e);
        }
    }

    public static Schema fromJson(String json) {
        try {
            return MAPPER.readValue(json, Schema.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to read schema model from JSON", e);
        }
    }
}
