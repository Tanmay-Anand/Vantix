/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.parser;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vantix.core.ast.Ast;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParserTest {

    private static Ast.SchemaFile parseClean(String source) {
        Parser.Result result = Parser.parse(source);
        assertThat(result.diagnostics()).isEmpty();
        return result.file();
    }

    @Test
    void parsesBlocksEnumsAndEntitiesInSourceOrder() {
        Ast.SchemaFile file = parseClean("""
                datasource {
                  provider = "postgresql"
                  url      = env("DATABASE_URL")
                }
                enum Status { ACTIVE BANNED }
                entity User {
                  id Long @id
                }
                """);

        assertThat(file.declarations()).hasSize(3);
        Ast.Block datasource = (Ast.Block) file.declarations().get(0);
        assertThat(datasource.keyword().text()).isEqualTo("datasource");
        assertThat(datasource.assignments()).extracting(a -> a.key().text()).containsExactly("provider", "url");
        Ast.Call env = (Ast.Call) datasource.assignments().get(1).value();
        assertThat(env.function().text()).isEqualTo("env");
        assertThat(env.args())
                .singleElement()
                .isEqualTo(
                        new Ast.StringLit("DATABASE_URL", env.args().getFirst().position()));

        Ast.EnumDecl status = (Ast.EnumDecl) file.declarations().get(1);
        assertThat(status.values()).extracting(Ast.Name::text).containsExactly("ACTIVE", "BANNED");
        assertThat(file.declarations().get(2)).isInstanceOf(Ast.EntityDecl.class);
    }

    @Test
    void parsesTypeMarkersAndAttributeArguments() {
        Ast.SchemaFile file = parseClean("""
                entity Order {
                  total   Decimal? @precision(12, 2) @default(0.05)
                  items   Item[]
                  user    User     @relation(fields: [userId], references: [id], onDelete: Cascade)
                  @@index([userId, total], name: "order_user_idx")
                }
                """);
        Ast.EntityDecl order = (Ast.EntityDecl) file.declarations().getFirst();

        Ast.FieldDecl total = order.fields().get(0);
        assertThat(total.type().optional()).isTrue();
        assertThat(total.type().list()).isFalse();
        assertThat(total.attributes()).extracting(a -> a.name().text()).containsExactly("precision", "default");
        assertThat(total.attributes().get(0).args())
                .extracting(e -> ((Ast.IntLit) e).text())
                .containsExactly("12", "2");
        assertThat(total.attributes().get(1).args().getFirst()).isInstanceOf(Ast.FloatLit.class);

        assertThat(order.fields().get(1).type().list()).isTrue();

        List<Ast.Expr> relationArgs =
                order.fields().get(2).attributes().getFirst().args();
        assertThat(relationArgs).allSatisfy(arg -> assertThat(arg).isInstanceOf(Ast.Named.class));
        Ast.Named onDelete = (Ast.Named) relationArgs.get(2);
        assertThat(onDelete.value()).isInstanceOf(Ast.Ident.class);

        Ast.AttributeDecl index = order.attributes().getFirst();
        assertThat(index.entityLevel()).isTrue();
        assertThat(((Ast.ListExpr) index.args().getFirst()).items())
                .extracting(Ast.Name::text)
                .containsExactly("userId", "total");
    }

    @Test
    void contextualKeywordsCanBeFieldNames() {
        Ast.SchemaFile file = parseClean("""
                entity Thing {
                  entity String
                  enum   String
                }
                """);
        Ast.EntityDecl thing = (Ast.EntityDecl) file.declarations().getFirst();
        assertThat(thing.fields()).extracting(f -> f.name().text()).containsExactly("entity", "enum");
    }

    @Test
    void recoversInsideAnEntityAndKeepsParsingLaterMembers() {
        Parser.Result result = Parser.parse("""
                entity User {
                  id    Long @id
                  email @unique
                  name  String
                }
                """);

        assertThat(result.diagnostics()).hasSize(1);
        Ast.EntityDecl user = (Ast.EntityDecl) result.file().declarations().getFirst();
        // `email` is dropped, but the member after it still parses.
        assertThat(user.fields()).extracting(f -> f.name().text()).containsExactly("id", "name");
    }

    @Test
    void recoversAtTopLevelAndReachesTheNextDeclaration() {
        Parser.Result result = Parser.parse("""
                entiy Broken {
                  x Int
                }
                entity Ok {
                  id Long @id
                }
                """);

        assertThat(result.diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.position().line()).isEqualTo(1);
            assertThat(d.suggestion()).isEqualTo("did you mean `entity`?");
        });
        assertThat(result.file().declarations())
                .singleElement()
                .satisfies(d -> assertThat(((Ast.EntityDecl) d).name().text()).isEqualTo("Ok"));
    }

    @Test
    void reportsEveryErrorInOnePass() {
        Parser.Result result = Parser.parse("""
                entity A {
                  x @id
                  y Int @length(
                }
                entity B {
                  z Int?[
                }
                """);
        assertThat(result.diagnostics()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(result.diagnostics()).extracting(d -> d.position().line()).isSorted();
    }

    @Test
    void anEmptySchemaIsValid() {
        assertThat(parseClean("// nothing yet\n").declarations()).isEmpty();
    }
}
