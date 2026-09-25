/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.cli;

import java.nio.file.Path;
import picocli.CommandLine.Option;

/** Options every command accepts ({@code --schema}, {@code --json}, {@code --verbose}, ...). */
final class CommonOptions {

    @Option(
            names = {"-C", "--project-dir"},
            paramLabel = "<dir>",
            description = "Project root that relative paths are resolved against (default: current directory).")
    Path projectDir = Path.of(".");

    @Option(
            names = "--schema",
            paramLabel = "<file>",
            description = "Schema file (default: $VANTIX_SCHEMA_PATH, else vantix/schema.vx).")
    Path schema;

    @Option(names = "--json", description = "Machine-readable output for CI; prints nothing else.")
    boolean json;

    @Option(names = "--verbose", description = "Print every file and decision.")
    boolean verbose;

    @Option(names = "--no-color", description = "Disable ANSI colours (also: $VANTIX_NO_COLOR).")
    boolean noColor;

    /** The schema file: {@code --schema}, else {@code $VANTIX_SCHEMA_PATH}, else the default. */
    Path schemaFile() {
        if (schema != null) {
            return projectDir.resolve(schema);
        }
        String env = System.getenv("VANTIX_SCHEMA_PATH");
        return projectDir.resolve(env != null && !env.isBlank() ? env : Workflow.DEFAULT_SCHEMA);
    }

    /** A short path for messages: relative to the project dir when possible. */
    Path display(Path p) {
        Path base = projectDir.toAbsolutePath().normalize();
        Path abs = p.toAbsolutePath().normalize();
        return abs.startsWith(base) ? base.relativize(abs) : abs;
    }
}
