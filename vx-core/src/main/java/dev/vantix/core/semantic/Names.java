/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.semantic;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;

/** Naming conventions and reserved-word tables shared by semantic analysis and code generation. */
public final class Names {

    private Names() {}

    /**
     * {@code createdAt} → {@code created_at}, {@code OrderItem} → {@code order_item},
     * {@code HTTPServer} → {@code http_server}. Idempotent on snake_case input, so it agrees with
     * Spring Boot's default {@code CamelCaseToUnderscoresNamingStrategy} whether or not that strategy
     * is active.
     */
    public static String snakeCase(String name) {
        StringBuilder out = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                boolean prevLowerOrDigit =
                        i > 0 && !Character.isUpperCase(name.charAt(i - 1)) && name.charAt(i - 1) != '_';
                boolean acronymEnd = i > 0
                        && Character.isUpperCase(name.charAt(i - 1))
                        && i + 1 < name.length()
                        && Character.isLowerCase(name.charAt(i + 1));
                if (prevLowerOrDigit || acronymEnd) {
                    out.append('_');
                }
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * The deterministic name of an index ({@code users_created_at_idx}) or unique constraint
     * ({@code seat_row_number_key}), following PostgreSQL's own conventions. Codegen and the Phase 2
     * SQL renderer must agree on these, so both call this. Names longer than PostgreSQL's 63-byte
     * limit are shortened with a stable hash suffix instead of being silently truncated by the
     * database.
     */
    public static String indexName(String table, List<String> columns, boolean unique) {
        String name = table + "_" + String.join("_", columns) + (unique ? "_key" : "_idx");
        if (name.length() <= 63) {
            return name;
        }
        CRC32 crc = new CRC32();
        crc.update(name.getBytes(StandardCharsets.UTF_8));
        return name.substring(0, 54) + "_" + String.format(Locale.ROOT, "%08x", crc.getValue());
    }

    /** Whether {@code name} is a legal unquoted PostgreSQL identifier Vantix is willing to emit. */
    public static boolean isSqlIdentifier(String name) {
        return name.matches("[a-z_][a-z0-9_]*") && name.length() <= 63;
    }

    /** Whether {@code name} is a syntactically valid Java package name (dot-separated identifiers). */
    public static boolean isJavaPackage(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (String segment : name.split("\\.", -1)) {
            if (!segment.matches("[A-Za-z_$][A-Za-z0-9_$]*") || JAVA_KEYWORDS.contains(segment)) {
                return false;
            }
        }
        return true;
    }

    /** Java reserved words and literals; none may be used as a generated class, field or constant name. */
    public static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract",
            "assert",
            "boolean",
            "break",
            "byte",
            "case",
            "catch",
            "char",
            "class",
            "const",
            "continue",
            "default",
            "do",
            "double",
            "else",
            "enum",
            "extends",
            "final",
            "finally",
            "float",
            "for",
            "goto",
            "if",
            "implements",
            "import",
            "instanceof",
            "int",
            "interface",
            "long",
            "native",
            "new",
            "package",
            "private",
            "protected",
            "public",
            "return",
            "short",
            "static",
            "strictfp",
            "super",
            "switch",
            "synchronized",
            "this",
            "throw",
            "throws",
            "transient",
            "try",
            "void",
            "volatile",
            "while",
            "true",
            "false",
            "null",
            "_");

    /** Contextual keywords that cannot name a Java type. */
    public static final Set<String> JAVA_RESTRICTED_TYPE_NAMES = Set.of("var", "yield", "record", "sealed", "permits");

    /**
     * {@code java.lang} types that generated code refers to by simple name. A same-package entity or
     * enum with one of these names would shadow the {@code java.lang} type and break compilation.
     */
    public static final Set<String> JAVA_LANG_CLASHES = Set.of(
            "Object",
            "String",
            "Integer",
            "Long",
            "Double",
            "Float",
            "Boolean",
            "Byte",
            "Short",
            "Character",
            "Number",
            "Class",
            "Override",
            "Enum",
            "Record",
            "System",
            "Math",
            "Void",
            "Iterable",
            "Comparable",
            "Deprecated",
            "SuppressWarnings",
            "Exception",
            "RuntimeException",
            "Error",
            "Thread",
            "Runnable");

    /** PostgreSQL reserved key words that cannot be used unquoted as a table or column name. */
    public static final Set<String> POSTGRES_RESERVED = Set.of(
            "all",
            "analyse",
            "analyze",
            "and",
            "any",
            "array",
            "as",
            "asc",
            "asymmetric",
            "authorization",
            "binary",
            "both",
            "case",
            "cast",
            "check",
            "collate",
            "collation",
            "column",
            "concurrently",
            "constraint",
            "create",
            "cross",
            "current_catalog",
            "current_date",
            "current_role",
            "current_schema",
            "current_time",
            "current_timestamp",
            "current_user",
            "default",
            "deferrable",
            "desc",
            "distinct",
            "do",
            "else",
            "end",
            "except",
            "false",
            "fetch",
            "for",
            "foreign",
            "freeze",
            "from",
            "full",
            "grant",
            "group",
            "having",
            "ilike",
            "in",
            "initially",
            "inner",
            "intersect",
            "into",
            "is",
            "isnull",
            "join",
            "lateral",
            "leading",
            "left",
            "like",
            "limit",
            "localtime",
            "localtimestamp",
            "natural",
            "not",
            "notnull",
            "null",
            "offset",
            "on",
            "only",
            "or",
            "order",
            "outer",
            "overlaps",
            "placing",
            "primary",
            "references",
            "returning",
            "right",
            "select",
            "session_user",
            "similar",
            "some",
            "symmetric",
            "system_user",
            "table",
            "tablesample",
            "then",
            "to",
            "trailing",
            "true",
            "union",
            "unique",
            "user",
            "using",
            "variadic",
            "verbose",
            "when",
            "where",
            "window",
            "with");
}
