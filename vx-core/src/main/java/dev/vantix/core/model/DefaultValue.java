/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A resolved column default from {@code @default(...)}. {@link Now} and {@link UuidGen} are function
 * defaults; {@link Literal} carries a literal string/number/boolean or an enum constant name.
 *
 * <p>Sealed for exhaustive handling in the Postgres renderer.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
    @JsonSubTypes.Type(value = DefaultValue.Now.class, name = "now"),
    @JsonSubTypes.Type(value = DefaultValue.UuidGen.class, name = "uuid"),
    @JsonSubTypes.Type(value = DefaultValue.Literal.class, name = "literal")
})
public sealed interface DefaultValue permits DefaultValue.Now, DefaultValue.UuidGen, DefaultValue.Literal {

    /** {@code @default(now())} — current timestamp. */
    record Now() implements DefaultValue {}

    /** {@code @default(uuid())} — generated UUID. */
    record UuidGen() implements DefaultValue {}

    /** A literal default: a number, quoted string, boolean, or enum constant name. */
    record Literal(String value) implements DefaultValue {}
}
