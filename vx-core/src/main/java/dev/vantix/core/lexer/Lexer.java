/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.lexer;

import dev.vantix.core.diagnostic.Diagnostic;
import dev.vantix.core.diagnostic.Severity;
import dev.vantix.core.model.SourcePosition;
import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written lexer for {@code schema.vx}. Produces a {@link Token} stream (always ending in
 * {@link TokenType#EOF}) with a precise {@link SourcePosition} on every token, and collects lexical
 * {@link Diagnostic}s (unterminated string/comment, unexpected character) rather than throwing — so
 * the parser can still run and report as much as possible in one pass.
 *
 * <p>The lexer is single-use: construct one per source and call {@link #tokenize()} once.
 */
public final class Lexer {

    private final String source;
    private final List<Token> tokens = new ArrayList<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    private int current = 0; // offset of the next char to read
    private int line = 1; // 1-based line of the next char
    private int column = 1; // 1-based column of the next char

    private int tokenStart; // offset where the current token began
    private int tokenLine; // line where the current token began
    private int tokenColumn; // column where the current token began

    public Lexer(String source) {
        this.source = source;
    }

    public List<Token> tokenize() {
        while (true) {
            skipWhitespaceAndComments();
            if (isAtEnd()) {
                break;
            }
            tokenStart = current;
            tokenLine = line;
            tokenColumn = column;
            scanToken();
        }
        tokens.add(new Token(TokenType.EOF, "", new SourcePosition(line, column, current, 0)));
        return tokens;
    }

    public List<Diagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(Diagnostic::isError);
    }

    private void scanToken() {
        char c = advance();
        switch (c) {
            case '{' -> add(TokenType.LBRACE);
            case '}' -> add(TokenType.RBRACE);
            case '(' -> add(TokenType.LPAREN);
            case ')' -> add(TokenType.RPAREN);
            case '[' -> add(TokenType.LBRACKET);
            case ']' -> add(TokenType.RBRACKET);
            case ',' -> add(TokenType.COMMA);
            case ':' -> add(TokenType.COLON);
            case '=' -> add(TokenType.EQUALS);
            case '?' -> add(TokenType.QUESTION);
            case '.' -> add(TokenType.DOT);
            case '@' -> {
                if (peek() == '@') {
                    advance();
                    add(TokenType.AT_AT);
                } else {
                    add(TokenType.AT);
                }
            }
            case '"' -> string();
            default -> {
                if (isDigit(c) || (c == '-' && isDigit(peek()))) {
                    number();
                } else if (isAlpha(c)) {
                    identifier();
                } else {
                    error("Unexpected character '" + c + "'");
                }
            }
        }
    }

    private void identifier() {
        while (isAlphaNumeric(peek())) {
            advance();
        }
        add(TokenType.IDENTIFIER);
    }

    private void number() {
        while (isDigit(peek())) {
            advance();
        }
        add(TokenType.INT_LITERAL);
    }

    private void string() {
        while (!isAtEnd() && peek() != '"' && peek() != '\n') {
            advance();
        }
        if (isAtEnd() || peek() == '\n') {
            error("Unterminated string literal");
            return;
        }
        advance(); // closing quote
        add(TokenType.STRING_LITERAL);
    }

    private void skipWhitespaceAndComments() {
        while (!isAtEnd()) {
            char c = peek();
            switch (c) {
                case ' ', '\t', '\r', '\n' -> advance();
                case '/' -> {
                    if (peekNext() == '/') {
                        while (!isAtEnd() && peek() != '\n') {
                            advance();
                        }
                    } else if (peekNext() == '*') {
                        blockComment();
                    } else {
                        return; // a lone '/' is not valid, but let scanToken report it
                    }
                }
                default -> {
                    return;
                }
            }
        }
    }

    private void blockComment() {
        int startLine = line;
        int startColumn = column;
        int startOffset = current;
        advance(); // '/'
        advance(); // '*'
        while (!isAtEnd()) {
            if (peek() == '*' && peekNext() == '/') {
                advance();
                advance();
                return;
            }
            advance();
        }
        diagnostics.add(Diagnostic.error(
                "Unterminated block comment",
                new SourcePosition(startLine, startColumn, startOffset, current - startOffset)));
    }

    private void add(TokenType type) {
        String lexeme = source.substring(tokenStart, current);
        tokens.add(
                new Token(type, lexeme, new SourcePosition(tokenLine, tokenColumn, tokenStart, current - tokenStart)));
    }

    private void error(String message) {
        diagnostics.add(new Diagnostic(
                Severity.ERROR,
                message,
                new SourcePosition(tokenLine, tokenColumn, tokenStart, Math.max(1, current - tokenStart)),
                null));
    }

    private char advance() {
        char c = source.charAt(current++);
        if (c == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        return c;
    }

    private char peek() {
        return isAtEnd() ? '\0' : source.charAt(current);
    }

    private char peekNext() {
        return current + 1 >= source.length() ? '\0' : source.charAt(current + 1);
    }

    private boolean isAtEnd() {
        return current >= source.length();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isAlphaNumeric(char c) {
        return isAlpha(c) || isDigit(c);
    }
}
