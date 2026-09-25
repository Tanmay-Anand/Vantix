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

    /** Characters that may follow a backslash inside a string literal. */
    static final String ESCAPES = "\"\\nt";

    public boolean is(TokenType candidate) {
        return type == candidate;
    }

    /** True when this token is an identifier spelled exactly {@code keyword} (contextual keywords). */
    public boolean isKeyword(String keyword) {
        return type == TokenType.IDENTIFIER && lexeme.equals(keyword);
    }

    /**
     * For a {@link TokenType#STRING_LITERAL}, the value with surrounding quotes removed and the
     * escapes {@code \"}, {@code \\}, {@code \n}, {@code \t} resolved.
     */
    public String stringValue() {
        if (type != TokenType.STRING_LITERAL) {
            throw new IllegalStateException("Not a string literal: " + this);
        }
        String body = lexeme.substring(1, lexeme.length() - 1);
        if (body.indexOf('\\') < 0) {
            return body;
        }
        StringBuilder out = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char next = body.charAt(++i);
                out.append(
                        switch (next) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            default -> next; // \" and \\ (unknown escapes were already reported)
                        });
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
