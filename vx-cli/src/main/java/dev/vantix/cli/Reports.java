/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.cli;

import dev.vantix.codegen.SourceWriter;
import dev.vantix.core.model.Schema;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Human and JSON reporting shared by the commands. */
final class Reports {

    private Reports() {}

    static int readFailure(Output output, CommonOptions options, Path schemaFile, IOException e) {
        String shown = options.display(schemaFile).toString().replace('\\', '/');
        if (e instanceof Workflow.MissingSchemaException) {
            output.failure("No schema found at " + shown);
            output.info("  Run `vantix init` to create one, or pass --schema <file>.");
            output.json(Map.of("ok", false, "schema", shown, "error", "schema not found"));
        } else {
            output.failure("Could not read " + shown + ": " + e.getMessage());
            output.json(Map.of("ok", false, "schema", shown, "error", String.valueOf(e.getMessage())));
        }
        return 1;
    }

    static void validationSummary(Output output, CommonOptions options, Workflow.Validation v) {
        String shown = options.display(v.schemaFile()).toString().replace('\\', '/');
        long errors = v.result().errorCount();
        long warnings = v.result().warningCount();
        if (errors > 0) {
            output.failure(shown + " has " + Output.plural(errors, "error")
                    + (warnings > 0 ? " and " + Output.plural(warnings, "warning") : ""));
            return;
        }
        Schema schema = v.result().schema();
        int entities = schema.entities().size();
        output.success(shown + " is valid: " + entities + (entities == 1 ? " entity" : " entities")
                + ", " + Output.plural(schema.enums().size(), "enum")
                + (warnings > 0 ? " (" + Output.plural(warnings, "warning") + ")" : ""));
    }

    static void putValidation(Map<String, Object> json, CommonOptions options, Workflow.Validation v) {
        json.put("ok", v.ok());
        json.put("schema", options.display(v.schemaFile()).toString().replace('\\', '/'));
        json.put("errors", v.result().errorCount());
        json.put("warnings", v.result().warningCount());
        json.put("diagnostics", Output.diagnosticsJson(v.result().diagnostics()));
    }

    static List<String> paths(CommonOptions options, List<Path> paths) {
        return paths.stream()
                .map(p -> options.display(p).toString().replace('\\', '/'))
                .toList();
    }

    static void putFiles(Map<String, Object> json, CommonOptions options, SourceWriter.Report r) {
        json.put(
                "files",
                Map.of(
                        "written", paths(options, r.written()),
                        "unchanged", paths(options, r.unchanged()),
                        "scaffolded", paths(options, r.scaffolded()),
                        "deleted", paths(options, r.deleted())));
        json.put("fileWarnings", r.warnings());
        json.put("conflicts", r.conflicts());
    }
}
