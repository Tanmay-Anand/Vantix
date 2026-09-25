/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.codegen;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeSpec;
import dev.vantix.core.model.Entity;
import dev.vantix.core.model.EnumDecl;
import dev.vantix.core.model.Schema;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Modifier;

/**
 * Resolved {@link Schema} → Java sources: an enum per {@code enum}, an entity (or a base + scaffold
 * pair) per {@code entity}, and a repository per entity when {@code generateRepositories} is on.
 *
 * <p>Pure and deterministic: the same schema always yields byte-identical files, in the same order.
 * Writing them to disk is {@link SourceWriter}'s job.
 */
public final class CodeGenerator {

    private CodeGenerator() {}

    public static List<GeneratedFile> generate(Schema schema) {
        String pkg = schema.generator().packageName();
        EntityGenerator entities = new EntityGenerator(schema);
        RepositoryGenerator repositories = new RepositoryGenerator(schema, entities);
        List<GeneratedFile> files = new ArrayList<>();
        for (EnumDecl e : schema.enums()) {
            files.add(enumFile(pkg, e));
        }
        for (Entity entity : schema.entities()) {
            files.addAll(entities.generate(entity));
            if (schema.generator().generateRepositories()) {
                files.add(repositories.generate(entity));
            }
        }
        return List.copyOf(files);
    }

    private static GeneratedFile enumFile(String pkg, EnumDecl e) {
        TypeSpec.Builder type = TypeSpec.enumBuilder(ClassName.get(pkg, e.name()))
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Sources.generatedAnnotation())
                .addJavadoc(
                        "Generated from {@code enum $L} in {@code schema.vx} line $L.\n",
                        e.name(),
                        e.position().line());
        e.values().forEach(type::addEnumConstant);
        return Sources.generated(pkg, type.build());
    }
}
