/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.cli;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "init", description = "Create vantix/schema.vx and git-ignore the generated sources.")
final class InitCommand implements Callable<Integer> {

    @Mixin
    CommonOptions options;

    @Option(
            names = "--package",
            paramLabel = "<name>",
            description = "Package for generated code (default: <your @SpringBootApplication package>.model).")
    String packageName;

    @Option(names = "--force", description = "Overwrite an existing schema file.")
    boolean force;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        Output output =
                new Output(spec.commandLine().getOut(), spec.commandLine().getErr(), options);
        Workflow.Init init;
        try {
            init = Workflow.init(options.projectDir, options.schemaFile(), packageName, force);
        } catch (IOException e) {
            output.failure("Could not initialize: " + e.getMessage());
            return 1;
        }
        String schema = options.display(init.schemaFile()).toString().replace('\\', '/');
        if (init.schemaWritten()) {
            output.success("Created " + schema + " (package " + init.packageName() + ")");
        } else {
            output.info(schema + " already exists; left untouched (use --force to overwrite)");
        }
        if (init.gitignoreUpdated()) {
            output.success("Added target/generated-sources/vantix/ to .gitignore");
        }
        if (init.schemaWritten()) {
            output.info("  Next: edit the schema, then run `mvn compile` or `vantix generate`.");
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("ok", true);
        json.put("schema", schema);
        json.put("created", init.schemaWritten());
        json.put("package", init.packageName());
        json.put("gitignoreUpdated", init.gitignoreUpdated());
        output.json(json);
        return 0;
    }
}
