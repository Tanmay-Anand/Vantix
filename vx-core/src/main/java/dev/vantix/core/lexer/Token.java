/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.lexer;

import dev.vantix.core.model.SourcePosition;

/**
 * A lexical token: its {@link TokenType}, the exact source {@code lexeme} (including quotes for
 * strings), and its {@link SourcePosition}.
 */
public record Token(TokenType type, String lexeme, SourcePosition position) {

    public boolean is(TokenType candidate) {
        return type == candidate;
    }

    /** True when this token is an identifier spelled exactly {@code keyword} (contextual keywords). */
    public boolean isKeyword(String keyword) {
        return type == TokenType.IDENTIFIER && lexeme.equals(keyword);
    }

    /** For a {@link TokenType#STRING_LITERAL}, the value with surrounding quotes removed. */
    public String stringValue() {
        if (type != TokenType.STRING_LITERAL) {
            throw new IllegalStateException("Not a string literal: " + this);
        }
        return lexeme.substring(1, lexeme.length() - 1);
    }
}
