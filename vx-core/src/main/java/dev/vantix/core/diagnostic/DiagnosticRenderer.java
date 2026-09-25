/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.diagnostic;

import dev.vantix.core.model.SourcePosition;
import java.util.List;
import java.util.Locale;

/**
 * Renders {@link Diagnostic}s for a terminal, compiler-style: the message, a {@code file:line:col}
 * pointer, the offending source line, and a caret underline carrying the suggestion.
 *
 * <pre>
 * error: Unknown type `Strin`
 *   --&gt; vantix/schema.vx:3:9
 *    |
 *  3 |   email Strin @unique
 *    |         ^^^^^ did you mean `String`?
 * </pre>
 *
 * Output uses {@code \n} line endings regardless of platform so diagnostic-corpus tests compare
 * byte-for-byte. Colour (ANSI) is opt-in.
 */
public final class DiagnosticRenderer {

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";

    private final String fileName;
    private final String[] lines;
    private final boolean color;

    public DiagnosticRenderer(String fileName, String source, boolean color) {
        this.fileName = fileName;
        this.lines = source.replace("\r\n", "\n").split("\n", -1);
        this.color = color;
    }

    /** Renders every diagnostic, separated by blank lines. */
    public String render(List<Diagnostic> diagnostics) {
        StringBuilder out = new StringBuilder();
        for (Diagnostic d : diagnostics) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(render(d));
        }
        return out.toString();
    }

    public String render(Diagnostic d) {
        boolean error = d.isError();
        String label = error ? "error" : "warning";
        String tone = error ? RED : YELLOW;
        StringBuilder out = new StringBuilder();
        out.append(paint(BOLD + tone, label))
                .append(paint(BOLD, ": " + d.message()))
                .append('\n');

        SourcePosition pos = d.position();
        if (pos == null || pos.line() < 1 || pos.line() > lines.length) {
            out.append("  --> ").append(fileName).append('\n');
            if (d.suggestion() != null) {
                out.append("   = ")
                        .append(paint(tone, "help: " + d.suggestion()))
                        .append('\n');
            }
            return out.toString();
        }

        List<Diagnostic.Label> related = d.related().stream()
                .filter(l -> l.position() != null
                        && l.position().line() >= 1
                        && l.position().line() <= lines.length)
                .toList();
        int gutter = Integer.toString(Math.max(
                        pos.line(),
                        related.stream()
                                .mapToInt(l -> l.position().line())
                                .max()
                                .orElse(0)))
                .length();
        String pad = " ".repeat(gutter);

        out.append(pad)
                .append(paint(BLUE, "--> "))
                .append(fileName)
                .append(':')
                .append(pos.line())
                .append(':')
                .append(pos.column())
                .append('\n');
        out.append(pad).append(paint(BLUE, " |")).append('\n');
        snippet(out, pos, gutter, '^', tone, d.suggestion());
        // Secondary locations, rustc-style: the same gutter, a `-` underline and a short note.
        for (Diagnostic.Label secondary : related) {
            out.append(pad).append(paint(BLUE, " |")).append('\n');
            snippet(out, secondary.position(), gutter, '-', BLUE, secondary.note());
        }
        return out.toString();
    }

    private void snippet(StringBuilder out, SourcePosition pos, int gutter, char mark, String tone, String note) {
        String sourceLine = lines[pos.line() - 1].replace('\t', ' ');
        int col = Math.max(1, Math.min(pos.column(), sourceLine.length() + 1));
        int width = Math.max(1, Math.min(pos.length(), sourceLine.length() - col + 1));
        String lineNo = String.format(Locale.ROOT, "%" + gutter + "d", pos.line());
        out.append(paint(BLUE, lineNo + " | "))
                .append(sourceLine.stripTrailing())
                .append('\n');
        out.append(" ".repeat(gutter))
                .append(paint(BLUE, " | "))
                .append(" ".repeat(col - 1))
                .append(paint(tone, String.valueOf(mark).repeat(width)));
        if (note != null) {
            out.append(' ').append(paint(tone, note));
        }
        out.append('\n');
    }

    private String paint(String code, String text) {
        return color ? code + text + RESET : text;
    }
}
