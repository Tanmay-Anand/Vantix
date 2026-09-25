/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.parser;

import dev.vantix.core.ast.Ast;
import dev.vantix.core.diagnostic.Diagnostic;
import dev.vantix.core.diagnostic.Suggestions;
import dev.vantix.core.lexer.Lexer;
import dev.vantix.core.lexer.Token;
import dev.vantix.core.lexer.TokenType;
import dev.vantix.core.model.SourcePosition;
import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for {@code schema.vx} (grammar: {@code docs/grammar.md}).
 *
 * <p>The parser never stops at the first problem. A syntax error inside an entity abandons only the
 * current line and parsing resumes at the next member; an error at the top level skips ahead to the
 * next {@code entity}/{@code enum}/{@code datasource}/{@code generator} block. Everything found is
 * reported in one pass, so fixing a schema is one edit-run cycle, not one per mistake.
 */
public final class Parser {

    private static final List<String> TOP_LEVEL = List.of("entity", "enum", "datasource", "generator");

    private final List<Token> tokens;
    private final List<Diagnostic> lexical;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private int current = 0;

    private Parser(List<Token> tokens, List<Diagnostic> lexical) {
        this.tokens = tokens;
        this.lexical = lexical;
    }

    /** The syntax tree plus every lexical and syntax diagnostic, in source order. */
    public record Result(Ast.SchemaFile file, List<Diagnostic> diagnostics) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(Diagnostic::isError);
        }
    }

    public static Result parse(String source) {
        Lexer lexer = new Lexer(source);
        List<Token> tokens = lexer.tokenize();
        Parser parser = new Parser(tokens, lexer.diagnostics());
        Ast.SchemaFile file = parser.schema();
        List<Diagnostic> all = new ArrayList<>(lexer.diagnostics());
        all.addAll(parser.diagnostics);
        all.sort((a, b) -> Integer.compare(a.position().offset(), b.position().offset()));
        return new Result(file, all);
    }

    /** Thrown after a diagnostic is recorded, to unwind to the nearest recovery point. */
    private static final class SyntaxError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SyntaxError() {
            super(null, null, false, false);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Top level
    // ---------------------------------------------------------------------------------------------

    private Ast.SchemaFile schema() {
        List<Ast.Decl> decls = new ArrayList<>();
        while (!check(TokenType.EOF)) {
            try {
                decls.add(topLevel());
            } catch (SyntaxError e) {
                synchronizeTopLevel();
            }
        }
        return new Ast.SchemaFile(decls);
    }

    private Ast.Decl topLevel() {
        Token t = peek();
        if (t.isKeyword("entity")) {
            return entity();
        }
        if (t.isKeyword("enum")) {
            return enumDecl();
        }
        if (t.is(TokenType.IDENTIFIER) && checkAt(1, TokenType.LBRACE)) {
            return block(); // datasource / generator — others are explained by semantic analysis
        }
        if (t.is(TokenType.IDENTIFIER)) {
            String suggestion = Suggestions.didYouMean(t.lexeme(), TOP_LEVEL);
            throw error(t, "Expected `entity`, `enum`, `datasource` or `generator`, found " + describe(t), suggestion);
        }
        throw error(t, "Expected `entity`, `enum`, `datasource` or `generator`, found " + describe(t), null);
    }

    /** Skip to the next token that plausibly starts a top-level declaration. */
    private void synchronizeTopLevel() {
        advance();
        while (!check(TokenType.EOF) && !startsTopLevel()) {
            advance();
        }
    }

    private boolean startsTopLevel() {
        Token t = peek();
        if (t.isKeyword("entity") || t.isKeyword("enum")) {
            return checkAt(1, TokenType.IDENTIFIER) && checkAt(2, TokenType.LBRACE);
        }
        return (t.isKeyword("datasource") || t.isKeyword("generator")) && checkAt(1, TokenType.LBRACE);
    }

    // ---------------------------------------------------------------------------------------------
    // Blocks
    // ---------------------------------------------------------------------------------------------

    private Ast.Block block() {
        Ast.Name keyword = name(advance());
        expect(TokenType.LBRACE, "Expected `{` after `" + keyword.text() + "`");
        List<Ast.Assignment> assignments = new ArrayList<>();
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            if (startsTopLevel()) {
                break; // missing '}' — reported below
            }
            int memberLine = peek().position().line();
            try {
                assignments.add(assignment());
            } catch (SyntaxError e) {
                synchronizeMember(memberLine);
            }
        }
        closeBrace("`" + keyword.text() + "` block");
        return new Ast.Block(keyword, assignments);
    }

    private Ast.Assignment assignment() {
        Token key = peek();
        if (!key.is(TokenType.IDENTIFIER)) {
            throw error(key, "Expected a setting name, found " + describe(key), null);
        }
        advance();
        if (!check(TokenType.EQUALS)) {
            throw error(peek(), "Expected `=` after `" + key.lexeme() + "`, found " + describe(peek()), null);
        }
        advance();
        return new Ast.Assignment(name(key), expr());
    }

    // ---------------------------------------------------------------------------------------------
    // Enums
    // ---------------------------------------------------------------------------------------------

    private Ast.EnumDecl enumDecl() {
        advance(); // enum
        Ast.Name name = name(expectIdentifier("Expected an enum name after `enum`"));
        expect(TokenType.LBRACE, "Expected `{` after enum name `" + name.text() + "`");
        List<Ast.Name> values = new ArrayList<>();
        boolean commaReported = false;
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            if (startsTopLevel()) {
                break;
            }
            Token t = peek();
            if (t.is(TokenType.IDENTIFIER)) {
                values.add(name(advance()));
            } else if (t.is(TokenType.COMMA)) {
                if (!commaReported) {
                    diagnostics.add(Diagnostic.error(
                            "Enum values are separated by spaces or newlines, not commas",
                            t.position(),
                            "remove the `,`"));
                    commaReported = true;
                }
                advance();
            } else {
                diagnostics.add(Diagnostic.error(
                        "Expected an enum value in `" + name.text() + "`, found " + describe(t), t.position()));
                advance();
            }
        }
        closeBrace("enum `" + name.text() + "`");
        return new Ast.EnumDecl(name, values);
    }

    // ---------------------------------------------------------------------------------------------
    // Entities
    // ---------------------------------------------------------------------------------------------

    private Ast.EntityDecl entity() {
        advance(); // entity
        Ast.Name name = name(expectIdentifier("Expected an entity name after `entity`"));
        expect(TokenType.LBRACE, "Expected `{` after entity name `" + name.text() + "`");
        List<Ast.FieldDecl> fields = new ArrayList<>();
        List<Ast.AttributeDecl> attributes = new ArrayList<>();
        while (!check(TokenType.RBRACE) && !check(TokenType.EOF)) {
            if (startsTopLevel()) {
                break; // missing '}' — reported by closeBrace
            }
            int memberLine = peek().position().line();
            try {
                if (check(TokenType.AT_AT)) {
                    attributes.add(attribute(true));
                } else if (check(TokenType.IDENTIFIER)) {
                    fields.add(field());
                } else if (check(TokenType.AT)) {
                    Token at = peek();
                    String attr = checkAt(1, TokenType.IDENTIFIER)
                            ? tokens.get(current + 1).lexeme()
                            : "";
                    if (attr.equals("index") || attr.equals("table")) {
                        throw error(
                                at,
                                "`@" + attr + "` is an entity attribute and needs `@@`",
                                "write `@@" + attr + "(...)`");
                    }
                    throw error(
                            at,
                            "Attribute " + describeAttribute(at) + " must be on the same line as its field",
                            "move it to the end of the field's line");
                } else {
                    throw error(peek(), "Expected a field or an `@@` attribute, found " + describe(peek()), null);
                }
            } catch (SyntaxError e) {
                synchronizeMember(memberLine);
            }
        }
        closeBrace("entity `" + name.text() + "`");
        return new Ast.EntityDecl(name, fields, attributes);
    }

    private Ast.FieldDecl field() {
        Token nameToken = advance();
        Token typeToken = peek();
        if (!typeToken.is(TokenType.IDENTIFIER)) {
            throw error(
                    typeToken,
                    "Expected a type after field `" + nameToken.lexeme() + "`, found " + describe(typeToken),
                    null);
        }
        advance();
        boolean optional = false;
        boolean list = false;
        if (check(TokenType.LBRACKET)) {
            advance();
            if (!check(TokenType.RBRACKET)) {
                throw error(peek(), "Expected `]` to close the list marker `[]`", null);
            }
            advance();
            list = true;
            if (check(TokenType.QUESTION)) {
                optional = true;
                advance();
            }
        } else if (check(TokenType.QUESTION)) {
            optional = true;
            advance();
            if (check(TokenType.LBRACKET) && checkAt(1, TokenType.RBRACKET)) {
                list = true;
                advance();
                advance();
            }
        }
        SourcePosition typePos = span(typeToken.position(), previous().position());
        Ast.TypeRef type = new Ast.TypeRef(name(typeToken), optional, list, typePos);
        List<Ast.AttributeDecl> attributes = new ArrayList<>();
        // Field attributes end at the end of the line, as in Prisma: a stray `@x` on the next line
        // is reported rather than silently attached to the field above it.
        while (check(TokenType.AT)
                && peek().position().line() == nameToken.position().line()) {
            attributes.add(attribute(false));
        }
        if (check(TokenType.AT_AT)
                && peek().position().line() == nameToken.position().line()) {
            throw error(
                    peek(),
                    "Entity attribute " + describeAttribute(peek()) + " cannot be attached to a field",
                    "put it on its own line, or use a single `@` for a field attribute");
        }
        return new Ast.FieldDecl(name(nameToken), type, attributes);
    }

    /**
     * {@code @name(args)} / {@code @@name(args)}. Syntax the grammar reserves but v1 rejects
     * ({@code @@id}, {@code @@schema}) is reported here and still returned, so semantic analysis
     * knows it was written and does not pile on follow-up errors.
     */
    private Ast.AttributeDecl attribute(boolean entityLevel) {
        Token marker = advance();
        Token nameToken = peek();
        if (!nameToken.is(TokenType.IDENTIFIER)) {
            throw error(
                    nameToken,
                    "Expected an attribute name after `" + marker.lexeme() + "`, found " + describe(nameToken),
                    null);
        }
        advance();
        List<Ast.Expr> args = new ArrayList<>();
        if (check(TokenType.LPAREN)) {
            args = arguments();
        }
        SourcePosition pos = span(marker.position(), previous().position());
        if (entityLevel && nameToken.lexeme().equals("schema")) {
            diagnostics.add(Diagnostic.error(
                    "`@@schema` is not supported yet",
                    pos,
                    "entities live in the datasource's default schema; multi-schema placement is planned"));
        }
        if (entityLevel && nameToken.lexeme().equals("id")) {
            diagnostics.add(Diagnostic.error(
                    "Composite primary keys (`@@id`) are not supported yet",
                    pos,
                    "mark a single field with `@id`; composite keys are planned for Phase 2.5"));
        }
        return new Ast.AttributeDecl(name(nameToken), entityLevel, args, pos);
    }

    private List<Ast.Expr> arguments() {
        Token open = advance(); // (
        List<Ast.Expr> args = new ArrayList<>();
        if (check(TokenType.RPAREN)) {
            advance();
            return args;
        }
        args.add(expr());
        while (check(TokenType.COMMA)) {
            advance();
            args.add(expr());
        }
        if (!check(TokenType.RPAREN)) {
            throw error(
                    peek(),
                    "Expected `,` or `)` in argument list, found " + describe(peek()),
                    peek().position().line() != open.position().line() ? "missing `)`?" : null);
        }
        advance();
        return args;
    }

    // ---------------------------------------------------------------------------------------------
    // Expressions
    // ---------------------------------------------------------------------------------------------

    private Ast.Expr expr() {
        Token t = peek();
        switch (t.type()) {
            case STRING_LITERAL -> {
                advance();
                return new Ast.StringLit(t.stringValue(), t.position());
            }
            case INT_LITERAL -> {
                advance();
                return new Ast.IntLit(t.lexeme(), t.position());
            }
            case FLOAT_LITERAL -> {
                advance();
                return new Ast.FloatLit(t.lexeme(), t.position());
            }
            case LBRACKET -> {
                return listExpr();
            }
            case IDENTIFIER -> {
                advance();
                if (check(TokenType.COLON)) {
                    advance();
                    Ast.Expr value = expr();
                    return new Ast.Named(name(t), value, span(t.position(), value.position()));
                }
                if (check(TokenType.LPAREN)) {
                    List<Ast.Expr> args = arguments();
                    return new Ast.Call(
                            name(t), args, span(t.position(), previous().position()));
                }
                if (t.lexeme().equals("true") || t.lexeme().equals("false")) {
                    return new Ast.BoolLit(t.lexeme().equals("true"), t.position());
                }
                return new Ast.Ident(t.lexeme(), t.position());
            }
            default -> throw error(t, "Expected a value, found " + describe(t), null);
        }
    }

    private Ast.ListExpr listExpr() {
        Token open = advance(); // [
        List<Ast.Name> items = new ArrayList<>();
        if (!check(TokenType.RBRACKET)) {
            items.add(name(expectIdentifier("Expected a field name in `[...]`")));
            while (check(TokenType.COMMA)) {
                advance();
                items.add(name(expectIdentifier("Expected a field name after `,`")));
            }
        }
        if (!check(TokenType.RBRACKET)) {
            throw error(peek(), "Expected `,` or `]` in list, found " + describe(peek()), null);
        }
        advance();
        return new Ast.ListExpr(items, span(open.position(), previous().position()));
    }

    // ---------------------------------------------------------------------------------------------
    // Recovery + helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Inside a block/entity body: drop the rest of the member's line so the next member parses
     * normally. When the offending token is already on a later line (e.g. a missing {@code )}), that
     * token starts the next member and nothing more is skipped. Stops early at {@code }} or at the
     * start of a new top-level declaration.
     */
    private void synchronizeMember(int memberLine) {
        while (!check(TokenType.EOF)
                && !check(TokenType.RBRACE)
                && peek().position().line() == memberLine
                && !startsTopLevel()) {
            advance();
        }
    }

    private void closeBrace(String what) {
        if (check(TokenType.RBRACE)) {
            advance();
            return;
        }
        Token t = peek();
        diagnostics.add(Diagnostic.error(
                "Missing `}` to close " + what,
                t.position(),
                t.is(TokenType.EOF) ? "add `}` at the end of the declaration" : "add `}` before this line"));
    }

    private Token expectIdentifier(String message) {
        if (!check(TokenType.IDENTIFIER)) {
            throw error(peek(), message + ", found " + describe(peek()), null);
        }
        return advance();
    }

    private void expect(TokenType type, String message) {
        if (!check(type)) {
            throw error(peek(), message + ", found " + describe(peek()), null);
        }
        advance();
    }

    private SyntaxError error(Token at, String message, String suggestion) {
        if (!followsLexicalError(at)) {
            diagnostics.add(Diagnostic.error(message, at.position(), suggestion));
        }
        return new SyntaxError();
    }

    /**
     * Whether a lexical error sits between the last consumed token and {@code at}. If so, the syntax
     * error is a consequence (the lexer dropped a broken string or character) and reporting it would
     * only bury the real problem.
     */
    private boolean followsLexicalError(Token at) {
        int from = previous().position().offset();
        int to = at.position().offset();
        return lexical.stream()
                .filter(Diagnostic::isError)
                .mapToInt(d -> d.position().offset())
                .anyMatch(offset -> offset >= from && offset <= to);
    }

    private static String describe(Token t) {
        return switch (t.type()) {
            case EOF -> "end of file";
            case STRING_LITERAL -> "string " + t.lexeme();
            default -> "`" + t.lexeme() + "`";
        };
    }

    private String describeAttribute(Token marker) {
        Token next = checkAt(1, TokenType.IDENTIFIER) ? tokens.get(current + 1) : null;
        return "`" + marker.lexeme() + (next != null ? next.lexeme() : "") + "`";
    }

    private static Ast.Name name(Token t) {
        return new Ast.Name(t.lexeme(), t.position());
    }

    private static SourcePosition span(SourcePosition from, SourcePosition to) {
        if (to.offset() < from.offset() || to.line() != from.line()) {
            return from;
        }
        return new SourcePosition(from.line(), from.column(), from.offset(), to.offset() + to.length() - from.offset());
    }

    private boolean check(TokenType type) {
        return peek().is(type);
    }

    private boolean checkAt(int ahead, TokenType type) {
        int i = current + ahead;
        return i < tokens.size() && tokens.get(i).is(type);
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token previous() {
        return tokens.get(Math.max(0, current - 1));
    }

    private Token advance() {
        Token t = tokens.get(current);
        if (!t.is(TokenType.EOF)) {
            current++;
        }
        return t;
    }
}
