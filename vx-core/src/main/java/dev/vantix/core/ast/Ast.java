/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.ast;

import dev.vantix.core.model.SourcePosition;
import java.util.List;

/**
 * The syntax tree produced by the {@link dev.vantix.core.parser.Parser}: a faithful, unvalidated
 * picture of what was written in {@code schema.vx}. Nothing here is resolved — a field's type is just
 * a name, and attributes are just names with arguments. {@link dev.vantix.core.semantic.SchemaAnalyzer}
 * turns this into the resolved {@link dev.vantix.core.model.Schema}.
 *
 * <p>Every node carries the {@link SourcePosition} diagnostics should point at.
 */
public final class Ast {

    private Ast() {}

    /** A whole {@code schema.vx} file: its top-level declarations in source order. */
    public record SchemaFile(List<Decl> declarations) {
        public SchemaFile {
            declarations = List.copyOf(declarations);
        }
    }

    /** A top-level declaration. */
    public sealed interface Decl permits Block, EnumDecl, EntityDecl {}

    /** An identifier together with where it was written. */
    public record Name(String text, SourcePosition position) {}

    /**
     * A {@code keyword { key = value ... }} configuration block. {@code datasource} and
     * {@code generator} are the valid keywords; the parser accepts any identifier so that semantic
     * analysis can explain a misspelled or not-yet-supported block precisely.
     */
    public record Block(Name keyword, List<Assignment> assignments) implements Decl {
        public Block {
            assignments = List.copyOf(assignments);
        }
    }

    /** {@code key = value} inside a {@link Block}. */
    public record Assignment(Name key, Expr value) {}

    /** {@code enum Name { A B C }}. */
    public record EnumDecl(Name name, List<Name> values) implements Decl {
        public EnumDecl {
            values = List.copyOf(values);
        }
    }

    /** {@code entity Name { fields... @@attributes... }}. */
    public record EntityDecl(Name name, List<FieldDecl> fields, List<AttributeDecl> attributes) implements Decl {
        public EntityDecl {
            fields = List.copyOf(fields);
            attributes = List.copyOf(attributes);
        }
    }

    /** {@code name Type? @attr(...)} — scalar, enum, or relation; the parser cannot tell which. */
    public record FieldDecl(Name name, TypeRef type, List<AttributeDecl> attributes) {
        public FieldDecl {
            attributes = List.copyOf(attributes);
        }
    }

    /** A type reference with its {@code ?} (optional) and {@code []} (list) markers. */
    public record TypeRef(Name name, boolean optional, boolean list, SourcePosition position) {}

    /** {@code @name(args)} on a field, or {@code @@name(args)} on an entity. */
    public record AttributeDecl(Name name, boolean entityLevel, List<Expr> args, SourcePosition position) {
        public AttributeDecl {
            args = List.copyOf(args);
        }

        /** The spelling as written, e.g. {@code @unique} or {@code @@index}. */
        public String display() {
            return (entityLevel ? "@@" : "@") + name.text();
        }
    }

    /** An argument or assignment value. */
    public sealed interface Expr permits StringLit, IntLit, FloatLit, BoolLit, Ident, ListExpr, Call, Named {
        SourcePosition position();
    }

    /** {@code "text"} — {@code value} has quotes removed and escapes resolved. */
    public record StringLit(String value, SourcePosition position) implements Expr {}

    /** {@code 42} / {@code -1}, kept as source text so range checks can report exactly what was written. */
    public record IntLit(String text, SourcePosition position) implements Expr {}

    /** {@code 0.05}, kept as source text (never round-tripped through {@code double}). */
    public record FloatLit(String text, SourcePosition position) implements Expr {}

    /** {@code true} / {@code false}. */
    public record BoolLit(boolean value, SourcePosition position) implements Expr {}

    /** A bare identifier, e.g. an enum constant {@code PENDING} or an action {@code Cascade}. */
    public record Ident(String name, SourcePosition position) implements Expr {}

    /** {@code [a, b]}. */
    public record ListExpr(List<Name> items, SourcePosition position) implements Expr {
        public ListExpr {
            items = List.copyOf(items);
        }
    }

    /** {@code fn(args)}, e.g. {@code now()}, {@code uuid()}, {@code env("DATABASE_URL")}. */
    public record Call(Name function, List<Expr> args, SourcePosition position) implements Expr {
        public Call {
            args = List.copyOf(args);
        }
    }

    /** {@code name: value}, e.g. {@code onDelete: Cascade}. */
    public record Named(Name name, Expr value, SourcePosition position) implements Expr {}
}
