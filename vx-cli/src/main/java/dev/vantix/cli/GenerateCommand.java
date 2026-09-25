/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.cli;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "generate", description = "Generate entities and repositories into the configured output directory.")
final class GenerateCommand implements Callable<Integer> {

    @Mixin
    CommonOptions options;

    @Option(names = "--watch", description = "Regenerate whenever the schema file changes (Ctrl+C to stop).")
    boolean watch;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        Output output =
                new Output(spec.commandLine().getOut(), spec.commandLine().getErr(), options);
        int status = runOnce(output);
        if (!watch) {
            return status;
        }
        try {
            watch(output);
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (IOException e) {
            output.failure("Cannot watch " + options.display(options.schemaFile()) + ": " + e.getMessage());
            return 1;
        }
    }

    private int runOnce(Output output) {
        Path schemaFile = options.schemaFile();
        Workflow.Generation g;
        try {
            g = Workflow.generate(options.projectDir, schemaFile);
        } catch (IOException e) {
            return Reports.readFailure(output, options, schemaFile, e);
        }
        output.diagnostics(g.validation(), options.display(schemaFile));
        Map<String, Object> json = new LinkedHashMap<>();
        Reports.putValidation(json, options, g.validation());
        if (g.report() == null) {
            Reports.validationSummary(output, options, g.validation());
            output.info("  Nothing was generated.");
            output.json(json);
            return 1;
        }

        var r = g.report();
        for (String conflict : r.conflicts()) {
            output.failure(conflict);
        }
        for (String warning : r.warnings()) {
            output.info("warning: " + warning);
        }
        String where = options.display(g.outputDirectory()).toString().replace('\\', '/');
        String summary = "Generated " + Output.plural(r.total(), "file") + " in "
                + g.elapsed().toMillis() + " ms -> " + where + " ("
                + r.written().size() + " written, " + r.unchanged().size() + " unchanged)";
        if (g.ok()) {
            output.success(summary);
        } else {
            output.failure(summary + ", but the build will fail until the conflicts above are fixed");
        }
        Reports.paths(options, r.scaffolded()).forEach(p -> output.info("  scaffolded " + p + " (yours to edit)"));
        Reports.paths(options, r.deleted()).forEach(p -> output.info("  deleted stale " + p));
        Reports.paths(options, r.written()).forEach(p -> output.detail("  wrote     " + p));
        Reports.paths(options, r.unchanged()).forEach(p -> output.detail("  unchanged " + p));

        json.put("ok", g.ok());
        json.put("outputDirectory", where);
        Reports.putFiles(json, options, r);
        output.json(json);
        return g.ok() ? 0 : 1;
    }

    /** Blocks until interrupted, regenerating after each change to the schema file. */
    private void watch(Output output) throws IOException, InterruptedException {
        Path schema = options.schemaFile().toAbsolutePath().normalize();
        Path dir = schema.getParent();
        try (WatchService watcher = dir.getFileSystem().newWatchService()) {
            dir.register(watcher, ENTRY_CREATE, ENTRY_MODIFY);
            output.info("Watching " + options.display(schema).toString().replace('\\', '/') + " (Ctrl+C to stop)");
            while (true) {
                WatchKey key = watcher.take();
                boolean changed = touches(key, schema);
                // Editors often save in bursts (truncate, write, rename); let them settle, then coalesce.
                WatchKey more;
                while ((more = watcher.poll(150, TimeUnit.MILLISECONDS)) != null) {
                    changed |= touches(more, schema);
                }
                if (changed) {
                    output.info("");
                    runOnce(output);
                }
            }
        }
    }

    private static boolean touches(WatchKey key, Path schema) {
        boolean hit = false;
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.context() instanceof Path p && p.equals(schema.getFileName())) {
                hit = true;
            }
        }
        key.reset();
        return hit;
    }
}
