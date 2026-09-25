/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.lexer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vantix.core.model.SourcePosition;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LexerTest {

    private static List<TokenType> types(String src) {
        return new Lexer(src).tokenize().stream().map(Token::type).toList();
    }

    @Test
    void tokenizesAFieldDeclaration() {
        assertThat(types("id Long @id"))
                .containsExactly(
                        TokenType.IDENTIFIER, // id
                        TokenType.IDENTIFIER, // Long
                        TokenType.AT,
                        TokenType.IDENTIFIER, // id
                        TokenType.EOF);
    }

    @Test
    void distinguishesEntityAndFieldAttributes() {
        // @@index vs @id — the two-char '@@' must not be split into two AT tokens.
        assertThat(types("@@index @id"))
                .containsExactly(
                        TokenType.AT_AT, TokenType.IDENTIFIER, TokenType.AT, TokenType.IDENTIFIER, TokenType.EOF);
    }

    @Test
    void lexesOptionalAndListMarkers() {
        assertThat(types("String? Order[]"))
                .containsExactly(
                        TokenType.IDENTIFIER, // String
                        TokenType.QUESTION,
                        TokenType.IDENTIFIER, // Order
                        TokenType.LBRACKET,
                        TokenType.RBRACKET,
                        TokenType.EOF);
    }

    @Test
    void lexesAttributeWithIntArgument() {
        List<Token> tokens = new Lexer("@length(180)").tokenize();
        assertThat(tokens.stream().map(Token::type))
                .containsExactly(
                        TokenType.AT,
                        TokenType.IDENTIFIER,
                        TokenType.LPAREN,
                        TokenType.INT_LITERAL,
                        TokenType.RPAREN,
                        TokenType.EOF);
        assertThat(tokens.get(3).lexeme()).isEqualTo("180");
    }

    @Test
    void lexesStringLiteralsAndExposesTheUnquotedValue() {
        List<Token> tokens = new Lexer("url = \"postgresql\"").tokenize();
        assertThat(tokens.get(0).isKeyword("url")).isTrue();
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.EQUALS);
        Token string = tokens.get(2);
        assertThat(string.type()).isEqualTo(TokenType.STRING_LITERAL);
        assertThat(string.lexeme()).isEqualTo("\"postgresql\"");
        assertThat(string.stringValue()).isEqualTo("postgresql");
    }

    @Test
    void skipsLineAndBlockComments() {
        String src = """
                // a line comment
                entity /* inline */ User
                """;
        assertThat(types(src)).containsExactly(TokenType.IDENTIFIER, TokenType.IDENTIFIER, TokenType.EOF);
    }

    @Test
    void tracksLineAndColumnAcrossNewlines() {
        // 'User' begins on line 2, column 8 (after "entity ").
        List<Token> tokens = new Lexer("entity User").tokenize();
        SourcePosition entity = tokens.get(0).position();
        assertThat(entity.line()).isEqualTo(1);
        assertThat(entity.column()).isEqualTo(1);
        assertThat(entity.offset()).isEqualTo(0);
        assertThat(entity.length()).isEqualTo(6);

        SourcePosition user = tokens.get(1).position();
        assertThat(user.line()).isEqualTo(1);
        assertThat(user.column()).isEqualTo(8);
        assertThat(user.offset()).isEqualTo(7);
        assertThat(user.length()).isEqualTo(4);
    }

    @Test
    void positionsAdvanceOntoTheNextLine() {
        List<Token> tokens = new Lexer("a\n  b").tokenize();
        SourcePosition b = tokens.get(1).position();
        assertThat(b.line()).isEqualTo(2);
        assertThat(b.column()).isEqualTo(3);
    }

    @Test
    void reportsUnexpectedCharacterButKeepsGoing() {
        Lexer lexer = new Lexer("id $ Long");
        List<Token> tokens = lexer.tokenize();

        assertThat(lexer.hasErrors()).isTrue();
        assertThat(lexer.diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.message()).contains("Unexpected character '$'");
            assertThat(d.position().line()).isEqualTo(1);
            assertThat(d.position().column()).isEqualTo(4);
        });
        // Lexing recovers and still produces the surrounding identifiers.
        assertThat(tokens.stream().map(Token::type))
                .containsExactly(TokenType.IDENTIFIER, TokenType.IDENTIFIER, TokenType.EOF);
    }

    @Test
    void reportsUnterminatedString() {
        Lexer lexer = new Lexer("url = \"oops");
        lexer.tokenize();
        assertThat(lexer.hasErrors()).isTrue();
        assertThat(lexer.diagnostics()).anySatisfy(d -> assertThat(d.message()).contains("Unterminated string"));
    }

    @Test
    void reportsUnterminatedBlockComment() {
        Lexer lexer = new Lexer("entity /* oops");
        lexer.tokenize();
        assertThat(lexer.diagnostics()).anySatisfy(d -> assertThat(d.message()).contains("Unterminated block comment"));
    }

    @Test
    void lexesAFloatLiteralAsOneToken() {
        List<Token> tokens = new Lexer("@default(0.05)").tokenize();
        assertThat(tokens.stream().map(Token::type))
                .containsExactly(
                        TokenType.AT,
                        TokenType.IDENTIFIER,
                        TokenType.LPAREN,
                        TokenType.FLOAT_LITERAL,
                        TokenType.RPAREN,
                        TokenType.EOF);
        assertThat(tokens.get(3).lexeme()).isEqualTo("0.05");
    }

    @Test
    void lexesNegativeAndZeroNumbers() {
        assertThat(types("-2.5 -3 0 0.05"))
                .containsExactly(
                        TokenType.FLOAT_LITERAL,
                        TokenType.INT_LITERAL,
                        TokenType.INT_LITERAL,
                        TokenType.FLOAT_LITERAL,
                        TokenType.EOF);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "1.       | A decimal needs digits after the point: `1.`       | 1      | INT_LITERAL",
                ".5       | A decimal needs a digit before the point: `.5`     | .5     | FLOAT_LITERAL",
                "1e5      | Exponent notation is not supported: `1e5`          | 1      | INT_LITERAL",
                "2.5E-3   | Exponent notation is not supported: `2.5E-3`       | 2.5    | FLOAT_LITERAL",
                "10L      | Invalid number `10L`                               | 10     | INT_LITERAL",
                "010      | Numbers cannot have leading zeros: `010`           | 010    | INT_LITERAL",
                "-007.25  | Numbers cannot have leading zeros: `-007.25`       | -007.25 | FLOAT_LITERAL",
            })
    void rejectsMalformedNumbersButKeepsATokenSoParsingContinues(
            String source, String message, String lexeme, TokenType type) {
        Lexer lexer = new Lexer(source);
        List<Token> tokens = lexer.tokenize();

        assertThat(lexer.diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.message()).isEqualTo(message);
            assertThat(d.position().length()).isEqualTo(source.length()); // the whole malformed number
        });
        assertThat(tokens.getFirst().type()).isEqualTo(type);
        assertThat(tokens.getFirst().lexeme()).isEqualTo(lexeme);
        assertThat(tokens).hasSize(2); // the number and EOF: nothing left over to derail the parser
    }

    @Test
    void escapedQuotesDoNotEndAString() {
        Lexer lexer = new Lexer("@default(\"say \\\"hi\\\"\")");
        List<Token> tokens = lexer.tokenize();
        assertThat(lexer.diagnostics()).isEmpty();
        Token string = tokens.get(3);
        assertThat(string.type()).isEqualTo(TokenType.STRING_LITERAL);
        assertThat(string.stringValue()).isEqualTo("say \"hi\"");
        assertThat(tokens.get(4).type()).isEqualTo(TokenType.RPAREN);
    }

    @Test
    void resolvesBackslashNewlineAndTabEscapes() {
        // source text: "a\\b\nc\td"
        Token string = new Lexer("\"a\\\\b\\nc\\td\"").tokenize().getFirst();
        assertThat(string.stringValue()).isEqualTo("a\\b\nc\td");
    }

    @Test
    void reportsAnUnknownEscapeAtTheBackslash() {
        Lexer lexer = new Lexer("\"a\\qb\""); // source text: "a\qb"
        List<Token> tokens = lexer.tokenize();
        assertThat(lexer.diagnostics()).singleElement().satisfies(d -> {
            assertThat(d.message()).isEqualTo("Unknown escape sequence '\\q'");
            assertThat(d.position().column()).isEqualTo(3);
            assertThat(d.position().length()).isEqualTo(2);
        });
        // The string itself still lexes, so the parser is not derailed.
        assertThat(tokens.getFirst().type()).isEqualTo(TokenType.STRING_LITERAL);
    }
}
