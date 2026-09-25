/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.codegen;

import static dev.vantix.codegen.JavaTypes.*;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import dev.vantix.core.model.DefaultValue;
import dev.vantix.core.model.Entity;
import dev.vantix.core.model.Field;
import dev.vantix.core.model.FieldType;
import dev.vantix.core.model.Generator;
import dev.vantix.core.model.Index;
import dev.vantix.core.model.Relation;
import dev.vantix.core.model.RelationKind;
import dev.vantix.core.model.ScalarType;
import dev.vantix.core.model.Schema;
import dev.vantix.core.semantic.Names;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Modifier;

/**
 * Emits one JPA entity per {@link Entity}: annotated fields in declaration order, accessors,
 * proxy-safe {@code equals}/{@code hashCode}, and a relation-free {@code toString}.
 *
 * <p>With {@code useGenerationGap}, the persistent state goes into an abstract
 * {@code @MappedSuperclass} {@code UserBase} (regenerated) and a {@code User extends UserBase}
 * entity is scaffolded once for the user to own.
 */
final class EntityGenerator {

    private final Schema schema;
    private final Generator generator;

    EntityGenerator(Schema schema) {
        this.schema = schema;
        this.generator = schema.generator();
    }

    List<GeneratedFile> generate(Entity entity) {
        ClassName entityClass = entityClass(entity.name());
        if (!generator.useGenerationGap()) {
            TypeSpec type = persistentClass(entity, entityClass, entityClass, false);
            return List.of(Sources.generated(generator.packageName(), type));
        }
        ClassName baseClass = ClassName.get(generator.packageName(), entityClass.simpleName() + "Base");
        TypeSpec base = persistentClass(entity, baseClass, entityClass, true);
        return List.of(Sources.generated(generator.packageName(), base), scaffold(entity, entityClass, baseClass));
    }

    ClassName entityClass(String entityName) {
        return ClassName.get(generator.packageName(), entityName + generator.entitySuffix());
    }

    // ---------------------------------------------------------------------------------------------
    // Class shells
    // ---------------------------------------------------------------------------------------------

    /**
     * The class holding fields, accessors, equals/hashCode/toString — either the entity itself or, in
     * Generation Gap mode, its abstract base.
     */
    private TypeSpec persistentClass(Entity entity, ClassName self, ClassName entityClass, boolean base) {
        TypeSpec.Builder type = TypeSpec.classBuilder(self).addModifiers(Modifier.PUBLIC);
        if (base) {
            type.addModifiers(Modifier.ABSTRACT)
                    .addAnnotation(MAPPED_SUPERCLASS)
                    .addJavadoc(
                            "Generated persistent state of {@link $T}, from {@code schema.vx} line $L.\n\n"
                                    + "<p>Do not edit: this class is regenerated on every build.\n"
                                    + "Add behaviour to {@code $L}, which Vantix created once and never touches.\n",
                            entityClass,
                            entity.position().line(),
                            entityClass.simpleName());
        } else {
            type.addAnnotation(ENTITY)
                    .addAnnotation(table(entity, true))
                    .addJavadoc(
                            "The {@code $L} entity, generated from {@code schema.vx} line $L.\n\n"
                                    + "<p>Do not edit: this class is regenerated on every build.\n"
                                    + "Change {@code schema.vx} instead, or enable {@code useGenerationGap}"
                                    + " to add your own methods.\n",
                            entity.name(),
                            entity.position().line());
        }
        type.addAnnotation(Sources.generatedAnnotation());

        Map<String, Relation> fkMirrors = new HashMap<>();
        for (Relation r : entity.relations()) {
            if (r.owning()) {
                fkMirrors.put(r.foreignKey().columnName(), r);
            }
        }

        List<Object> members = new ArrayList<>();
        members.addAll(entity.fields());
        members.addAll(entity.relations());
        members.sort(Comparator.comparingInt(m -> m instanceof Field f
                ? f.position().offset()
                : ((Relation) m).position().offset()));

        List<MethodSpec> accessors = new ArrayList<>();
        for (Object member : members) {
            if (member instanceof Field f) {
                Relation mirrored = f.ignored() ? null : fkMirrors.get(f.columnName());
                type.addField(scalarField(f, mirrored));
                accessors.add(mirrored == null ? getter(f.name(), javaType(f), null) : fkGetter(f, mirrored));
                if (mirrored == null) {
                    accessors.add(setter(f.name(), javaType(f)));
                }
            } else {
                Relation r = (Relation) member;
                type.addField(relationField(r));
                accessors.add(getter(r.fieldName(), relationType(r), null));
                accessors.add(setter(r.fieldName(), relationType(r)));
            }
        }
        type.addMethods(accessors);

        Field id = entity.idField().orElseThrow();
        type.addMethod(equalsMethod(self, id));
        type.addMethod(hashCodeMethod());
        type.addMethod(toStringMethod(entity));
        return type.build();
    }

