/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command(
        name = "validate",
        description = "Parse and check the schema, reporting every problem at once. Exits 1 on errors.")
final class ValidateCommand implements Callable<Integer> {

    @Mixin
    CommonOptions options;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        Output output =
                new Output(spec.commandLine().getOut(), spec.commandLine().getErr(), options);
        Path schemaFile = options.schemaFile();
        Workflow.Validation v;
        try {
            v = Workflow.validate(schemaFile);
        } catch (IOException e) {
            return Reports.readFailure(output, options, schemaFile, e);
        }
        output.diagnostics(v, options.display(schemaFile));
        Reports.validationSummary(output, options, v);
        Map<String, Object> json = new LinkedHashMap<>();
        Reports.putValidation(json, options, v);
        output.json(json);
        return v.ok() ? 0 : 1;
    }
}
