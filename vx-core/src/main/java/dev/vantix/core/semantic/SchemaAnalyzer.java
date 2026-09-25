/*
 * Copyright 2026 The Vantix Authors
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package dev.vantix.core.semantic;

import dev.vantix.core.ast.Ast;
import dev.vantix.core.diagnostic.Diagnostic;
import dev.vantix.core.diagnostic.Severity;
import dev.vantix.core.diagnostic.Suggestions;
import dev.vantix.core.model.Datasource;
import dev.vantix.core.model.DefaultValue;
import dev.vantix.core.model.Entity;
import dev.vantix.core.model.EnumDecl;
import dev.vantix.core.model.Field;
import dev.vantix.core.model.FieldType;
import dev.vantix.core.model.ForeignKey;
import dev.vantix.core.model.Generator;
import dev.vantix.core.model.Index;
import dev.vantix.core.model.OnDelete;
import dev.vantix.core.model.Precision;
import dev.vantix.core.model.Relation;
import dev.vantix.core.model.RelationKind;
import dev.vantix.core.model.ScalarType;
import dev.vantix.core.model.Schema;
import dev.vantix.core.model.SourcePosition;
import java.math.BigInteger;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Semantic analysis: resolves the {@link Ast} into the immutable {@link Schema} model, checking
 * everything the grammar cannot — type names, attribute placement and arguments, relation pairing
 * ({@code mappedBy} inference), primary keys, and naming hazards in the generated Java and SQL.
 *
 * <p>Like the parser, it reports every problem it finds rather than stopping at the first. A model is
 * produced only when there are no errors; warnings do not block generation.
 */
public final class SchemaAnalyzer {

    /** Package used when the schema has no {@code generator { package = ... }}. */
    public static final String DEFAULT_PACKAGE = "vantix.generated";

    /** Output directory used when the schema has no {@code generator { output = ... }}. */
    public static final String DEFAULT_OUTPUT = "target/generated-sources/vantix";

    private static final List<String> FIELD_ATTRIBUTES = List.of(
            "id",
            "generated",
            "unique",
            "default",
            "length",
            "precision",
            "column",
            "updatedAt",
            "ignore",
            "raw",
            "relation");
    private static final List<String> ENTITY_ATTRIBUTES = List.of("table", "index", "unique");
    private static final List<String> DATASOURCE_KEYS = List.of("provider", "url", "schema");
    private static final List<String> GENERATOR_KEYS = List.of(
            "package",
            "output",
            "entitySuffix",
            "generateRepos",
            "generateRepositories",
            "generateMetamodel",
            "generateDtos",
            "useGenerationGap");
    private static final List<String> RELATION_ARGS = List.of("fields", "references", "onDelete");
    private static final Map<String, OnDelete> ON_DELETE = new LinkedHashMap<>();

    static {
        ON_DELETE.put("NoAction", OnDelete.NO_ACTION);
        ON_DELETE.put("Restrict", OnDelete.RESTRICT);
        ON_DELETE.put("Cascade", OnDelete.CASCADE);
        ON_DELETE.put("SetNull", OnDelete.SET_NULL);
        ON_DELETE.put("SetDefault", OnDelete.SET_DEFAULT);
    }

    private static final Set<ScalarType> ID_TYPES =
            Set.of(ScalarType.LONG, ScalarType.INT, ScalarType.STRING, ScalarType.UUID);
    private static final Set<ScalarType> GENERATED_ID_TYPES = Set.of(ScalarType.LONG, ScalarType.INT, ScalarType.UUID);
    private static final Set<ScalarType> TEMPORAL_NOW =
            Set.of(ScalarType.INSTANT, ScalarType.LOCAL_DATE_TIME, ScalarType.LOCAL_DATE);

