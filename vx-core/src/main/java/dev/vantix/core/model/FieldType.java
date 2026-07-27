/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * The resolved type of a scalar {@link Field}: either a built-in {@link ScalarType} or a reference
 * to a declared {@link EnumDecl}. Relations are modelled separately (see {@link Relation}) and are
 * not {@code FieldType}s.
 *
 * <p>Sealed so exhaustive {@code switch} in codegen and the differ catches any missed case at
 * compile time. Jackson uses an explicit {@code kind} discriminator (deduction cannot distinguish
 * the two no-arg default variants elsewhere, so we standardize on named subtypes across the model).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = FieldType.Scalar.class, name = "scalar"),
    @JsonSubTypes.Type(value = FieldType.EnumRef.class, name = "enumRef")
})
public sealed interface FieldType permits FieldType.Scalar, FieldType.EnumRef {

    /** A built-in scalar type. */
    record Scalar(ScalarType type) implements FieldType {}

    /** A reference to a declared enum, by name. */
    record EnumRef(String enumName) implements FieldType {}
}
