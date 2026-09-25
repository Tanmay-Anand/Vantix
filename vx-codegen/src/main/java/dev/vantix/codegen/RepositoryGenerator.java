/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.codegen;

import static dev.vantix.codegen.JavaTypes.JPA_REPOSITORY;
import static dev.vantix.codegen.JavaTypes.OPTIONAL;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import dev.vantix.core.model.Entity;
import dev.vantix.core.model.Field;
import dev.vantix.core.model.FieldType;
import dev.vantix.core.model.ScalarType;
import dev.vantix.core.model.Schema;
import javax.lang.model.element.Modifier;

/**
 * Emits a Spring Data {@code JpaRepository} per entity, plus a derived {@code findByX} returning
 * {@code Optional} for every {@code @unique} field — uniqueness is what makes {@code Optional} (rather
 * than a list) the honest return type.
 */
final class RepositoryGenerator {

    private final Schema schema;
    private final EntityGenerator entities;

    RepositoryGenerator(Schema schema, EntityGenerator entities) {
        this.schema = schema;
        this.entities = entities;
    }

    GeneratedFile generate(Entity entity) {
        String pkg = schema.generator().packageName();
        ClassName entityClass = entities.entityClass(entity.name());
        Field id = entity.idField().orElseThrow();
        TypeSpec.Builder repo = TypeSpec.interfaceBuilder(ClassName.get(pkg, entity.name() + "Repository"))
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(ParameterizedTypeName.get(JPA_REPOSITORY, entityClass, type(id)))
                .addAnnotation(Sources.generatedAnnotation())
                .addJavadoc(
                        "Spring Data repository for {@link $T}, generated from {@code schema.vx}.\n\n"
                                + "<p>Do not edit: add custom queries in your own repository interface.\n",
                        entityClass);
        for (Field f : entity.fields()) {
            if (!f.unique() || f.id() || f.ignored() || isOpaque(f)) {
                continue;
            }
            repo.addMethod(MethodSpec.methodBuilder("findBy" + EntityGenerator.capitalize(f.name()))
                    .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                    .addParameter(type(f), f.name())
                    .returns(ParameterizedTypeName.get(OPTIONAL, entityClass))
                    .addJavadoc("Finds by {@code $L}, which is {@code @unique}.\n", f.name())
                    .build());
        }
        return Sources.generated(pkg, repo.build());
    }

    private TypeName type(Field f) {
        return switch (f.type()) {
            case FieldType.Scalar s -> JavaTypes.of(s.type());
            case FieldType.EnumRef r -> ClassName.get(schema.generator().packageName(), r.enumName());
        };
    }

    /** Binary and JSON columns cannot sensibly be looked up by equality. */
    private static boolean isOpaque(Field f) {
        return f.type() instanceof FieldType.Scalar s && (s.type() == ScalarType.BYTES || s.type() == ScalarType.JSON);
    }
}
