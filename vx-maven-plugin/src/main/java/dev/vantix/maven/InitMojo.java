/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.maven;

import dev.vantix.cli.Workflow;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * {@code mvn vantix:init}: creates {@code vantix/schema.vx} (never overwriting one without
 * {@code -Dvantix.force}) and git-ignores the generated sources. Same behaviour as {@code vantix init}.
 */
@Mojo(name = "init", requiresProject = true, threadSafe = true)
public class InitMojo extends AbstractMojo {

    @Parameter(property = "vantix.schemaPath", defaultValue = Workflow.DEFAULT_SCHEMA)
    String schemaPath;

    /** Package for generated code; defaults to the {@code @SpringBootApplication} package + {@code .model}. */
    @Parameter(property = "vantix.package")
    String packageName;

    /** Overwrite an existing schema file. */
    @Parameter(property = "vantix.force", defaultValue = "false")
    boolean force;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        Path basedir = project.getBasedir().toPath();
        Workflow.Init init;
        try {
            init = Workflow.init(basedir, basedir.resolve(schemaPath), packageName, force);
        } catch (IOException e) {
            throw new MojoExecutionException("vantix:init failed: " + e.getMessage(), e);
        }
        if (init.schemaWritten()) {
            getLog().info("Vantix: created " + schemaPath + " (package " + init.packageName() + ")");
        } else {
            getLog().info("Vantix: " + schemaPath + " already exists; left untouched (-Dvantix.force=true overwrites)");
        }
        if (init.gitignoreUpdated()) {
            getLog().info("Vantix: added target/generated-sources/vantix/ to .gitignore");
        }
    }
}
