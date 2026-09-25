/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vantix.core.diagnostic.DiagnosticRenderer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The diagnostic corpus: every {@code src/test/resources/diagnostics/*.vx} is compiled and its
 * rendered diagnostics must match the sibling {@code .expected} file exactly — message, position,
 * caret and suggestion. Error text is a headline feature, so a wording change is a deliberate,
 * reviewed diff to these files, never an accident.
 *
 * <p>After an intentional change, regenerate with {@code ./mvnw test -Dvantix.updateGolden=true}
 * and review the diff.
 */
class DiagnosticCorpusTest {

    private static final Path CORPUS = Path.of("src/test/resources/diagnostics");

    static Stream<Path> corpus() throws IOException {
        return Files.list(CORPUS).filter(p -> p.toString().endsWith(".vx")).sorted();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void rendersExactlyTheExpectedDiagnostics(Path schema) throws IOException {
        String source = Files.readString(schema, StandardCharsets.UTF_8);
        SchemaCompiler.Result result = SchemaCompiler.compile(source);
        String actual =
                new DiagnosticRenderer(schema.getFileName().toString(), source, false).render(result.diagnostics());

        Path expectedFile =
                schema.resolveSibling(schema.getFileName().toString().replace(".vx", ".expected"));
        if (Boolean.getBoolean("vantix.updateGolden")) {
            Files.writeString(expectedFile, actual, StandardCharsets.UTF_8);
            return;
        }
        assertThat(expectedFile)
                .as("missing golden file; run with -Dvantix.updateGolden=true")
                .exists();
        String expected = Files.readString(expectedFile, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(actual).isEqualTo(expected);
        // Every corpus entry is a deliberately broken or suspicious schema.
        assertThat(result.diagnostics()).isNotEmpty();
    }
}
