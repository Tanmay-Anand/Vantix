/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core;

import dev.vantix.core.diagnostic.Diagnostic;
import dev.vantix.core.model.Schema;
import dev.vantix.core.parser.Parser;
import dev.vantix.core.semantic.SchemaAnalyzer;
import java.util.ArrayList;
import java.util.List;

/**
 * The front end in one call: {@code schema.vx} source text → resolved {@link Schema} plus every
 * diagnostic (lexical, syntactic, semantic) in source order.
 *
 * <p>Semantic analysis runs even when parsing reported errors, so a single run surfaces both a
 * missing brace and an unknown type. The model is only returned when there are no errors at all.
 */
public final class SchemaCompiler {

    private SchemaCompiler() {}

    /** The compilation outcome. {@link #schema()} is {@code null} whenever {@link #hasErrors()}. */
    public record Result(Schema schema, List<Diagnostic> diagnostics) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(Diagnostic::isError);
        }

        public long errorCount() {
            return diagnostics.stream().filter(Diagnostic::isError).count();
        }

        public long warningCount() {
            return diagnostics.size() - errorCount();
        }
    }

    public static Result compile(String source) {
        Parser.Result parsed = Parser.parse(source);
        SchemaAnalyzer.Result analyzed = SchemaAnalyzer.analyze(parsed.file());
        List<Diagnostic> all = new ArrayList<>(parsed.diagnostics());
        all.addAll(analyzed.diagnostics());
        all.sort((a, b) -> Integer.compare(a.position().offset(), b.position().offset()));
        boolean failed = all.stream().anyMatch(Diagnostic::isError);
        return new Result(failed ? null : analyzed.schema(), all);
    }
}
