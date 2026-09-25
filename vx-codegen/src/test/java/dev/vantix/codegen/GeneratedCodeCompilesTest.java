/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Feeds every golden case's generated sources (including scaffolds) to the Java Compiler API against
 * the real JPA, Hibernate and Spring Data jars, and requires zero errors and zero warnings — a
 * deprecation or unchecked warning in generated code lands in every user's build log.
 */
class GeneratedCodeCompilesTest {

    /** One class from each jar generated code (or its supertypes) needs at compile time. */
    private static final List<String> ANCHORS = List.of(
            "jakarta.persistence.Entity",
            "org.hibernate.Hibernate",
            "org.springframework.data.jpa.repository.JpaRepository",
            "org.springframework.data.repository.Repository",
            "org.springframework.core.SpringVersion",
            "org.jspecify.annotations.Nullable");

    static Stream<String> cases() throws IOException {
        return GoldenFiles.cases();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void generatedSourcesCompileCleanly(String name, @TempDir Path out) throws IOException {
        List<JavaFileObject> sources = new ArrayList<>();
        for (GeneratedFile file : GoldenFiles.generate(name)) {
            sources.add(new SimpleJavaFileObject(file.relativePath().toUri(), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return file.content();
                }
            });
        }

        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
            files.setLocation(StandardLocation.CLASS_PATH, classpath());
            boolean ok = javac.getTask(
                            new StringWriter(),
                            files,
                            diagnostics,
                            List.of("--release", "21", "-Xlint:all", "-Xlint:-processing", "-proc:none"),
                            null,
                            sources)
                    .call();

            List<String> problems = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() != Diagnostic.Kind.NOTE)
                    .map(d -> d.getKind() + " " + d.getSource().getName() + ":" + d.getLineNumber() + " "
                            + d.getMessage(Locale.ROOT))
                    .toList();
            assertThat(problems).as("javac diagnostics").isEmpty();
            assertThat(ok).isTrue();
        }
    }

    private static Set<File> classpath() {
        Set<File> jars = new LinkedHashSet<>();
        for (String anchor : ANCHORS) {
            try {
                Class<?> type = Class.forName(anchor, false, GeneratedCodeCompilesTest.class.getClassLoader());
                jars.add(new File(
                        type.getProtectionDomain().getCodeSource().getLocation().toURI()));
            } catch (ClassNotFoundException | URISyntaxException e) {
                // optional annotation jars (e.g. jspecify) may be absent on some Spring versions
            }
        }
        assertThat(jars).as("compile classpath for generated code").hasSizeGreaterThanOrEqualTo(4);
        assertThat(jars).allSatisfy(f -> assertThat(Files.exists(f.toPath())).isTrue());
        return jars;
    }
}
