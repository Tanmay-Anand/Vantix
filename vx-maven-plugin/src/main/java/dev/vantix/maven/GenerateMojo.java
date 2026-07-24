/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.maven;

import java.io.File;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Generates Vantix sources (entities, repositories) from {@code schema.vx} into
 * {@code target/generated-sources/vantix}, bound to the {@code generate-sources} phase so the
 * output is on the compile source root before {@code javac} runs.
 *
 * <p>Phase 0 skeleton: logs its wiring and does not yet invoke the generator. The real generation
 * pipeline is delegated to {@code vx-cli}/{@code vx-codegen} in Phase 1.
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true, requiresProject = true)
public class GenerateMojo extends AbstractMojo {

    /** Location of the schema file, relative to the project base directory. */
    @Parameter(property = "vantix.schemaPath", defaultValue = "vantix/schema.vx")
    private String schemaPath;

    /** Directory the generated Java sources are written to. */
    @Parameter(
            property = "vantix.outputDirectory",
            defaultValue = "${project.build.directory}/generated-sources/vantix")
    private File outputDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("vantix:generate (Phase 0 skeleton)");
        getLog().info("  schemaPath      = " + schemaPath);
        getLog().info("  outputDirectory = " + outputDirectory);
        // Phase 1: parse schemaPath -> Schema model -> JavaPoet emit into outputDirectory,
        // then add outputDirectory as a compile source root.
    }
}
