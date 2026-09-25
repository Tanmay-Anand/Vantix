/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Root of the {@code vantix} command tree. Phase 1 ships {@code init}, {@code validate} and
 * {@code generate}; {@code migrate}, {@code db}, {@code studio} and {@code doctor} arrive with their
 * phases.
 */
@Command(
        name = "vantix",
        mixinStandardHelpOptions = true,
        versionProvider = VantixCli.ManifestVersionProvider.class,
        description = "Prisma-style developer-experience toolchain for Spring Boot.",
        subcommands = {InitCommand.class, ValidateCommand.class, GenerateCommand.class},
        exitCodeListHeading = "%nExit codes:%n",
        exitCodeList = {
            "0:success",
            "1:the schema has errors, is missing, or generated code would not compile",
            "2:invalid command-line usage"
        })
public final class VantixCli implements Runnable {

    @Spec
    CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    /** The configured command line; {@link #main} and tests share it. */
    public static CommandLine commandLine() {
        return new CommandLine(new VantixCli()).setExecutionExceptionHandler((e, cmd, parsed) -> {
            cmd.getErr().println("vantix: unexpected error: " + e);
            cmd.getErr().flush();
            return 1;
        });
    }

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    /** Reads the version from the JAR manifest so {@code --version} stays accurate across releases. */
    static final class ManifestVersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String version = VantixCli.class.getPackage().getImplementationVersion();
            return new String[] {"vantix " + (version == null ? "0.1.0-SNAPSHOT" : version)};
        }
    }
}
