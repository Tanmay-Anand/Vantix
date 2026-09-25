/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vantix.core.SchemaCompiler;
import dev.vantix.core.model.Schema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Golden-file cases live in {@code src/test/resources/golden/<case>/}: a {@code schema.vx} input and an
 * {@code expected/} tree — {@code expected/generated/} for regenerated sources and
 * {@code expected/scaffold/} for Generation Gap scaffolds.
 */
final class GoldenFiles {

    static final Path ROOT = Path.of("src/test/resources/golden");

    private GoldenFiles() {}

    static Stream<String> cases() throws IOException {
        return Files.list(ROOT)
                .filter(Files::isDirectory)
                .map(p -> p.getFileName().toString())
                .sorted();
    }

    static Schema schema(String name) {
        try {
            String source = Files.readString(ROOT.resolve(name).resolve("schema.vx"), StandardCharsets.UTF_8);
            SchemaCompiler.Result result = SchemaCompiler.compile(source);
            assertThat(result.hasErrors())
                    .as("golden schema %s must compile without errors: %s", name, result.diagnostics())
                    .isFalse();
            return result.schema();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<GeneratedFile> generate(String name) {
        return CodeGenerator.generate(schema(name));
    }

    /** Where a generated file's expected content lives within its case. */
    static Path expectedPath(String name, GeneratedFile file) {
        return ROOT.resolve(name)
                .resolve("expected")
                .resolve(file.scaffold() ? "scaffold" : "generated")
                .resolve(file.relativePath());
    }
}
