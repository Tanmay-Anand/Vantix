/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Enforces the load-bearing architectural guarantees from IMPLEMENTATION_PLAN.md §2.3:
 *
 * <ul>
 *   <li>the compile-time toolchain (CLI, codegen, JavaPoet, picocli) must never leak into the
 *       thin runtime that ships inside user apps;
 *   <li>{@code vx-core} — the model contract — must stay free of Spring and JavaPoet.
 * </ul>
 *
 * These rules pass trivially over the Phase 0 skeleton; they exist now so the seams cannot rot as
 * real code lands.
 */
@AnalyzeClasses(
        packages = "dev.vantix",
        importOptions = {ImportOption.DoNotIncludeTests.class})
class ModuleBoundaryTest {

    @ArchTest
    static final ArchRule runtime_does_not_depend_on_the_toolchain = noClasses()
            .that()
            .resideInAPackage("dev.vantix.runtime..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "dev.vantix.cli..",
                    "dev.vantix.codegen..",
                    "dev.vantix.maven..",
                    "info.picocli..",
                    "com.palantir.javapoet..");

    @ArchTest
    static final ArchRule core_stays_framework_free = noClasses()
            .that()
            .resideInAPackage("dev.vantix.core..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "com.palantir.javapoet..", "info.picocli..");
}
