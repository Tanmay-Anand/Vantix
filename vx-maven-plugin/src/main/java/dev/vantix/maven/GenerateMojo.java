/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.maven;

import dev.vantix.cli.Workflow;
import dev.vantix.core.diagnostic.Diagnostic;
import dev.vantix.core.diagnostic.DiagnosticRenderer;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * {@code vantix:generate}, bound to {@code generate-sources}: compiles {@code schema.vx}, writes
 * entities and repositories, and adds the output directory as a compile source root — so
 * {@code mvn compile} is all a user runs.
 *
 * <p>All generator settings (package, output directory, Generation Gap, ...) come from
 * {@code schema.vx} itself (D8); the plugin only needs to know where that file is. It runs the exact
 * code path of {@code vantix generate}, so the two can never disagree. Unchanged files are not
 * rewritten, so running this on every build does not trigger recompilation.
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true, requiresProject = true)
public class GenerateMojo extends AbstractMojo {

    /** Location of the schema file, relative to the project base directory. */
    @Parameter(property = "vantix.schemaPath", defaultValue = Workflow.DEFAULT_SCHEMA)
    String schemaPath;

    /** Skip generation entirely. */
    @Parameter(property = "vantix.skip", defaultValue = "false")
    boolean skip;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("vantix:generate skipped (vantix.skip=true)");
            return;
        }
        Path basedir = project.getBasedir().toPath();
        Path schemaFile = basedir.resolve(schemaPath);

        Workflow.Generation g;
        try {
            g = Workflow.generate(basedir, schemaFile);
        } catch (Workflow.MissingSchemaException e) {
            throw new MojoFailureException("No Vantix schema at " + schemaPath
                    + ". Create one with `mvn vantix:init`, or set <schemaPath> in the plugin configuration.");
        } catch (IOException e) {
            throw new MojoExecutionException("vantix:generate failed: " + e.getMessage(), e);
        }

        logDiagnostics(g.validation());
        if (!g.validation().ok()) {
            throw new MojoFailureException(
                    schemaPath + " has " + g.validation().result().errorCount() + " error(s); see above");
        }
        g.report().warnings().forEach(getLog()::warn);
        g.report().conflicts().forEach(getLog()::error);
        if (!g.report().conflicts().isEmpty()) {
            throw new MojoFailureException("Generated sources conflict with hand-written classes; see above");
        }

        var r = g.report();
        getLog().info("Vantix: " + r.total() + " file(s) from " + schemaPath + " in "
                + g.elapsed().toMillis() + " ms (" + r.written().size() + " written, "
                + r.unchanged().size() + " unchanged)");
        r.scaffolded().forEach(p -> getLog().info("Vantix: scaffolded " + basedir.relativize(p) + " (yours to edit)"));
        r.deleted().forEach(p -> getLog().info("Vantix: deleted stale " + basedir.relativize(p)));

        project.addCompileSourceRoot(g.outputDirectory().toString());
    }

    private void logDiagnostics(Workflow.Validation v) {
        DiagnosticRenderer renderer = new DiagnosticRenderer(schemaPath, v.source(), false);
        for (Diagnostic d : v.result().diagnostics()) {
            String text = renderer.render(d).stripTrailing();
            if (d.isError()) {
                getLog().error(text);
            } else {
                getLog().warn(text);
            }
        }
    }
}