    private GeneratedFile scaffold(Entity entity, ClassName entityClass, ClassName baseClass) {
        TypeSpec type = TypeSpec.classBuilder(entityClass)
                .addModifiers(Modifier.PUBLIC)
                .superclass(baseClass)
                .addAnnotation(ENTITY)
                .addAnnotation(table(entity, false))
                .addJavadoc(
                        "The {@code $L} entity. Its persistent state lives in the generated {@link $T}.\n\n"
                                + "<p>This file is yours: Vantix created it once and will never modify it.\n"
                                + "Add methods freely. If you rename the table with {@code @@table} in"
                                + " {@code schema.vx},\nupdate {@code @Table} here to match.\n",
                        entity.name(),
                        baseClass)
                .build();
        return Sources.scaffold(generator.packageName(), type);
    }

    private AnnotationSpec table(Entity entity, boolean withIndexes) {
        AnnotationSpec.Builder table = AnnotationSpec.builder(TABLE).addMember("name", "$S", entity.tableName());
        if (!withIndexes) {
            return table.build();
        }
        for (Index index : entity.indexes()) {
            List<String> columns = index.fieldNames().stream()
                    .map(n -> field(entity, n).columnName())
                    .toList();
            String name =
                    index.name() != null ? index.name() : Names.indexName(entity.tableName(), columns, index.unique());
            if (index.unique()) {
                CodeBlock names = CodeBlock.of(
                        "{$L}",
                        CodeBlock.join(
                                columns.stream().map(c -> CodeBlock.of("$S", c)).toList(), ", "));
                table.addMember(
                        "uniqueConstraints",
                        "$L",
                        AnnotationSpec.builder(UNIQUE_CONSTRAINT)
                                .addMember("name", "$S", name)
                                .addMember("columnNames", "$L", names)
                                .build());
            } else {
                table.addMember(
                        "indexes",
                        "$L",
                        AnnotationSpec.builder(INDEX)
                                .addMember("name", "$S", name)
                                .addMember("columnList", "$S", String.join(", ", columns))
                                .build());
            }
        }
        return table.build();
    }

    // ---------------------------------------------------------------------------------------------
    // Fields
    // ---------------------------------------------------------------------------------------------

    private FieldSpec scalarField(Field f, Relation mirrored) {
        FieldSpec.Builder b = FieldSpec.builder(javaType(f), f.name(), Modifier.PRIVATE);
        if (f.ignored()) {
            b.addAnnotation(TRANSIENT);
        } else {
            if (f.id()) {
                b.addAnnotation(ID);
            }
            if (f.generated()) {
                boolean uuid = f.type() instanceof FieldType.Scalar s && s.type() == ScalarType.UUID;
                b.addAnnotation(AnnotationSpec.builder(GENERATED_VALUE)
                        .addMember("strategy", "$T.$L", GENERATION_TYPE, uuid ? "UUID" : "IDENTITY")
                        .build());
            }
            if (f.type() instanceof FieldType.EnumRef) {
                b.addAnnotation(AnnotationSpec.builder(ENUMERATED)
                        .addMember("value", "$T.STRING", ENUM_TYPE)
                        .build());
            }
            if (f.type() instanceof FieldType.Scalar s && s.type() == ScalarType.JSON) {
                b.addAnnotation(AnnotationSpec.builder(JDBC_TYPE_CODE)
                        .addMember("value", "$T.JSON", SQL_TYPES)
                        .build());
            }
            if (f.updatedAt()) {
                b.addAnnotation(UPDATE_TIMESTAMP);
            }
            b.addAnnotation(column(f, mirrored != null));
        }
        CodeBlock initializer = initializer(f);
        if (initializer != null) {
            b.initializer(initializer);
        }
        return b.build();
    }

    private static AnnotationSpec column(Field f, boolean readOnly) {
        AnnotationSpec.Builder c = AnnotationSpec.builder(COLUMN).addMember("name", "$S", f.columnName());
        if (!f.nullable()) {
            c.addMember("nullable", "false");
        }
        if (f.unique()) {
            c.addMember("unique", "true");
        }
        if (f.length() != null) {
            c.addMember("length", "$L", f.length());
        }
        if (f.precision() != null) {
            c.addMember("precision", "$L", f.precision().precision());
            c.addMember("scale", "$L", f.precision().scale());
        }
        if (f.rawDdl() != null) {
            c.addMember("columnDefinition", "$S", f.rawDdl());
        }
        if (readOnly) {
            // The relation owns the foreign key; this mirror only reads it (see the getter).
            c.addMember("insertable", "false");
            c.addMember("updatable", "false");
        } else if (f.id()) {
            c.addMember("updatable", "false");
        }
        return c.build();
    }

