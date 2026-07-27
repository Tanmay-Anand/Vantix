/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaMapperRoundTripTest {

    @Test
    void roundTripsAFullSchemaExactly() {
        Schema original = sampleSchema();

        Schema back = SchemaMapper.fromJson(SchemaMapper.toJson(original));

        // Records give deep structural equality for free, so this covers every nested type,
        // every sealed subtype, and every source position.
        assertThat(back).isEqualTo(original);
    }

    @Test
    void serializationIsDeterministic() {
        Schema schema = sampleSchema();

        assertThat(SchemaMapper.toJson(schema)).isEqualTo(SchemaMapper.toJson(schema));
    }

    @Test
    void formatVersionIsSerializedFirst() {
        String json = SchemaMapper.toJson(sampleSchema());

        assertThat(json.stripLeading()).startsWith("{");
        assertThat(json).contains("\"formatVersion\"");
        // formatVersion must precede the bulky content so the snapshot's version is obvious and the
        // format can evolve with a documented upgrade path (D14).
        assertThat(json.indexOf("\"formatVersion\""))
                .isLessThan(json.indexOf("\"entities\""))
                .isLessThan(json.indexOf("\"generator\""));
    }

    @Test
    void preservesSealedSubtypesThroughTheRoundTrip() {
        Schema back = SchemaMapper.fromJson(SchemaMapper.toJson(sampleSchema()));

        Field status = back.entity("Order").orElseThrow().fields().stream()
                .filter(f -> f.name().equals("status"))
                .findFirst()
                .orElseThrow();
        assertThat(status.type()).isInstanceOf(FieldType.EnumRef.class);
        assertThat(status.defaultValue()).isInstanceOf(DefaultValue.Literal.class);

        Field token = back.entity("User").orElseThrow().fields().stream()
                .filter(f -> f.name().equals("token"))
                .findFirst()
                .orElseThrow();
        assertThat(token.defaultValue()).isInstanceOf(DefaultValue.UuidGen.class);
    }

    /**
     * A schema that exercises every scalar family, both {@link FieldType} variants, all three
     * {@link DefaultValue} variants, precision, indexes, and owning/inverse relations of both
     * cardinalities supported in v1.
     */
    private static Schema sampleSchema() {
        SourcePosition pos = SourcePosition.of(1, 1, 0, 4);

        EnumDecl orderStatus = new EnumDecl("OrderStatus", List.of("PENDING", "PAID", "SHIPPED", "CANCELLED"), pos);

        Entity user = new Entity(
                "User",
                "users",
                null,
                List.of(
                        idField(pos),
                        new Field(
                                "email",
                                "email",
                                new FieldType.Scalar(ScalarType.STRING),
                                false,
                                false,
                                false,
                                true,
                                false,
                                false,
                                180,
                                null,
                                null,
                                null,
                                pos),
                        new Field(
                                "token",
                                "token",
                                new FieldType.Scalar(ScalarType.UUID),
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                null,
                                null,
                                new DefaultValue.UuidGen(),
                                null,
                                pos),
                        new Field(
                                "createdAt",
                                "created_at",
                                new FieldType.Scalar(ScalarType.INSTANT),
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                null,
                                null,
                                new DefaultValue.Now(),
                                null,
                                pos)),
                List.of(
                        new Relation("orders", RelationKind.ONE_TO_MANY, "Order", false, "user", null, false, pos),
                        new Relation("profile", RelationKind.ONE_TO_ONE, "Profile", false, "user", null, true, pos)),
                List.of(new Index(null, List.of("createdAt"), false, pos)),
                pos);

        Entity profile = new Entity(
                "Profile",
                "profiles",
                null,
                List.of(
                        idField(pos),
                        new Field(
                                "userId",
                                "user_id",
                                new FieldType.Scalar(ScalarType.LONG),
                                false,
                                false,
                                false,
                                true,
                                false,
                                false,
                                null,
                                null,
                                null,
                                null,
                                pos)),
                List.of(new Relation(
                        "user",
                        RelationKind.ONE_TO_ONE,
                        "User",
                        true,
                        null,
                        new ForeignKey("user_id", "id", OnDelete.CASCADE),
                        false,
                        pos)),
                List.of(),
                pos);

        Entity order = new Entity(
                "Order",
                "orders",
                null,
                List.of(
                        idField(pos),
                        new Field(
                                "status",
                                "status",
                                new FieldType.EnumRef("OrderStatus"),
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                null,
                                null,
                                new DefaultValue.Literal("PENDING"),
                                null,
                                pos),
                        new Field(
                                "total",
                                "total",
                                new FieldType.Scalar(ScalarType.DECIMAL),
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                null,
                                new Precision(12, 2),
                                null,
                                null,
                                pos),
                        new Field(
                                "userId",
                                "user_id",
                                new FieldType.Scalar(ScalarType.LONG),
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                null,
                                null,
                                null,
                                null,
                                pos)),
                List.of(new Relation(
                        "user",
                        RelationKind.MANY_TO_ONE,
                        "User",
                        true,
                        null,
                        new ForeignKey("user_id", "id", OnDelete.RESTRICT),
                        false,
                        pos)),
                List.of(new Index(null, List.of("userId", "placedAt"), false, pos)),
                pos);

        Datasource datasource = new Datasource("postgresql", "env(\"DATABASE_URL\")", "public");
        Generator generator =
                new Generator("com.acme.shop", "target/generated-sources/vantix", "", true, false, false, false);

        return new Schema(
                Schema.CURRENT_FORMAT_VERSION,
                datasource,
                generator,
                List.of(orderStatus),
                List.of(user, profile, order));
    }

    private static Field idField(SourcePosition pos) {
        return new Field(
                "id",
                "id",
                new FieldType.Scalar(ScalarType.LONG),
                false,
                true,
                true,
                false,
                false,
                false,
                null,
                null,
                null,
                null,
                pos);
    }
}
