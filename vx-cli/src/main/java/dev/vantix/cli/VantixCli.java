/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Root of the {@code vantix} command tree. Phase 0 skeleton: the subcommands (init, validate,
 * generate, migrate, db pull, studio, doctor) are wired in Phase 1+.
 */
@Command(
        name = "vantix",
        mixinStandardHelpOptions = true,
        versionProvider = VantixCli.ManifestVersionProvider.class,
        description = "Prisma-style developer-experience toolchain for Spring Boot.")
public final class VantixCli implements Runnable {

    @Override
    public void run() {
        // With no subcommand, print usage. Real subcommands land in Phase 1.
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new VantixCli()).execute(args);
        System.exit(exitCode);
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
