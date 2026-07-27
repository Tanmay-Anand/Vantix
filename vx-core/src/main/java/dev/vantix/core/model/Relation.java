/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.model;

/**
 * A resolved association between entities (v1: one-to-one and one-to-many only).
 *
 * <p>Exactly one side is the {@code owning} side (it holds the {@link ForeignKey}); the inverse side
 * carries {@code mappedBy} naming the owning field on the other entity. This is what lets codegen
 * emit correct {@code @OneToMany(mappedBy = ...)} / {@code @ManyToOne @JoinColumn} pairs.
 *
 * @param fieldName the field on this entity that declares the relation (e.g. {@code "orders"})
 * @param kind cardinality from this entity's perspective
 * @param targetEntity the related entity's name (e.g. {@code "Order"})
 * @param owning whether this side owns the foreign key
 * @param mappedBy on the inverse side, the owning field name on the target entity; else {@code null}
 * @param foreignKey present on the owning side; {@code null} on the inverse side
 * @param nullable whether the association is optional (e.g. {@code Profile?})
 */
public record Relation(
        String fieldName,
        RelationKind kind,
        String targetEntity,
        boolean owning,
        String mappedBy,
        ForeignKey foreignKey,
        boolean nullable,
        SourcePosition position) {}
