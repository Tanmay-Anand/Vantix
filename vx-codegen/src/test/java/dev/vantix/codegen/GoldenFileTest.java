/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Snapshot tests: the generated sources for each golden schema must match the committed expectation
 * byte for byte, and no expected file may go missing. Regenerate after an intentional change with
 * {@code ./mvnw test -Dvantix.updateGolden=true}, then review the diff like any other code change.
 */
class GoldenFileTest {

    static Stream<String> cases() throws IOException {
        return GoldenFiles.cases();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void generatedSourcesMatchTheGoldenFiles(String name) throws IOException {
        List<GeneratedFile> files = GoldenFiles.generate(name);
        Path expectedRoot = GoldenFiles.ROOT.resolve(name).resolve("expected");

        if (Boolean.getBoolean("vantix.updateGolden")) {
            if (Files.exists(expectedRoot)) {
                try (Stream<Path> old = Files.walk(expectedRoot)) {
                    for (Path p : old.filter(Files::isRegularFile).toList()) {
                        Files.delete(p);
                    }
                }
            }
            for (GeneratedFile file : files) {
                Path target = GoldenFiles.expectedPath(name, file);
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.content(), StandardCharsets.UTF_8);
            }
            return;
        }

        for (GeneratedFile file : files) {
            Path expected = GoldenFiles.expectedPath(name, file);
            assertThat(expected)
                    .as("no golden file for %s", file.relativePath())
                    .exists();
            String content = Files.readString(expected, StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertThat(file.content()).as(file.relativePath().toString()).isEqualTo(content);
        }
        Set<Path> produced = files.stream()
                .map(f -> GoldenFiles.expectedPath(name, f).normalize())
                .collect(Collectors.toSet());
        try (Stream<Path> walk = Files.walk(expectedRoot)) {
            assertThat(walk.filter(Files::isRegularFile).map(Path::normalize))
                    .as("golden files the generator no longer produces")
                    .allMatch(produced::contains);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void generationIsDeterministic(String name) {
        assertThat(GoldenFiles.generate(name)).isEqualTo(GoldenFiles.generate(name));
    }

    /** Turkish is the classic trap: {@code "id".toUpperCase()} is {@code "İD"} there. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void generationDoesNotDependOnTheDefaultLocale(String name) {
        List<GeneratedFile> expected = GoldenFiles.generate(name);
        Locale previous = Locale.getDefault();
        try {
            for (String tag : List.of("tr-TR", "ar-SA", "hi-IN")) {
                Locale.setDefault(Locale.forLanguageTag(tag));
                assertThat(GoldenFiles.generate(name)).as(tag).isEqualTo(expected);
            }
        } finally {
            Locale.setDefault(previous);
        }
    }
}