    /** Java-side mirror of {@code @default}, so a new instance already holds the database default. */
    private CodeBlock initializer(Field f) {
        DefaultValue d = f.defaultValue();
        if (d == null) {
            return null;
        }
        return switch (d) {
            case DefaultValue.Now n -> CodeBlock.of("$T.now()", javaType(f));
            case DefaultValue.UuidGen u -> CodeBlock.of("$T.randomUUID()", UUID);
            case DefaultValue.Literal l ->
                switch (f.type()) {
                    case FieldType.EnumRef ref ->
                        CodeBlock.of("$T.$L", ClassName.get(generator.packageName(), ref.enumName()), l.value());
                    case FieldType.Scalar s ->
                        switch (s.type()) {
                            case STRING, JSON -> CodeBlock.of("$S", l.value());
                            case INT -> CodeBlock.of("$L", l.value());
                            case LONG -> CodeBlock.of("$LL", l.value());
                            case FLOAT -> CodeBlock.of("$Ld", l.value());
                            case DECIMAL -> CodeBlock.of("new $T($S)", BIG_DECIMAL, l.value());
                            case BOOLEAN -> CodeBlock.of("$L", l.value());
                            default -> throw new IllegalStateException("No literal default for " + s.type());
                        };
                };
        };
    }

    private FieldSpec relationField(Relation r) {
        FieldSpec.Builder b = FieldSpec.builder(relationType(r), r.fieldName(), Modifier.PRIVATE);
        switch (r.kind()) {
            case MANY_TO_ONE, ONE_TO_ONE -> {
                ClassName kind = r.kind() == RelationKind.MANY_TO_ONE ? MANY_TO_ONE : ONE_TO_ONE;
                AnnotationSpec.Builder assoc = AnnotationSpec.builder(kind);
                if (r.owning()) {
                    assoc.addMember("fetch", "$T.LAZY", FETCH_TYPE);
                    if (!r.nullable()) {
                        assoc.addMember("optional", "false");
                    }
                    b.addAnnotation(assoc.build());
                    AnnotationSpec.Builder join = AnnotationSpec.builder(JOIN_COLUMN)
                            .addMember("name", "$S", r.foreignKey().columnName());
                    Entity target = schema.entity(r.targetEntity()).orElseThrow();
                    String targetId = target.idField().orElseThrow().columnName();
                    if (!r.foreignKey().referencedColumn().equals(targetId)) {
                        join.addMember(
                                "referencedColumnName", "$S", r.foreignKey().referencedColumn());
                    }
                    if (!r.nullable()) {
                        join.addMember("nullable", "false");
                    }
                    b.addAnnotation(join.build());
                } else {
                    assoc.addMember("mappedBy", "$S", r.mappedBy());
                    assoc.addMember("fetch", "$T.LAZY", FETCH_TYPE);
                    b.addAnnotation(assoc.build());
                }
            }
            case ONE_TO_MANY -> {
                b.addAnnotation(AnnotationSpec.builder(ONE_TO_MANY)
                        .addMember("mappedBy", "$S", r.mappedBy())
                        .build());
                b.initializer("new $T<>()", ARRAY_LIST);
            }
        }
        return b.build();
    }

    // ---------------------------------------------------------------------------------------------
    // Methods
    // ---------------------------------------------------------------------------------------------

    private static MethodSpec getter(String name, TypeName type, CodeBlock body) {
        MethodSpec.Builder m = MethodSpec.methodBuilder("get" + capitalize(name))
                .addModifiers(Modifier.PUBLIC)
                .returns(type);
        if (body != null) {
            m.addCode(body);
        } else {
            m.addStatement("return $N", name);
        }
        return m.build();
    }

    private static MethodSpec setter(String name, TypeName type) {
        return MethodSpec.methodBuilder("set" + capitalize(name))
                .addModifiers(Modifier.PUBLIC)
                .addParameter(type, name)
                .addStatement("this.$N = $N", name, name)
                .build();
    }

