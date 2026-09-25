/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.codegen;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;
import dev.vantix.core.model.ScalarType;

/**
 * Types referenced by generated code. They are named, not loaded: vx-codegen has no dependency on
 * JPA, Hibernate or Spring — those only need to be on the <em>user's</em> classpath.
 */
final class JavaTypes {

    private JavaTypes() {}

    private static final String JPA = "jakarta.persistence";

    static final ClassName ENTITY = ClassName.get(JPA, "Entity");
    static final ClassName MAPPED_SUPERCLASS = ClassName.get(JPA, "MappedSuperclass");
    static final ClassName TABLE = ClassName.get(JPA, "Table");
    static final ClassName INDEX = ClassName.get(JPA, "Index");
    static final ClassName UNIQUE_CONSTRAINT = ClassName.get(JPA, "UniqueConstraint");
    static final ClassName ID = ClassName.get(JPA, "Id");
    static final ClassName GENERATED_VALUE = ClassName.get(JPA, "GeneratedValue");
    static final ClassName GENERATION_TYPE = ClassName.get(JPA, "GenerationType");
    static final ClassName COLUMN = ClassName.get(JPA, "Column");
    static final ClassName ENUMERATED = ClassName.get(JPA, "Enumerated");
    static final ClassName ENUM_TYPE = ClassName.get(JPA, "EnumType");
    static final ClassName TRANSIENT = ClassName.get(JPA, "Transient");
    static final ClassName MANY_TO_ONE = ClassName.get(JPA, "ManyToOne");
    static final ClassName ONE_TO_ONE = ClassName.get(JPA, "OneToOne");
    static final ClassName ONE_TO_MANY = ClassName.get(JPA, "OneToMany");
    static final ClassName JOIN_COLUMN = ClassName.get(JPA, "JoinColumn");
    static final ClassName FETCH_TYPE = ClassName.get(JPA, "FetchType");

    static final ClassName HIBERNATE_PROXY = ClassName.get("org.hibernate.proxy", "HibernateProxy");
    static final ClassName UPDATE_TIMESTAMP = ClassName.get("org.hibernate.annotations", "UpdateTimestamp");
    static final ClassName JDBC_TYPE_CODE = ClassName.get("org.hibernate.annotations", "JdbcTypeCode");
    static final ClassName SQL_TYPES = ClassName.get("org.hibernate.type", "SqlTypes");

    static final ClassName JPA_REPOSITORY = ClassName.get("org.springframework.data.jpa.repository", "JpaRepository");

    /** JDK-provided, source-retention marker for generated code. */
    static final ClassName GENERATED = ClassName.get("javax.annotation.processing", "Generated");

    static final ClassName OBJECT = ClassName.get("java.lang", "Object");
    static final ClassName STRING = ClassName.get("java.lang", "String");
    static final ClassName OBJECTS = ClassName.get("java.util", "Objects");
    static final ClassName OPTIONAL = ClassName.get("java.util", "Optional");
    static final ClassName LIST = ClassName.get("java.util", "List");
    static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");
    static final ClassName UUID = ClassName.get("java.util", "UUID");
    static final ClassName BIG_DECIMAL = ClassName.get("java.math", "BigDecimal");
    static final ClassName INSTANT = ClassName.get("java.time", "Instant");
    static final ClassName LOCAL_DATE = ClassName.get("java.time", "LocalDate");
    static final ClassName LOCAL_DATE_TIME = ClassName.get("java.time", "LocalDateTime");

    /** The Java type of a scalar column (boxed, so nullability is representable). */
    static TypeName of(ScalarType type) {
        return switch (type) {
            case STRING, JSON -> STRING;
            case INT -> ClassName.get("java.lang", "Integer");
            case LONG -> ClassName.get("java.lang", "Long");
            case DECIMAL -> BIG_DECIMAL;
            case FLOAT -> ClassName.get("java.lang", "Double");
            case BOOLEAN -> ClassName.get("java.lang", "Boolean");
            case INSTANT -> INSTANT;
            case LOCAL_DATE -> LOCAL_DATE;
            case LOCAL_DATE_TIME -> LOCAL_DATE_TIME;
            case UUID -> UUID;
            case BYTES -> ArrayTypeName.of(TypeName.BYTE);
        };
    }
}
