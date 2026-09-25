/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vantix.core.SchemaCompiler;
import dev.vantix.core.diagnostic.Diagnostic;
import dev.vantix.core.model.DefaultValue;
import dev.vantix.core.model.Entity;
import dev.vantix.core.model.Field;
import dev.vantix.core.model.FieldType;
import dev.vantix.core.model.ForeignKey;
import dev.vantix.core.model.Index;
import dev.vantix.core.model.OnDelete;
import dev.vantix.core.model.Precision;
import dev.vantix.core.model.Relation;
import dev.vantix.core.model.RelationKind;
import dev.vantix.core.model.ScalarType;
import dev.vantix.core.model.Schema;
import dev.vantix.core.model.SchemaMapper;
import org.junit.jupiter.api.Test;

class SchemaAnalyzerTest {

    /** The canonical v1 sample from docs/grammar.md (with `@@table("orders")` to dodge the reserved word). */
    static final String SHOP = """
            datasource {
              provider = "postgresql"
              url      = env("DATABASE_URL")
            }

            generator {
              package = "com.acme.shop"
              output  = "target/generated-sources/vantix"
            }

            enum OrderStatus { PENDING PAID SHIPPED CANCELLED }

            entity User {
              id        Long    @id @generated
              email     String  @unique @length(180)
              name      String  @length(120)
              createdAt Instant @default(now())
              orders    Order[]
              profile   Profile?

              @@index([createdAt])
              @@table("users")
            }

            entity Profile {
              id     Long    @id @generated
              bio    String? @length(500)
              user   User    @relation(fields: [userId], references: [id])
              userId Long    @unique
            }

            entity Order {
              id       Long        @id @generated
              status   OrderStatus @default(PENDING)
              total    Decimal     @precision(12, 2)
              placedAt Instant     @default(now())
              user     User        @relation(fields: [userId], references: [id], onDelete: Restrict)
              userId   Long

              @@index([userId, placedAt])
              @@table("orders")
            }
            """;

    /** No errors; warnings are asserted by the tests that care about them. */
    private static Schema compileClean(String source) {
        SchemaCompiler.Result result = SchemaCompiler.compile(source);
        assertThat(result.diagnostics()).filteredOn(Diagnostic::isError).isEmpty();
        return result.schema();
    }

    private static Field field(Entity e, String name) {
        return e.fields().stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static Relation relation(Entity e, String name) {
        return e.relations().stream()
                .filter(r -> r.fieldName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void resolvesTheCanonicalSample() {
        Schema schema = compileClean(SHOP);

        assertThat(schema.formatVersion()).isEqualTo(Schema.CURRENT_FORMAT_VERSION);
        assertThat(schema.datasource().url()).isEqualTo("env(\"DATABASE_URL\")");
        assertThat(schema.generator().packageName()).isEqualTo("com.acme.shop");
        assertThat(schema.enums()).singleElement().satisfies(e -> assertThat(e.values())
                .containsExactly("PENDING", "PAID", "SHIPPED", "CANCELLED"));
        assertThat(schema.entities()).extracting(Entity::name).containsExactly("User", "Profile", "Order");
    }

    @Test
    void derivesSnakeCaseColumnsAndHonoursTableOverrides() {
        Schema schema = compileClean(SHOP);
        Entity user = schema.entity("User").orElseThrow();

        assertThat(user.tableName()).isEqualTo("users");
        assertThat(user.schemaName()).isNull(); // reserved for @@schema (D14)
        assertThat(field(user, "createdAt").columnName()).isEqualTo("created_at");
        assertThat(schema.entity("Profile").orElseThrow().tableName()).isEqualTo("profile");
    }

    @Test
    void resolvesScalarAttributes() {
        Schema schema = compileClean(SHOP);
        Entity user = schema.entity("User").orElseThrow();
        Entity order = schema.entity("Order").orElseThrow();

        Field id = field(user, "id");
        assertThat(id.id()).isTrue();
        assertThat(id.generated()).isTrue();
        assertThat(id.type()).isEqualTo(new FieldType.Scalar(ScalarType.LONG));

        Field email = field(user, "email");
        assertThat(email.unique()).isTrue();
        assertThat(email.length()).isEqualTo(180);
        assertThat(email.nullable()).isFalse();

        assertThat(field(user, "createdAt").defaultValue()).isEqualTo(new DefaultValue.Now());
        assertThat(field(order, "status").type()).isEqualTo(new FieldType.EnumRef("OrderStatus"));
        assertThat(field(order, "status").defaultValue()).isEqualTo(new DefaultValue.Literal("PENDING"));
        assertThat(field(order, "total").precision()).isEqualTo(new Precision(12, 2));
        assertThat(field(schema.entity("Profile").orElseThrow(), "bio").nullable())
                .isTrue();
    }

    @Test
    void pairsBothSidesOfEachRelationAndInfersMappedBy() {
        Schema schema = compileClean(SHOP);
        Entity user = schema.entity("User").orElseThrow();
        Entity order = schema.entity("Order").orElseThrow();
        Entity profile = schema.entity("Profile").orElseThrow();

        Relation orderUser = relation(order, "user");
        assertThat(orderUser.owning()).isTrue();
        assertThat(orderUser.kind()).isEqualTo(RelationKind.MANY_TO_ONE);
        assertThat(orderUser.foreignKey()).isEqualTo(new ForeignKey("user_id", "id", OnDelete.RESTRICT));

        Relation orders = relation(user, "orders");
        assertThat(orders.owning()).isFalse();
        assertThat(orders.kind()).isEqualTo(RelationKind.ONE_TO_MANY);
        assertThat(orders.mappedBy()).isEqualTo("user");
        assertThat(orders.foreignKey()).isNull();

        // `userId @unique` makes Profile.user one-to-one, and User.profile its optional inverse.
        assertThat(relation(profile, "user").kind()).isEqualTo(RelationKind.ONE_TO_ONE);
        assertThat(relation(profile, "user").foreignKey().onDelete()).isEqualTo(OnDelete.NO_ACTION);
        Relation userProfile = relation(user, "profile");
        assertThat(userProfile.kind()).isEqualTo(RelationKind.ONE_TO_ONE);
        assertThat(userProfile.mappedBy()).isEqualTo("user");
        assertThat(userProfile.nullable()).isTrue();
    }

    @Test
    void warnsThatAnInverseOneToOneCannotBeLazy() {
        SchemaCompiler.Result result = SchemaCompiler.compile(SHOP);
        assertThat(result.diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.isError()).isFalse();
            assertThat(d.message())
                    .isEqualTo(
                            "`User.profile` cannot be loaded lazily: Hibernate runs one extra query per `User` to fill it");
            assertThat(d.suggestion()).contains("ProfileRepository.findByUserId(...)");
        });
    }

    @Test
    void resolvesEntityIndexes() {
        Entity order = compileClean(SHOP).entity("Order").orElseThrow();
        assertThat(order.indexes()).singleElement().satisfies(i -> {
            assertThat(i.fieldNames()).containsExactly("userId", "placedAt");
            assertThat(i.unique()).isFalse();
            assertThat(i.name()).isNull();
        });
    }

    @Test
    void supportsSelfRelations() {
        Schema schema = compileClean("""
                generator { package = "com.example" }
                entity Category {
                  id       Long       @id @generated
                  parent   Category?  @relation(fields: [parentId], references: [id], onDelete: SetNull)
                  parentId Long?
                  children Category[]
                }
                """);
        Entity category = schema.entity("Category").orElseThrow();
        assertThat(relation(category, "parent").kind()).isEqualTo(RelationKind.MANY_TO_ONE);
        assertThat(relation(category, "parent").nullable()).isTrue();
        assertThat(relation(category, "children").mappedBy()).isEqualTo("parent");
    }

    @Test
    void appliesGeneratorDefaults() {
        Schema schema = compileClean("""
                generator { package = "com.example" }
                entity Thing {
                  id Long @id
                }
                """);
        assertThat(schema.generator().outputDirectory()).isEqualTo(SchemaAnalyzer.DEFAULT_OUTPUT);
        assertThat(schema.generator().entitySuffix()).isEmpty();
        assertThat(schema.generator().generateRepositories()).isTrue();
        assertThat(schema.generator().useGenerationGap()).isFalse();
        assertThat(schema.datasource()).isNull();
    }

    @Test
    void warningsDoNotBlockTheModel() {
        SchemaCompiler.Result result = SchemaCompiler.compile("""
                entity Thing {
                  id Long @id
                }
                """);
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.diagnostics()).extracting(Diagnostic::isError).containsOnly(false);
        assertThat(result.schema()).isNotNull();
        assertThat(result.schema().generator().packageName()).isEqualTo(SchemaAnalyzer.DEFAULT_PACKAGE);
    }