    /**
     * The FK mirror's getter prefers the relation, so it is correct before the entity is flushed and
     * after the relation is reassigned. {@code getId()} on a lazy proxy does not initialize it.
     */
    private MethodSpec fkGetter(Field fk, Relation relation) {
        Entity target = schema.entity(relation.targetEntity()).orElseThrow();
        Field targetId = target.idField().orElseThrow();
        String repository = relation.targetEntity() + "Repository";
        MethodSpec.Builder m = MethodSpec.methodBuilder("get" + capitalize(fk.name()))
                .addModifiers(Modifier.PUBLIC)
                .returns(javaType(fk))
                .addJavadoc(
                        "The {@code $L} foreign key. Read-only: there is deliberately no setter, because the\n"
                                + "{@code $L} relation owns the column. To point this row at another {@code $L}\n"
                                + "by id without loading it, assign"
                                + " {@code set$L($L.getReferenceById(id))}.\n",
                        relation.fieldName(),
                        relation.fieldName(),
                        relation.targetEntity(),
                        capitalize(relation.fieldName()),
                        lowerFirst(repository));
        if (!relation.foreignKey().referencedColumn().equals(targetId.columnName())) {
            return m.addStatement("return $N", fk.name()).build();
        }
        // Prefer the relation: correct before flush and after reassignment, and getId() on a lazy
        // proxy does not initialize it.
        return m.addStatement(
                        "return $N != null ? $N.get$L() : $N",
                        relation.fieldName(),
                        relation.fieldName(),
                        capitalize(targetId.name()),
                        fk.name())
                .build();
    }

    /**
     * Id-based equality that is correct for transient, managed, detached and proxied instances: two
     * instances are equal only if they have the same effective (unproxied) class and the same non-null
     * id. The effective class comes from the proxy's lazy initializer, which does not trigger a load.
     */
    private static MethodSpec equalsMethod(ClassName self, Field id) {
        String getter = "get" + capitalize(id.name());
        return MethodSpec.methodBuilder("equals")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .returns(TypeName.BOOLEAN)
                .addParameter(OBJECT, "o")
                .beginControlFlow("if (this == o)")
                .addStatement("return true")
                .endControlFlow()
                .beginControlFlow("if (o == null)")
                .addStatement("return false")
                .endControlFlow()
                .addCode(
                        "$T<?> oEffectiveClass = o instanceof $T oProxy\n$>$>? oProxy.getHibernateLazyInitializer()"
                                + ".getPersistentClass()\n: o.getClass();\n$<$<",
                        Class.class,
                        HIBERNATE_PROXY)
                .addCode(
                        "$T<?> thisEffectiveClass = this instanceof $T thisProxy\n$>$>? thisProxy"
                                + ".getHibernateLazyInitializer().getPersistentClass()\n: this.getClass();\n$<$<",
                        Class.class,
                        HIBERNATE_PROXY)
                .beginControlFlow("if (thisEffectiveClass != oEffectiveClass)")
                .addStatement("return false")
                .endControlFlow()
                .addStatement("$T other = ($T) o", self, self)
                .addStatement("return $L() != null && $T.equals($L(), other.$L())", getter, OBJECTS, getter, getter)
                .build();
    }

    /**
     * Constant per entity class, so the hash never changes when an id is assigned on persist — an
     * instance put in a {@code HashSet} before saving can still be found after.
     */
    private static MethodSpec hashCodeMethod() {
        return MethodSpec.methodBuilder("hashCode")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .returns(TypeName.INT)
                .addCode(
                        "return this instanceof $T proxy\n$>$>? proxy.getHibernateLazyInitializer()"
                                + ".getPersistentClass().hashCode()\n: getClass().hashCode();\n$<$<",
                        HIBERNATE_PROXY)
                .build();
    }

    /** Scalars only: touching a relation here could trigger lazy loading or infinite recursion. */
    private static MethodSpec toStringMethod(Entity entity) {
        List<Field> shown = entity.fields().stream()
                .filter(f -> !f.ignored())
                .filter(f -> !(f.type() instanceof FieldType.Scalar s
                        && (s.type() == ScalarType.BYTES || s.type() == ScalarType.JSON)))
                .toList();
        CodeBlock.Builder expr = CodeBlock.builder().add("return $S", entity.name() + "{");
        for (int i = 0; i < shown.size(); i++) {
            Field f = shown.get(i);
            expr.add("\n$>$>+ $S + get$L()$<$<", (i == 0 ? "" : ", ") + f.name() + "=", capitalize(f.name()));
        }
        expr.add("\n$>$>+ $S;\n$<$<", "}");
        return MethodSpec.methodBuilder("toString")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(STRING)
                .addCode(expr.build())
                .build();
    }

    // ---------------------------------------------------------------------------------------------
    // Types
    // ---------------------------------------------------------------------------------------------

    private TypeName javaType(Field f) {
        return switch (f.type()) {
            case FieldType.Scalar s -> JavaTypes.of(s.type());
            case FieldType.EnumRef r -> ClassName.get(generator.packageName(), r.enumName());
        };
    }

    private TypeName relationType(Relation r) {
        ClassName target = entityClass(r.targetEntity());
        return r.kind() == RelationKind.ONE_TO_MANY ? ParameterizedTypeName.get(LIST, target) : target;
    }

    private static Field field(Entity entity, String name) {
        return entity.fields().stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static String lowerFirst(String s) {
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
