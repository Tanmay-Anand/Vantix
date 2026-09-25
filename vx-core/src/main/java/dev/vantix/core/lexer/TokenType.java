/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.lexer;

/**
 * The lexical token kinds of {@code schema.vx}.
 *
 * <p>Keywords ({@code entity}, {@code enum}, {@code datasource}, {@code generator}, {@code env},
 * {@code true}, {@code false}) are <em>contextual</em> — they lex as {@link #IDENTIFIER} and the
 * parser interprets them by text. This keeps the lexer tiny and lets those words be used as field
 * names where unambiguous.
 */
public enum TokenType {
    IDENTIFIER,
    INT_LITERAL,
    FLOAT_LITERAL,
    STRING_LITERAL,

    LBRACE, // {
    RBRACE, // }
    LPAREN, // (
    RPAREN, // )
    LBRACKET, // [
    RBRACKET, // ]
    COMMA, // ,
    COLON, // :
    EQUALS, // =
    QUESTION, // ?
    AT, // @
    AT_AT, // @@
    DOT, // .

    EOF
}