    @Test
    void errorsSuppressTheModel() {
        SchemaCompiler.Result result = SchemaCompiler.compile("""
                generator { package = "com.example" }
                entity Thing {
                  id Long @id
                  x  Strng
                }
                """);
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.schema()).isNull();
    }

    @Test
    void resolvesEveryDefaultLiteralKind() {
        Entity e = compileClean("""
                generator { package = "com.example" }
                entity Defaults {
                  id      UUID    @id @generated
                  token   UUID    @default(uuid())
                  label   String  @default("say \\"hi\\"")
                  count   Int     @default(-3)
                  ratio   Float   @default(0.05)
                  price   Decimal @default(10)
                  active  Boolean @default(false)
                  day     LocalDate @default(now())
                }
                """).entity("Defaults").orElseThrow();

        assertThat(field(e, "token").defaultValue()).isEqualTo(new DefaultValue.UuidGen());
        assertThat(field(e, "label").defaultValue()).isEqualTo(new DefaultValue.Literal("say \"hi\""));
        assertThat(field(e, "count").defaultValue()).isEqualTo(new DefaultValue.Literal("-3"));
        assertThat(field(e, "ratio").defaultValue()).isEqualTo(new DefaultValue.Literal("0.05"));
        assertThat(field(e, "price").defaultValue()).isEqualTo(new DefaultValue.Literal("10"));
        assertThat(field(e, "active").defaultValue()).isEqualTo(new DefaultValue.Literal("false"));
        assertThat(field(e, "day").defaultValue()).isEqualTo(new DefaultValue.Now());
    }

    @Test
    void compositeUniqueConstraintsCarryTheirName() {
        Entity e = compileClean("""
                generator { package = "com.example" }
                entity Seat {
                  id     Long   @id
                  row    String
                  number Int
                  @@unique([row, number], name: "seat_position_key")
                }
                """).entity("Seat").orElseThrow();
        assertThat(e.indexes())
                .containsExactly(new Index(
                        "seat_position_key",
                        java.util.List.of("row", "number"),
                        true,
                        e.indexes().getFirst().position()));
    }

    @Test
    void theResolvedModelRoundTripsThroughTheSnapshotFormat() {
        Schema schema = compileClean(SHOP);
        assertThat(SchemaMapper.fromJson(SchemaMapper.toJson(schema))).isEqualTo(schema);
    }
}
