/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.cli;

import dev.vantix.codegen.CodeGenerator;
import dev.vantix.codegen.SourceWriter;
import dev.vantix.core.SchemaCompiler;
import dev.vantix.core.model.Schema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The validate and generate pipelines, shared verbatim by the CLI commands and the Maven plugin so
 * {@code vantix generate} and {@code mvn compile} can never disagree (D8: {@code schema.vx} is the
 * only configuration).
 */
public final class Workflow {

    /** Default schema location, relative to the project directory. */
    public static final String DEFAULT_SCHEMA = "vantix/schema.vx";

    /** Where Generation Gap scaffolds are written, relative to the project directory. */
    public static final String SCAFFOLD_ROOT = "src/main/java";

    private Workflow() {}

    /** Thrown when there is no schema to work on. */
    public static final class MissingSchemaException extends IOException {
        private static final long serialVersionUID = 1L;

        MissingSchemaException(Path schema) {
            super("No schema found at " + schema);
        }
    }

    /** A parsed and analyzed schema file. */
    public record Validation(Path schemaFile, String source, SchemaCompiler.Result result) {
        public boolean ok() {
            return !result.hasErrors();
        }
    }

    /**
     * A generation run. {@code report} is {@code null} when validation failed and nothing was
     * written.
     */
    public record Generation(
            Validation validation, SourceWriter.Report report, Path outputDirectory, Duration elapsed) {

        public boolean ok() {
            return validation.ok() && report != null && report.conflicts().isEmpty();
        }
    }

    public static Validation validate(Path schemaFile) throws IOException {
        String source;
        try {
            source = Files.readString(schemaFile, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new MissingSchemaException(schemaFile);
        }
        return new Validation(schemaFile, source, SchemaCompiler.compile(source));
    }

    /** What {@link #init} did. */
    public record Init(Path schemaFile, boolean schemaWritten, String packageName, boolean gitignoreUpdated) {}

    /** Fallback when no {@code @SpringBootApplication} class is found to derive a package from. */
    public static final String FALLBACK_PACKAGE = "com.example.app.model";

    private static final String GITIGNORE_ENTRY = "target/generated-sources/vantix/";

    /**
     * Scaffolds {@code schema.vx} (unless it exists and {@code force} is off) and makes sure generated
     * sources are git-ignored. With no explicit package, entities go in a {@code .model} subpackage of
     * the {@code @SpringBootApplication} class, so Spring Boot's entity scanning finds them.
     */
    public static Init init(Path projectDir, Path schemaFile, String packageName, boolean force) throws IOException {
        String pkg = packageName != null ? packageName : detectPackage(projectDir);
        boolean writeSchema = force || !Files.exists(schemaFile);
        if (writeSchema) {
            String template;
            try (var in = Workflow.class.getResourceAsStream("schema-template.vx")) {
                template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Files.createDirectories(schemaFile.toAbsolutePath().getParent());
            Files.writeString(schemaFile, template.replace("${package}", pkg), StandardCharsets.UTF_8);
        }
        return new Init(schemaFile, writeSchema, pkg, ensureGitignored(projectDir));
    }

    private static boolean ensureGitignored(Path projectDir) throws IOException {
        Path gitignore = projectDir.resolve(".gitignore");
        String existing = Files.exists(gitignore) ? Files.readString(gitignore, StandardCharsets.UTF_8) : "";
        boolean covered = existing.lines()
                .map(String::strip)
                .anyMatch(l -> l.equals("target/")
                        || l.equals("target")
                        || l.equals("/target/")
                        || l.equals("**/generated-sources/")
                        || l.equals(GITIGNORE_ENTRY));
        if (covered) {
            return false;
        }
        String prefix = existing.isEmpty() || existing.endsWith("\n") ? "" : "\n";
        Files.writeString(
                gitignore,
                existing + prefix + "# Vantix: generated from vantix/schema.vx on every build\n" + GITIGNORE_ENTRY
                        + "\n",
                StandardCharsets.UTF_8);
        return true;
    }

    /** {@code <package of the @SpringBootApplication class>.model}, if there is exactly one such class. */
    static String detectPackage(Path projectDir) throws IOException {
        Path sources = projectDir.resolve(SCAFFOLD_ROOT);
        if (!Files.isDirectory(sources)) {
            return FALLBACK_PACKAGE;
        }
        java.util.regex.Pattern pkg = java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
        try (var files = Files.walk(sources, 16)) {
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String text = Files.readString(file, StandardCharsets.ISO_8859_1);
                if (text.contains("@SpringBootApplication")) {
                    var m = pkg.matcher(text);
                    if (m.find()) {
                        return m.group(1) + ".model";
                    }
                }
            }
        }
        return FALLBACK_PACKAGE;
    }

    public static Generation generate(Path projectDir, Path schemaFile) throws IOException {
        long start = System.nanoTime();
        Validation validation = validate(schemaFile);
        if (!validation.ok()) {
            return new Generation(validation, null, null, Duration.ofNanos(System.nanoTime() - start));
        }
        Schema schema = validation.result().schema();
        Path output = projectDir.resolve(schema.generator().outputDirectory()).normalize();
        SourceWriter.Report report =
                SourceWriter.write(CodeGenerator.generate(schema), output, projectDir.resolve(SCAFFOLD_ROOT));
        return new Generation(validation, report, output, Duration.ofNanos(System.nanoTime() - start));
    }
}
