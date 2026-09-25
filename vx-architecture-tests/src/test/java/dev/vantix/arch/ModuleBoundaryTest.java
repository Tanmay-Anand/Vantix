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
 * Enforces the load-bearing module boundaries (see CONTRIBUTING.md, "Module boundaries"):
 *
 * <ul>
 *   <li>the compile-time toolchain (CLI, codegen, JavaPoet, picocli) must never leak into the
 *       thin runtime that ships inside user apps;
 *   <li>{@code vx-core} — the model contract — must stay free of Spring and JavaPoet, and must not
 *       depend on the modules that consume it;
 *   <li>{@code vx-codegen} references JPA/Hibernate/Spring types only by name;
 *   <li>the whole compile-time toolchain is Spring-free.
 * </ul>
 *
 * Written in Phase 0, ahead of the code they guard, so the seams could not rot as the compiler,
 * generator and CLI landed; Phase 1 added the layering and "names, never loads" rules below.
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

    /** The model contract sits at the bottom: nothing in core may reach up into its consumers. */
    @ArchTest
    static final ArchRule core_does_not_depend_on_its_consumers = noClasses()
            .that()
            .resideInAPackage("dev.vantix.core..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.vantix.codegen..", "dev.vantix.migrate..", "dev.vantix.cli..");

    /**
     * The generator names JPA, Hibernate and Spring types in the code it writes, but must never load
     * them: they belong on the user's classpath, not the build tool's.
     */
    @ArchTest
    static final ArchRule codegen_only_names_the_runtime_types_it_generates = noClasses()
            .that()
            .resideInAPackage("dev.vantix.codegen..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..", "jakarta.persistence..", "org.hibernate..", "dev.vantix.cli..");

    @ArchTest
    static final ArchRule the_toolchain_is_spring_free = noClasses()
            .that()
            .resideInAnyPackage("dev.vantix.codegen..", "dev.vantix.migrate..", "dev.vantix.cli..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..");
}