    /** The analysis outcome: a model (only when there are no errors) plus every diagnostic. */
    public record Result(Schema schema, List<Diagnostic> diagnostics) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean hasErrors() {
            return diagnostics.stream().anyMatch(Diagnostic::isError);
        }
    }

    private enum Kind {
        SCALAR,
        ENUM,
        RELATION,
        UNKNOWN
    }

    private final Ast.SchemaFile file;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Map<String, Ast.EntityDecl> entityDecls = new LinkedHashMap<>();
    private final Map<String, Ast.EnumDecl> enumDecls = new LinkedHashMap<>();
    private final Map<String, SourcePosition> typeNames = new HashMap<>();
    /** entity → scalar field name → resolved field (only fields that resolved cleanly). */
    private final Map<String, Map<String, Field>> scalars = new HashMap<>();
    /** entity → relation field name → resolved owning relation. */
    private final Map<String, Map<String, Relation>> owning = new HashMap<>();
    /** "Entity.field" of owning relations already paired with an inverse side. */
    private final Map<String, String> paired = new HashMap<>();

    private SchemaAnalyzer(Ast.SchemaFile file) {
        this.file = file;
    }

    public static Result analyze(Ast.SchemaFile file) {
        return new SchemaAnalyzer(file).run();
    }

    private Result run() {
        Ast.Block datasourceBlock = null;
        Ast.Block generatorBlock = null;
        for (Ast.Decl decl : file.declarations()) {
            switch (decl) {
                case Ast.Block b -> {
                    String keyword = b.keyword().text();
                    if (keyword.equals("datasource") || keyword.equals("generator")) {
                        boolean first = keyword.equals("datasource") ? datasourceBlock == null : generatorBlock == null;
                        if (!first) {
                            error(
                                    "Duplicate `" + keyword + "` block",
                                    b.keyword().position(),
                                    "keep a single block");
                        } else if (keyword.equals("datasource")) {
                            datasourceBlock = b;
                        } else {
                            generatorBlock = b;
                        }
                    } else if (keyword.equals("migrations")) {
                        error(
                                "The `migrations` block is not supported yet",
                                b.keyword().position(),
                                "migration settings arrive with `vantix migrate` in Phase 2");
                    } else {
                        String hint = Suggestions.didYouMean(keyword, List.of("datasource", "generator"));
                        error(
                                "Unknown block `" + keyword + "`",
                                b.keyword().position(),
                                hint != null
                                        ? hint
                                        : "top-level declarations are `entity`, `enum`, `datasource` and `generator`");
                    }
                }
                case Ast.EnumDecl e -> {
                    if (declareType(e.name())) {
                        enumDecls.put(e.name().text(), e);
                    }
                }
                case Ast.EntityDecl e -> {
                    if (declareType(e.name())) {
                        entityDecls.put(e.name().text(), e);
                    }
                }
            }
        }

        Datasource datasource = datasourceBlock == null ? null : datasource(datasourceBlock);
        Generator generator = generator(generatorBlock);
        List<EnumDecl> enums =
                enumDecls.values().stream().map(this::resolveEnum).toList();

        for (Ast.EntityDecl e : entityDecls.values()) {
            resolveScalars(e);
        }
        for (Ast.EntityDecl e : entityDecls.values()) {
            resolveOwningRelations(e);
        }
        List<Entity> entities = new ArrayList<>();
        for (Ast.EntityDecl e : entityDecls.values()) {
            entities.add(resolveEntity(e));
        }
        checkTableNames(entities);
        checkGeneratedClassNames(generator);

        diagnostics.sort(
                (a, b) -> Integer.compare(a.position().offset(), b.position().offset()));
        boolean failed = diagnostics.stream().anyMatch(Diagnostic::isError);
        Schema schema = failed
                ? null
                : new Schema(Schema.CURRENT_FORMAT_VERSION, datasource, generator, enums, List.copyOf(entities));
        return new Result(schema, diagnostics);
    }

    // =============================================================================================
    // Declarations
    // =============================================================================================

    private boolean declareType(Ast.Name name) {
        String n = name.text();
        if (ScalarType.fromSchemaName(n).isPresent()) {
            error("`" + n + "` is a built-in type and cannot be redeclared", name.position(), null);
            return false;
        }
        if (typeNames.containsKey(n)) {
            error(
                    "`" + n + "` is already declared",
                    name.position(),
                    "entity and enum names must be unique",
                    List.of(new Diagnostic.Label(typeNames.get(n), "first declared here")));
            return false;
        }
        if (Names.JAVA_KEYWORDS.contains(n) || Names.JAVA_RESTRICTED_TYPE_NAMES.contains(n)) {
            error("`" + n + "` is a reserved word in Java and cannot name a generated type", name.position(), null);
            return false;
        }
        if (Names.JAVA_LANG_CLASHES.contains(n)) {
            error(
                    "`" + n + "` would clash with `java.lang." + n + "` in generated code",
                    name.position(),
                    "choose another name; `@@table(\"...\")` can keep the table name you want");
            return false;
        }
        typeNames.put(n, name.position());
        return true;
    }

    private Datasource datasource(Ast.Block b) {
        String provider = "postgresql";
        String url = null;
        String schemaName = null;
        for (Ast.Assignment a : uniqueKeys(b, DATASOURCE_KEYS)) {
            switch (a.key().text()) {
                case "provider" -> {
                    String p = string(a.value(), "provider = \"postgresql\"");
                    if (p != null && !p.equals("postgresql")) {
                        error(
                                "Unsupported provider `" + p + "`",
                                a.value().position(),
                                "only `postgresql` is supported in v1");
                    } else if (p != null) {
                        provider = p;
                    }
                }
                case "url" -> url = urlExpression(a.value());
                case "schema" -> schemaName = string(a.value(), "schema = \"public\"");
                default -> throw new IllegalStateException(a.key().text());
            }
        }
        return new Datasource(provider, url, schemaName);
    }

    /** Keeps the URL as its source expression; credentials are resolved from the environment at use. */
    private String urlExpression(Ast.Expr value) {
        if (value instanceof Ast.Call c && c.function().text().equals("env")) {
            if (c.args().size() == 1 && c.args().getFirst() instanceof Ast.StringLit s) {
                return "env(\"" + s.value() + "\")";
            }
            error("`env(...)` expects one string argument", c.position(), "e.g. `env(\"DATABASE_URL\")`");
            return null;
        }
        if (value instanceof Ast.StringLit s) {
            warning(
                    "The database URL is hard-coded in the schema",
                    s.position(),
                    "use `env(\"DATABASE_URL\")` so credentials never land in version control");
            return s.value();
        }
        error("Expected `env(\"VARIABLE\")` or a string", value.position(), null);
        return null;
    }

    private Generator generator(Ast.Block b) {
        String packageName = null;
        String output = DEFAULT_OUTPUT;
        String suffix = "";
        boolean repositories = true;
        boolean metamodel = false;
        boolean dtos = false;
        boolean gap = false;
        if (b != null) {
            for (Ast.Assignment a : uniqueKeys(b, GENERATOR_KEYS)) {
                Ast.Expr v = a.value();
                switch (a.key().text()) {
                    case "package" -> {
                        String p = string(v, "package = \"com.acme.shop\"");
                        if (p != null && !Names.isJavaPackage(p)) {
                            error("`" + p + "` is not a valid Java package name", v.position(), null);
                        } else if (p != null) {
                            packageName = p;
                        }
                    }
                    case "output" -> {
                        String o = string(v, "output = \"target/generated-sources/vantix\"");
                        if (o != null && outputIsSafe(o, v.position())) {
                            output = o;
                        }
                    }
                    case "entitySuffix" -> {
                        String s = string(v, "entitySuffix = \"Entity\"");
                        if (s != null && !s.matches("[A-Za-z0-9_]*")) {
                            error("`entitySuffix` may only contain letters, digits and `_`", v.position(), null);
                        } else if (s != null) {
                            suffix = s;
                        }
                    }
                    case "generateRepos", "generateRepositories" -> repositories = bool(v, repositories);
                    case "generateMetamodel" -> {
                        metamodel = bool(v, metamodel);
                        if (metamodel) {
                            warning(
                                    "`generateMetamodel` is not implemented yet and is ignored",
                                    v.position(),
                                    "the typed metamodel (`UserFields`) arrives with the query builder in Phase 3");
                        }
                    }
                    case "generateDtos" -> {
                        dtos = bool(v, dtos);
                        if (dtos) {
                            warning(
                                    "`generateDtos` is not implemented yet and is ignored",
                                    v.position(),
                                    "DTO generation is planned for Phase 5");
                        }
                    }
                    case "useGenerationGap" -> gap = bool(v, gap);
                    default -> throw new IllegalStateException(a.key().text());
                }
            }
        }
        if (packageName == null) {
            packageName = DEFAULT_PACKAGE;
            if (!entityDecls.isEmpty() || !enumDecls.isEmpty()) {
                warning(
                        "No generator package set; generating into `" + DEFAULT_PACKAGE + "`",
                        b == null ? SourcePosition.UNKNOWN : b.keyword().position(),
                        "add `generator { package = \"com.acme.app\" }`; Spring Boot only finds entities"
                                + " under your application's package");
            }
        }
        return new Generator(packageName, output, suffix, repositories, metamodel, dtos, gap);
    }

    /**
     * {@code output} is resolved against the project base directory, and generation deletes stale
     * generated files inside it, so it must be a subdirectory of the project: never absolute, never
     * escaping with {@code ..}, never the project root itself. A schema arriving in a pull request
     * cannot point the generator anywhere else.
     */
    private boolean outputIsSafe(String output, SourcePosition position) {
        String hint = "use a directory inside the project, e.g. `target/generated-sources/vantix`";
        Path path;
        try {
            path = Path.of(output).normalize();
        } catch (InvalidPathException e) {
            error("`" + output + "` is not a valid path", position, hint);
            return false;
        }
        if (path.isAbsolute() || path.getRoot() != null) {
            error("`output` must be relative to the project directory, not absolute", position, hint);
            return false;
        }
        if (path.toString().isEmpty() || path.startsWith("..")) {
            error("`output` must be a directory inside the project", position, hint);
            return false;
        }
        return true;
    }

    /** The block's assignments, reporting unknown and duplicate keys (which are dropped). */
    private List<Ast.Assignment> uniqueKeys(Ast.Block b, List<String> known) {
        List<Ast.Assignment> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Ast.Assignment a : b.assignments()) {
            String key = a.key().text();
            if (!known.contains(key)) {
                error(
                        "Unknown " + b.keyword().text() + " setting `" + key + "`",
                        a.key().position(),
                        Suggestions.didYouMean(key, known));
            } else if (!seen.add(key)) {
                error("Duplicate setting `" + key + "`", a.key().position(), null);
            } else {
                out.add(a);
            }
        }
        return out;
    }

    private EnumDecl resolveEnum(Ast.EnumDecl e) {
        List<String> values = new ArrayList<>();
        for (Ast.Name v : e.values()) {
            if (values.contains(v.text())) {
                error("Duplicate value `" + v.text() + "` in enum `" + e.name().text() + "`", v.position(), null);
            } else if (Names.JAVA_KEYWORDS.contains(v.text())) {
                error("`" + v.text() + "` is a reserved word in Java and cannot be an enum value", v.position(), null);
            } else {
                values.add(v.text());
            }
        }
        if (e.values().isEmpty()) {
            error("Enum `" + e.name().text() + "` has no values", e.name().position(), null);
        }
        return new EnumDecl(e.name().text(), values, e.name().position());
    }

    // =============================================================================================
    // Scalar fields (pass 1)
    // =============================================================================================

    private Kind kind(Ast.TypeRef t) {
        String n = t.name().text();
        if (ScalarType.fromSchemaName(n).isPresent()) {
            return Kind.SCALAR;
        }
        if (enumDecls.containsKey(n)) {
            return Kind.ENUM;
        }
        if (entityDecls.containsKey(n)) {
            return Kind.RELATION;
        }
        return Kind.UNKNOWN;
    }

    private void resolveScalars(Ast.EntityDecl e) {
        Map<String, Field> fields = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (Ast.FieldDecl f : e.fields()) {
            String n = f.name().text();
            if (!names.add(n)) {
                error(
                        "Duplicate field `" + n + "` in entity `" + e.name().text() + "`",
                        f.name().position(),
                        null,
                        List.of(new Diagnostic.Label(fieldDecl(e, n).name().position(), "first declared here")));
                continue;
            }
            if (Names.JAVA_KEYWORDS.contains(n)) {
                error(
                        "`" + n + "` is a reserved word in Java and cannot be a field name",
                        f.name().position(),
                        null);
                continue;
            }
            switch (kind(f.type())) {
                case UNKNOWN -> {
                    List<String> candidates = Stream.of(
                                    Arrays.stream(ScalarType.values()).map(ScalarType::schemaName),
                                    enumDecls.keySet().stream(),
                                    entityDecls.keySet().stream())
                            .flatMap(s -> s)
                            .toList();
                    error(
                            "Unknown type `" + f.type().name().text() + "`",
                            f.type().name().position(),
                            Suggestions.didYouMean(f.type().name().text(), candidates));
                    for (Ast.AttributeDecl a : f.attributes()) {
                        if (!FIELD_ATTRIBUTES.contains(a.name().text())) {
                            error(
                                    "Unknown attribute `" + a.display() + "`",
                                    a.name().position(),
                                    attributeHint(a.name().text()));
                        }
                    }
                }
                case SCALAR, ENUM -> {
                    Field field = resolveScalarField(e, f);
                    if (field != null) {
                        fields.put(n, field);
                    }
                }
                case RELATION -> {
                    // pass 2
                }
            }
        }
        scalars.put(e.name().text(), fields);
    }

    private Field resolveScalarField(Ast.EntityDecl e, Ast.FieldDecl f) {
        Ast.TypeRef t = f.type();
        String fieldName = f.name().text();
        String typeName = t.name().text();
        ScalarType scalar = ScalarType.fromSchemaName(typeName).orElse(null);
        FieldType type = scalar != null ? new FieldType.Scalar(scalar) : new FieldType.EnumRef(typeName);
        int errorsBefore = errorCount();

        if (t.list()) {
            error(
                    "Lists of scalar values (`" + typeName + "[]`) are not supported",
                    t.position(),
                    "model the values as their own entity with a one-to-many relation");
        }

        boolean id = false;
        boolean generated = false;
        boolean unique = false;
        boolean updatedAt = false;
        boolean ignored = false;
        Integer length = null;
        Precision precision = null;
        DefaultValue defaultValue = null;
        String columnName = Names.snakeCase(fieldName);
        boolean explicitColumn = false;
        String raw = null;
        Ast.AttributeDecl generatedAttr = null;
        Ast.AttributeDecl uniqueAttr = null;
        Ast.AttributeDecl defaultAttr = null;

        Set<String> seen = new HashSet<>();
        for (Ast.AttributeDecl a : f.attributes()) {
            String n = a.name().text();
            if (!seen.add(n)) {
                error("Duplicate attribute `" + a.display() + "` on `" + fieldName + "`", a.position(), null);
                continue;
            }
            switch (n) {
                case "id" -> id = noArgs(a);
                case "generated" -> {
                    generated = noArgs(a);
                    generatedAttr = a;
                }
                case "unique" -> {
                    unique = noArgs(a);
                    uniqueAttr = a;
                }
                case "updatedAt" -> updatedAt = noArgs(a);
                case "ignore" -> ignored = noArgs(a);
                case "default" -> {
                    defaultValue = defaultValue(a, fieldName, type, scalar);
                    defaultAttr = a;
                }
                case "length" -> {
                    Long v = intArg(a, "@length(120)");
                    if (v != null && (v < 1 || v > 10_485_760)) {
                        error(
                                "`@length` must be between 1 and 10485760",
                                a.args().getFirst().position(),
                                null);
                    } else if (v != null) {
                        length = v.intValue();
                    }
                    if (scalar != ScalarType.STRING) {
                        error(
                                "`@length` only applies to `String` fields, but `" + fieldName + "` is `" + typeName
                                        + "`",
                                a.position(),
                                scalar == ScalarType.DECIMAL ? "use `@precision(p, s)` for `Decimal`" : null);
                    }
                }
                case "precision" -> {
                    precision = precision(a);
                    if (scalar != ScalarType.DECIMAL) {
                        error(
                                "`@precision` only applies to `Decimal` fields, but `" + fieldName + "` is `" + typeName
                                        + "`",
                                a.position(),
                                null);
                    }
                }
                case "column" -> {
                    String c = stringArg(a, "@column(\"email_address\")");
                    if (c != null && !Names.isSqlIdentifier(c)) {
                        error(
                                "`" + c + "` is not a valid column name",
                                a.args().getFirst().position(),
                                "use lower_snake_case letters, digits and `_` (max 63 characters)");
                    } else if (c != null) {
                        columnName = c;
                        explicitColumn = true;
                    }
                }
                case "raw" -> raw = stringArg(a, "@raw(\"text collate \\\"C\\\"\")");
                case "relation" ->
                    error(
                            "`@relation` belongs on a relation field, but `" + fieldName + "` is a `" + typeName
                                    + "` field",
                            a.position(),
                            "put `@relation(fields: [" + fieldName + "], references: [...])` on the field whose type is"
                                    + " the related entity");
                default ->
                    error("Unknown attribute `" + a.display() + "`", a.name().position(), attributeHint(n));
            }
        }

        boolean nullable = t.optional();
        if (id && nullable) {
            error("The `@id` field `" + fieldName + "` cannot be optional", t.position(), "remove the `?`");
        }
        if (id && (scalar == null || !ID_TYPES.contains(scalar))) {
            error("`@id` fields must be `Long`, `Int`, `String` or `UUID`, not `" + typeName + "`", t.position(), null);
        }
        if (id && ignored) {
            error("The `@id` field cannot be `@ignore`d", f.name().position(), null);
        }
        if (generated && !id) {
            error(
                    "`@generated` is only valid on the `@id` field",
                    generatedAttr.position(),
                    "use `@default(...)` for a non-key default");
        } else if (generated && scalar != null && !GENERATED_ID_TYPES.contains(scalar)) {
            error(
                    "`@generated` needs a `Long`, `Int` or `UUID` id, not `" + typeName + "`",
                    generatedAttr.position(),
                    "`String` ids must be assigned by the application");
        }
        if (generated && defaultValue != null) {
            error("`@generated` and `@default` cannot be combined", generatedAttr.position(), null);
        } else if (id && defaultValue != null) {
            // A Java-side default would give every new instance an id, so Spring Data's save() would
            // treat it as existing (SELECT before every INSERT) and unsaved instances would compare equal.
            error(
                    "The `@id` field cannot have a `@default`",
                    defaultAttr.position(),
                    defaultValue instanceof DefaultValue.UuidGen
                            ? "write `" + fieldName + " UUID @id @generated`; Hibernate generates the id on persist"
                            : "use `@generated`, or assign the id in application code");
        }
        if (unique && id) {
            warning("`@unique` is redundant on the `@id` field", uniqueAttr.position(), "remove it");
        }
        if (unique && ignored) {
            error("An `@ignore`d field is not stored, so it cannot be `@unique`", uniqueAttr.position(), null);
        }
        if (updatedAt && scalar != ScalarType.INSTANT && scalar != ScalarType.LOCAL_DATE_TIME) {
            error(
                    "`@updatedAt` needs an `Instant` or `LocalDateTime` field, not `" + typeName + "`",
                    t.position(),
                    null);
        }
        if (!ignored && !explicitColumn && Names.POSTGRES_RESERVED.contains(columnName)) {
            warning(
                    "Column name `" + columnName + "` is a reserved word in PostgreSQL",
                    f.name().position(),
                    "add `@column(\"" + columnName + "_value\")` or rename the field");
        }
        if (errorCount() > errorsBefore) {
            return null;
        }
        return new Field(
                fieldName,
                columnName,
                type,
                nullable,
                id,
                generated,
                unique,
                updatedAt,
                ignored,
                length,
                precision,
                defaultValue,
                raw,
                f.name().position());
    }

    private DefaultValue defaultValue(Ast.AttributeDecl a, String fieldName, FieldType type, ScalarType scalar) {
        Ast.Expr v = singleArg(a, "@default(now())");
        if (v == null) {
            return null;
        }
        String typeName = scalar != null ? scalar.schemaName() : ((FieldType.EnumRef) type).enumName();
        String mismatch = "`@default(" + source(v) + ")` does not fit `" + fieldName + "`, which is `" + typeName + "`";
        switch (v) {
            case Ast.Call c -> {
                String fn = c.function().text();
                if (!c.args().isEmpty() && (fn.equals("now") || fn.equals("uuid"))) {
                    error("`" + fn + "()` takes no arguments", c.position(), null);
                    return null;
                }
                switch (fn) {
                    case "now" -> {
                        if (scalar != null && TEMPORAL_NOW.contains(scalar)) {
                            return new DefaultValue.Now();
                        }
                        error(
                                mismatch,
                                v.position(),
                                "`now()` needs an `Instant`, `LocalDateTime` or `LocalDate` field");
                    }
                    case "uuid" -> {
                        if (scalar == ScalarType.UUID) {
                            return new DefaultValue.UuidGen();
                        }
                        error(mismatch, v.position(), "`uuid()` needs a `UUID` field");
                    }
                    case "autoincrement", "cuid" ->
                        error(
                                "`" + fn + "()` is not a Vantix default",
                                c.function().position(),
                                "mark the `@id` field `@generated` instead");
                    default ->
                        error(
                                "Unknown default function `" + fn + "()`",
                                c.function().position(),
                                Suggestions.closest(fn, List.of("now", "uuid"))
                                        .map(s -> "did you mean `" + s + "()`?")
                                        .orElse("supported functions are `now()` and `uuid()`"));
                }
                return null;
            }
            case Ast.StringLit s -> {
                if (scalar == ScalarType.STRING || scalar == ScalarType.JSON) {
                    return new DefaultValue.Literal(s.value());
                }
                String hint = null;
                if (type instanceof FieldType.EnumRef) {
                    hint = "enum defaults are unquoted: `@default(" + s.value() + ")`";
                } else if (scalar == ScalarType.BOOLEAN) {
                    hint = "use `true` or `false`";
                }
                error(mismatch, v.position(), hint);
                return null;
            }
            case Ast.IntLit i -> {
                if (scalar == ScalarType.INT || scalar == ScalarType.LONG) {
                    BigInteger n = new BigInteger(i.text());
                    long min = scalar == ScalarType.INT ? Integer.MIN_VALUE : Long.MIN_VALUE;
                    long max = scalar == ScalarType.INT ? Integer.MAX_VALUE : Long.MAX_VALUE;
                    if (n.compareTo(BigInteger.valueOf(min)) < 0 || n.compareTo(BigInteger.valueOf(max)) > 0) {
                        error("`" + i.text() + "` is out of range for `" + typeName + "`", v.position(), null);
                        return null;
                    }
                    return new DefaultValue.Literal(i.text());
                }
                if (scalar == ScalarType.DECIMAL || scalar == ScalarType.FLOAT) {
                    return new DefaultValue.Literal(i.text());
                }
                error(mismatch, v.position(), scalar == ScalarType.STRING ? "quote it: `\"" + i.text() + "\"`" : null);
                return null;
            }
            case Ast.FloatLit fl -> {
                if (scalar == ScalarType.DECIMAL || scalar == ScalarType.FLOAT) {
                    return new DefaultValue.Literal(fl.text());
                }
                error(
                        mismatch,
                        v.position(),
                        scalar == ScalarType.INT || scalar == ScalarType.LONG ? "use a whole number" : null);
                return null;
            }
            case Ast.BoolLit b -> {
                if (scalar == ScalarType.BOOLEAN) {
                    return new DefaultValue.Literal(Boolean.toString(b.value()));
                }
                error(mismatch, v.position(), null);
                return null;
            }
            case Ast.Ident id -> {
                if (type instanceof FieldType.EnumRef ref) {
                    List<Ast.Name> values = enumDecls.get(ref.enumName()).values();
                    List<String> names = values.stream().map(Ast.Name::text).toList();
                    if (names.contains(id.name())) {
                        return new DefaultValue.Literal(id.name());
                    }
                    error(
                            "`" + id.name() + "` is not a value of enum `" + ref.enumName() + "`",
                            v.position(),
                            Suggestions.didYouMean(id.name(), names));
                    return null;
                }
                error(mismatch, v.position(), scalar == ScalarType.STRING ? "quote it: `\"" + id.name() + "\"`" : null);
                return null;
            }
            default -> {
                error("Unsupported default value `" + source(v) + "`", v.position(), null);
                return null;
            }
        }
    }

    private Precision precision(Ast.AttributeDecl a) {
        if (a.args().size() != 2) {
            error("`@precision` expects two arguments", a.position(), "e.g. `@precision(12, 2)`");
            return null;
        }
        Long p = intValue(a.args().get(0), "@precision(12, 2)");
        Long s = intValue(a.args().get(1), "@precision(12, 2)");
        if (p == null || s == null) {
            return null;
        }
        if (p < 1 || p > 1000) {
            error("Precision must be between 1 and 1000", a.args().get(0).position(), null);
            return null;
        }
        if (s < 0 || s > p) {
            error(
                    "Scale must be between 0 and the precision (" + p + ")",
                    a.args().get(1).position(),
                    null);
            return null;
        }
        return new Precision(p.intValue(), s.intValue());
    }

    // =============================================================================================
    // Relations (pass 2)
    // =============================================================================================

    private void resolveOwningRelations(Ast.EntityDecl e) {
        Map<String, Relation> out = new LinkedHashMap<>();
        Map<String, String> fkUsers = new HashMap<>();
        for (Ast.FieldDecl f : relationFields(e)) {
            Ast.AttributeDecl rel = relationAttribute(f);
            if (rel == null || f.type().list()) {
                continue;
            }
            Relation r = owningRelation(e, f, rel);
            if (r == null) {
                continue;
            }
            String fk = fkFieldName(rel);
            String other = fkUsers.putIfAbsent(fk, f.name().text());
            if (other != null) {
                error(
                        "Foreign key `" + fk + "` is already used by relation `" + other + "`",
                        f.name().position(),
                        "give each relation its own foreign-key field");
                continue;
            }
            out.put(f.name().text(), r);
        }
        owning.put(e.name().text(), out);
    }

    private List<Ast.FieldDecl> relationFields(Ast.EntityDecl e) {
        Set<String> seen = new HashSet<>();
        return e.fields().stream()
                .filter(f -> seen.add(f.name().text()))
                .filter(f -> !Names.JAVA_KEYWORDS.contains(f.name().text()))
                .filter(f -> kind(f.type()) == Kind.RELATION)
                .toList();
    }

    private static Ast.AttributeDecl relationAttribute(Ast.FieldDecl f) {
        return f.attributes().stream()
                .filter(a -> a.name().text().equals("relation"))
                .findFirst()
                .orElse(null);
    }

    private static String fkFieldName(Ast.AttributeDecl rel) {
        for (Ast.Expr arg : rel.args()) {
            if (arg instanceof Ast.Named n
                    && n.name().text().equals("fields")
                    && n.value() instanceof Ast.ListExpr l
                    && l.items().size() == 1) {
                return l.items().getFirst().text();
            }
        }
        return "";
    }

    private Relation owningRelation(Ast.EntityDecl e, Ast.FieldDecl f, Ast.AttributeDecl rel) {
        String entity = e.name().text();
        String target = f.type().name().text();
        Ast.ListExpr fieldsArg = null;
        Ast.ListExpr referencesArg = null;
        Ast.Ident onDeleteArg = null;
        Set<String> seen = new HashSet<>();
        boolean ok = true;
        for (Ast.Expr arg : rel.args()) {
            if (!(arg instanceof Ast.Named n)) {
                if (ok) {
                    error(
                            "`@relation` arguments must be named",
                            arg.position(),
                            "e.g. `@relation(fields: [userId], references: [id])`");
                }
                ok = false;
                continue;
            }
            String key = n.name().text();
            if (!RELATION_ARGS.contains(key)) {
                error(
                        "Unknown `@relation` argument `" + key + "`",
                        n.name().position(),
                        Suggestions.didYouMean(key, RELATION_ARGS));
                ok = false;
                continue;
            }
            if (!seen.add(key)) {
                error("Duplicate `@relation` argument `" + key + "`", n.name().position(), null);
                ok = false;
                continue;
            }
            switch (key) {
                case "fields", "references" -> {
                    if (n.value() instanceof Ast.ListExpr l) {
                        if (key.equals("fields")) {
                            fieldsArg = l;
                        } else {
                            referencesArg = l;
                        }
                    } else {
                        error(
                                "`" + key + "` expects a list, e.g. `[" + (key.equals("fields") ? "userId" : "id")
                                        + "]`",
                                n.value().position(),
                                null);
                        ok = false;
                    }
                }
                case "onDelete" -> {
                    if (n.value() instanceof Ast.Ident i) {
                        onDeleteArg = i;
                    } else {
                        error(
                                "`onDelete` expects one of " + String.join(", ", ON_DELETE.keySet()),
                                n.value().position(),
                                null);
                        ok = false;
                    }
                }
                default -> throw new IllegalStateException(key);
            }
        }
        if (fieldsArg == null && ok) {
            error(
                    "`@relation` on `" + f.name().text() + "` is missing `fields: [...]`",
                    rel.position(),
                    "name the foreign-key field on `" + entity + "`, e.g. `fields: ["
                            + f.name().text() + "Id]`");
            ok = false;
        }
        if (referencesArg == null && ok) {
            error(
                    "`@relation` on `" + f.name().text() + "` is missing `references: [...]`",
                    rel.position(),
                    "name the referenced field on `" + target + "`, e.g. `references: [id]`");
            ok = false;
        }
        if (!ok) {
            return null;
        }
        for (Ast.ListExpr l : List.of(fieldsArg, referencesArg)) {
            if (l.items().size() != 1) {
                error(
                        l.items().isEmpty()
                                ? "Expected exactly one field name"
                                : "Composite foreign keys are not supported yet",
                        l.position(),
                        l.items().isEmpty() ? null : "use a single field; composite keys are planned for Phase 2.5");
                ok = false;
                break;
            }
        }
        if (!ok) {
            return null;
        }

        Ast.Name fkName = fieldsArg.items().getFirst();
        Ast.Name refName = referencesArg.items().getFirst();
        Field fk = resolveFieldRef(entity, fkName, "fields");
        Field ref = resolveFieldRef(target, refName, "references");
        if (fk == null || ref == null) {
            return null;
        }
        int errorsBefore = errorCount();
        if (!ref.id() && !ref.unique()) {
            error(
                    "`" + target + "." + ref.name() + "` must be `@id` or `@unique` to be referenced by a foreign key",
                    refName.position(),
                    null);
        }
        // The fix belongs on the foreign-key declaration; the @relation that uses it is shown as context.
        SourcePosition fkType = fieldDecl(e, fk.name()).type().position();
        Diagnostic.Label usedHere = new Diagnostic.Label(fkName.position(), "used as the foreign key here");
        if (!fk.type().equals(ref.type())) {
            error(
                    "Type mismatch: `" + entity + "." + fk.name() + "` is `" + typeName(fk.type()) + "` but `" + target
                            + "." + ref.name() + "` is `" + typeName(ref.type()) + "`",
                    fkType,
                    "change `" + fk.name() + "` to `" + typeName(ref.type()) + "`",
                    List.of(usedHere));
        }
        if (fk.id()) {
            error(
                    "The foreign key `" + fk.name() + "` cannot also be the `@id` field",
                    fkName.position(),
                    "shared primary keys are not supported in v1; add a separate `" + fk.name() + "` field");
        }
        if (fk.ignored()) {
            error("The foreign key `" + fk.name() + "` cannot be `@ignore`d", fkName.position(), null);
        }
        boolean nullable = f.type().optional();
        if (nullable && !fk.nullable()) {
            error(
                    "`" + fk.name() + "` must be optional because relation `"
                            + f.name().text() + "` is optional",
                    fkType,
                    "write `" + fk.name() + " " + typeName(fk.type()) + "?`",
                    List.of(new Diagnostic.Label(f.type().position(), "optional relation")));
        } else if (!nullable && fk.nullable()) {
            error(
                    "`" + fk.name() + "` is optional but relation `" + f.name().text() + "` is required",
                    fkType,
                    "make both optional (`" + target + "?`) or both required",
                    List.of(new Diagnostic.Label(f.type().position(), "required relation")));
        }
        OnDelete onDelete = OnDelete.NO_ACTION;
        if (onDeleteArg != null) {
            OnDelete action = ON_DELETE.get(onDeleteArg.name());
            if (action == null) {
                error(
                        "Unknown `onDelete` action `" + onDeleteArg.name() + "`",
                        onDeleteArg.position(),
                        Suggestions.closest(onDeleteArg.name(), ON_DELETE.keySet())
                                .map(s -> "did you mean `" + s + "`?")
                                .orElse("expected one of " + String.join(", ", ON_DELETE.keySet())));
            } else if (action == OnDelete.SET_NULL && !fk.nullable()) {
                error(
                        "`onDelete: SetNull` needs an optional foreign key",
                        onDeleteArg.position(),
                        "write `" + fk.name() + " " + typeName(fk.type()) + "?` and `"
                                + f.name().text() + " " + target + "?`");
            } else if (action == OnDelete.SET_DEFAULT && fk.defaultValue() == null) {
                error("`onDelete: SetDefault` needs a `@default` on `" + fk.name() + "`", onDeleteArg.position(), null);
            } else {
                onDelete = action;
            }
        }
        if (errorCount() > errorsBefore) {
            return null;
        }
        RelationKind kind = fk.unique() ? RelationKind.ONE_TO_ONE : RelationKind.MANY_TO_ONE;
        return new Relation(
                f.name().text(),
                kind,
                target,
                true,
                null,
                new ForeignKey(fk.columnName(), ref.columnName(), onDelete),
                nullable,
                f.name().position());
    }

    /** Resolves a field named in {@code @relation(fields/references: [...])} on {@code entity}. */
    private Field resolveFieldRef(String entity, Ast.Name name, String argument) {
        Map<String, Field> fields = scalars.get(entity);
        Field field = fields.get(name.text());
        if (field != null) {
            return field;
        }
        Ast.EntityDecl decl = entityDecls.get(entity);
        Ast.FieldDecl declared = decl.fields().stream()
                .filter(f -> f.name().text().equals(name.text()))
                .findFirst()
                .orElse(null);
        if (declared == null) {
            String hint = Suggestions.didYouMean(
                    name.text(), fields.keySet().stream().sorted().toList());
            error(
                    "`" + argument + "` refers to unknown field `" + name.text() + "` on `" + entity + "`",
                    name.position(),
                    hint != null
                            ? hint
                            : argument.equals("fields") ? "add `" + name.text() + " Long` to `" + entity + "`" : null);
        } else if (kind(declared.type()) == Kind.RELATION) {
            error(
                    "`" + entity + "." + name.text() + "` is a relation; `" + argument + "` must name a scalar field",
                    name.position(),
                    null);
        }
        // otherwise the field itself failed to resolve and has already been reported
        return null;
    }

    private Relation inverseRelation(Ast.EntityDecl e, Ast.FieldDecl f) {
        String entity = e.name().text();
        String fieldName = f.name().text();
        Ast.TypeRef t = f.type();
        String target = t.name().text();
        Ast.EntityDecl targetDecl = entityDecls.get(target);

        List<Ast.FieldDecl> candidates = relationFields(targetDecl).stream()
                .filter(c -> c.type().name().text().equals(entity) && !c.type().list())
                .filter(c -> relationAttribute(c) != null)
                .filter(c -> !(target.equals(entity) && c.name().text().equals(fieldName)))
                .toList();

        if (candidates.isEmpty()) {
            Ast.FieldDecl otherList = !t.list()
                    ? null
                    : relationFields(targetDecl).stream()
                            .filter(c -> c.type().name().text().equals(entity)
                                    && c.type().list()
                                    && !(target.equals(entity)
                                            && c.name().text().equals(fieldName)))
                            .findFirst()
                            .orElse(null);
            if (otherList != null) {
                String here = entity + "." + fieldName;
                String there = target + "." + otherList.name().text();
                String pair = here.compareTo(there) < 0 ? here + "|" + there : there + "|" + here;
                if (!paired.containsKey(pair)) {
                    paired.put(pair, pair);
                    error(
                            "Many-to-many relations are not supported yet",
                            t.position(),
                            "add a join entity with two `@relation` fields; direct many-to-many is planned"
                                    + " for Phase 2.5");
                }
            } else if (t.list()) {
                Field id = scalars.get(entity).values().stream()
                        .filter(Field::id)
                        .findFirst()
                        .orElse(null);
                String idName = id != null ? id.name() : "id";
                String idType = id != null ? typeName(id.type()) : "Long";
                String backRef = lowerFirst(entity);
                error(
                        "Relation `" + fieldName + "` has no matching `@relation` on `" + target + "`",
                        f.name().position(),
                        "add `" + backRef + " " + entity + " @relation(fields: [" + backRef + "Id], references: ["
                                + idName + "])` and `" + backRef + "Id " + idType + "` to `" + target + "`");
            } else {
                error(
                        "Relation `" + fieldName + "` needs a foreign key on one side",
                        f.name().position(),
                        "add `@relation(fields: [" + fieldName + "Id], references: [id])` here, or a matching"
                                + " `@relation` field on `" + target + "`");
            }
            return null;
        }
        if (candidates.size() > 1) {
            error(
                    "Relation `" + fieldName + "` is ambiguous: `" + target + "` has " + candidates.size()
                            + " relations to `" + entity + "` ("
                            + String.join(
                                    ", ",
                                    candidates.stream()
                                            .map(c -> "`" + c.name().text() + "`")
                                            .toList())
                            + ")",
                    f.name().position(),
                    "multiple relations between the same two entities are not supported yet");
            return null;
        }
        Ast.FieldDecl owningDecl = candidates.getFirst();
        Relation owningRel = owning.get(target).get(owningDecl.name().text());
        if (owningRel == null) {
            return null; // the owning side failed and was reported
        }
        String owningKey = target + "." + owningDecl.name().text();
        String previous = paired.putIfAbsent(owningKey, entity + "." + fieldName);
        if (previous != null) {
            error(
                    "`" + owningKey + "` is already paired with `" + previous + "`",
                    f.name().position(),
                    "each `@relation` can have at most one inverse field");
            return null;
        }
        String fkField = fkFieldName(relationAttribute(owningDecl));
        if (t.list()) {
            if (owningRel.kind() == RelationKind.ONE_TO_ONE) {
                error(
                        "`" + target + "." + fkField + "` is `@unique`, so `" + entity + "." + fieldName
                                + "` can hold at most one `" + target + "`",
                        t.position(),
                        "write `" + fieldName + " " + target + "?`, or remove `@unique` from `" + fkField + "`");
                return null;
            }
            return new Relation(
                    fieldName,
                    RelationKind.ONE_TO_MANY,
                    target,
                    false,
                    owningDecl.name().text(),
                    null,
                    false,
                    f.name().position());
        }
        if (owningRel.kind() == RelationKind.MANY_TO_ONE) {
            error(
                    "`" + owningKey + "` is many-to-one, so `" + entity + "." + fieldName + "` must be a list",
                    t.position(),
                    "write `" + fieldName + " " + target + "[]`, or make `" + target + "." + fkField
                            + "` `@unique` for a one-to-one relation");
            return null;
        }
        if (!t.optional()) {
            error(
                    "The inverse side of a one-to-one relation must be optional",
                    t.position(),
                    "write `" + target + "?`; a `" + entity + "` can exist before its `" + target + "`");
            return null;
        }
        // Without bytecode enhancement Hibernate cannot make this side lazy: it must query to decide
        // between a proxy and null, so every loaded row costs one extra SELECT (measured in the demo
        // app's DemoApplicationTest). Legal, but never silent.
        warning(
                "`" + entity + "." + fieldName + "` cannot be loaded lazily: Hibernate runs one extra query" + " per `"
                        + entity + "` to fill it",
                f.name().position(),
                "if you rarely need it, remove this field and call `" + target + "Repository.findBy"
                        + Character.toUpperCase(fkField.charAt(0)) + fkField.substring(1) + "(...)` instead");
        return new Relation(
                fieldName,
                RelationKind.ONE_TO_ONE,
                target,
                false,
                owningDecl.name().text(),
                null,
                true,
                f.name().position());
    }

    // =============================================================================================
    // Entities
    // =============================================================================================

    private Entity resolveEntity(Ast.EntityDecl e) {
        String entity = e.name().text();
        Map<String, Field> fields = scalars.get(entity);

        List<Relation> relations = new ArrayList<>();
        for (Ast.FieldDecl f : relationFields(e)) {
            Ast.AttributeDecl rel = null;
            Set<String> seen = new HashSet<>();
            for (Ast.AttributeDecl a : f.attributes()) {
                String n = a.name().text();
                if (!seen.add(n)) {
                    error(
                            "Duplicate attribute `" + a.display() + "` on `"
                                    + f.name().text() + "`",
                            a.position(),
                            null);
                } else if (n.equals("relation")) {
                    rel = a;
                } else if (FIELD_ATTRIBUTES.contains(n)) {
                    error(
                            "`" + a.display() + "` cannot be used on relation field `"
                                    + f.name().text() + "`",
                            a.position(),
                            "column attributes go on the foreign-key field");
                } else {
                    error("Unknown attribute `" + a.display() + "`", a.name().position(), attributeHint(n));
                }
            }
            if (f.type().list() && f.type().optional()) {
                error(
                        "A list relation cannot be optional",
                        f.type().position(),
                        "write `" + f.type().name().text() + "[]`; an empty list means no related rows");
                continue;
            }
            if (rel != null && f.type().list()) {
                error(
                        "`@relation` belongs on the single-valued side of a relation",
                        rel.position(),
                        "put it on the `" + f.type().name().text() + "` field that holds the foreign key");
                continue;
            }
            Relation r = rel != null ? owning.get(entity).get(f.name().text()) : inverseRelation(e, f);
            if (r != null) {
                relations.add(r);
            }
        }

        String tableName = Names.snakeCase(entity);
        SourcePosition tablePos = e.name().position();
        boolean explicitTable = false;
        List<Index> indexes = new ArrayList<>();
        Set<String> seenAttrs = new HashSet<>();
        for (Ast.AttributeDecl a : e.attributes()) {
            String n = a.name().text();
            if (n.equals("table") && !seenAttrs.add(n)) {
                error("Duplicate `@@table` on `" + entity + "`", a.position(), null);
                continue;
            }
            switch (n) {
                case "table" -> {
                    String t = stringArg(a, "@@table(\"users\")");
                    if (t != null && !Names.isSqlIdentifier(t)) {
                        error(
                                "`" + t + "` is not a valid table name",
                                a.args().getFirst().position(),
                                "use lower_snake_case letters, digits and `_` (max 63 characters)");
                    } else if (t != null) {
                        tableName = t;
                        tablePos = a.position();
                        explicitTable = true;
                    }
                }
                case "id", "schema" -> {
                    // rejected (and reported) by the parser; kept in the AST only to avoid follow-up errors
                }
                case "index", "unique" -> {
                    Index index = index(e, a, fields);
                    if (index != null) {
                        indexes.add(index);
                    }
                }
                default ->
                    error(
                            "Unknown entity attribute `" + a.display() + "`",
                            a.name().position(),
                            Suggestions.closest(n, ENTITY_ATTRIBUTES)
                                    .map(s -> "did you mean `@@" + s + "`?")
                                    .orElse(null));
            }
        }
        if (!explicitTable && Names.POSTGRES_RESERVED.contains(tableName)) {
            warning(
                    "Table name `" + tableName + "` is a reserved word in PostgreSQL",
                    tablePos,
                    "add `@@table(\"" + tableName + "s\")`");
        }

        List<Field> ids = fields.values().stream().filter(Field::id).toList();
        boolean idDeclared = e.fields().stream().anyMatch(f -> f.attributes().stream()
                        .anyMatch(a -> a.name().text().equals("id")))
                || e.attributes().stream().anyMatch(a -> a.name().text().equals("id"));
        if (!idDeclared) {
            error("Entity `" + entity + "` has no `@id` field", e.name().position(), "add `id Long @id @generated`");
        } else if (ids.size() > 1) {
            error(
                    "Entity `" + entity + "` has more than one `@id` field",
                    ids.get(1).position(),
                    "use a single `@id`; composite keys are planned for Phase 2.5");
        }

        Map<String, String> columns = new HashMap<>();
        for (Field field : fields.values()) {
            if (field.ignored()) {
                continue;
            }
            String other = columns.putIfAbsent(field.columnName(), field.name());
            if (other != null) {
                error(
                        "Fields `" + other + "` and `" + field.name() + "` both map to column `" + field.columnName()
                                + "`",
                        field.position(),
                        "use `@column(\"...\")` to give one of them another name",
                        List.of(new Diagnostic.Label(fields.get(other).position(), "`" + other + "` is here")));
            }
        }

        return new Entity(
                entity,
                tableName,
                null,
                List.copyOf(fields.values()),
                relations,
                indexes,
                e.name().position());
    }

    private Index index(Ast.EntityDecl e, Ast.AttributeDecl a, Map<String, Field> fields) {
        String example = a.display() + "([createdAt])";
        if (a.args().isEmpty() || !(a.args().getFirst() instanceof Ast.ListExpr list)) {
            error("`" + a.display() + "` expects a list of fields", a.position(), "e.g. `" + example + "`");
            return null;
        }
        String name = null;
        for (Ast.Expr extra : a.args().subList(1, a.args().size())) {
            if (extra instanceof Ast.Named n
                    && n.name().text().equals("name")
                    && n.value() instanceof Ast.StringLit s) {
                if (!Names.isSqlIdentifier(s.value())) {
                    error("`" + s.value() + "` is not a valid index name", s.position(), null);
                    return null;
                }
                name = s.value();
            } else {
                error(
                        "Unexpected argument to `" + a.display() + "`",
                        extra.position(),
                        "only an optional `name: \"...\"` may follow the field list");
                return null;
            }
        }
        if (list.items().isEmpty()) {
            error("`" + a.display() + "` needs at least one field", list.position(), "e.g. `" + example + "`");
            return null;
        }
        List<String> names = new ArrayList<>();
        boolean ok = true;
        for (Ast.Name item : list.items()) {
            Field field = fields.get(item.text());
            if (names.contains(item.text())) {
                error("Field `" + item.text() + "` is listed twice", item.position(), null);
                ok = false;
            } else if (field != null && !field.ignored()) {
                names.add(item.text());
            } else if (field != null) {
                error("`" + item.text() + "` is `@ignore`d and has no column to index", item.position(), null);
                ok = false;
            } else {
                Ast.FieldDecl declared = e.fields().stream()
                        .filter(f -> f.name().text().equals(item.text()))
                        .findFirst()
                        .orElse(null);
                if (declared != null && kind(declared.type()) == Kind.RELATION) {
                    Ast.AttributeDecl rel = relationAttribute(declared);
                    String fk = rel != null ? fkFieldName(rel) : "";
                    error(
                            "`" + item.text() + "` is a relation and has no column of its own",
                            item.position(),
                            fk.isEmpty() ? null : "index its foreign key `" + fk + "` instead");
                } else if (declared == null) {
                    error(
                            "Unknown field `" + item.text() + "` in `" + a.display() + "`",
                            item.position(),
                            Suggestions.didYouMean(
                                    item.text(),
                                    fields.keySet().stream().sorted().toList()));
                }
                ok = false;
            }
        }
        return ok ? new Index(name, names, a.name().text().equals("unique"), a.position()) : null;
    }

    private void checkTableNames(List<Entity> entities) {
        Map<String, String> tables = new HashMap<>();
        for (Entity e : entities) {
            String other = tables.putIfAbsent(e.tableName(), e.name());
            if (other != null) {
                error(
                        "Entities `" + other + "` and `" + e.name() + "` both map to table `" + e.tableName() + "`",
                        e.position(),
                        "use `@@table(\"...\")` to give one of them another name");
            }
        }
    }

    /** Generated type names (entities, bases, repositories, enums) must not collide in one package. */
    private void checkGeneratedClassNames(Generator generator) {
        Map<String, String> owners = new HashMap<>();
        for (String en : enumDecls.keySet()) {
            owners.put(en, "enum `" + en + "`");
        }
        for (Ast.EntityDecl e : entityDecls.values()) {
            String n = e.name().text();
            String className = n + generator.entitySuffix();
            Map<String, String> generated = new LinkedHashMap<>();
            generated.put(className, "entity `" + n + "`");
            if (generator.useGenerationGap()) {
                generated.put(className + "Base", "the base class of `" + n + "`");
            }
            if (generator.generateRepositories()) {
                generated.put(n + "Repository", "the repository of `" + n + "`");
            }
            generated.forEach((g, what) -> {
                String other = owners.putIfAbsent(g, what);
                if (other != null) {
                    error(
                            "Generated class name `" + g + "` is used twice: by " + other + " and by " + what,
                            e.name().position(),
                            "rename one of them");
                }
            });
        }
    }

    // =============================================================================================
    // Argument helpers
    // =============================================================================================

    private boolean noArgs(Ast.AttributeDecl a) {
        if (!a.args().isEmpty()) {
            error("`" + a.display() + "` takes no arguments", a.position(), "write `" + a.display() + "`");
        }
        return true;
    }

    private Ast.Expr singleArg(Ast.AttributeDecl a, String example) {
        if (a.args().size() != 1) {
            error(
                    "`" + a.display() + "` expects exactly one argument, got "
                            + a.args().size(),
                    a.position(),
                    "e.g. `" + example + "`");
            return null;
        }
        return a.args().getFirst();
    }

    private String stringArg(Ast.AttributeDecl a, String example) {
        Ast.Expr v = singleArg(a, example);
        return v == null ? null : string(v, example);
    }

    private Long intArg(Ast.AttributeDecl a, String example) {
        Ast.Expr v = singleArg(a, example);
        return v == null ? null : intValue(v, example);
    }

    private String string(Ast.Expr v, String example) {
        if (v instanceof Ast.StringLit s) {
            return s.value();
        }
        error("Expected a string, found `" + source(v) + "`", v.position(), "e.g. `" + example + "`");
        return null;
    }

    private Long intValue(Ast.Expr v, String example) {
        if (v instanceof Ast.IntLit i) {
            try {
                return Long.parseLong(i.text());
            } catch (NumberFormatException e) {
                error("`" + i.text() + "` is too large", v.position(), null);
                return null;
            }
        }
        error("Expected a whole number, found `" + source(v) + "`", v.position(), "e.g. `" + example + "`");
        return null;
    }

    private boolean bool(Ast.Expr v, boolean fallback) {
        if (v instanceof Ast.BoolLit b) {
            return b.value();
        }
        error("Expected `true` or `false`, found `" + source(v) + "`", v.position(), null);
        return fallback;
    }

    /** Re-renders an expression the way it would be written, for messages. */
    private static String source(Ast.Expr v) {
        return switch (v) {
            case Ast.StringLit s -> "\"" + s.value() + "\"";
            case Ast.IntLit i -> i.text();
            case Ast.FloatLit f -> f.text();
            case Ast.BoolLit b -> Boolean.toString(b.value());
            case Ast.Ident i -> i.name();
            case Ast.ListExpr l ->
                "[" + String.join(", ", l.items().stream().map(Ast.Name::text).toList()) + "]";
            case Ast.Call c ->
                c.function().text() + "("
                        + String.join(
                                ", ",
                                c.args().stream().map(SchemaAnalyzer::source).toList())
                        + ")";
            case Ast.Named n -> n.name().text() + ": " + source(n.value());
        };
    }

    private static String typeName(FieldType t) {
        return switch (t) {
            case FieldType.Scalar s -> s.type().schemaName();
            case FieldType.EnumRef r -> r.enumName();
        };
    }

    private static String attributeHint(String name) {
        return Suggestions.closest(name, FIELD_ATTRIBUTES)
                .map(s -> "did you mean `@" + s + "`?")
                .orElse(null);
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private int errorCount() {
        return (int) diagnostics.stream().filter(Diagnostic::isError).count();
    }

    private void error(String message, SourcePosition position, String suggestion) {
        diagnostics.add(new Diagnostic(Severity.ERROR, message, position, suggestion));
    }

    private void error(String message, SourcePosition position, String suggestion, List<Diagnostic.Label> related) {
        diagnostics.add(new Diagnostic(Severity.ERROR, message, position, suggestion, related));
    }

    /** The first declaration of {@code name} in {@code e}. */
    private static Ast.FieldDecl fieldDecl(Ast.EntityDecl e, String name) {
        return e.fields().stream()
                .filter(f -> f.name().text().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private void warning(String message, SourcePosition position, String suggestion) {
        diagnostics.add(new Diagnostic(Severity.WARNING, message, position, suggestion));
    }
}
