# The Vantix Schema Language (`schema.vx`) — Grammar v1

This is the normative grammar for **Phase 1**. It is deliberately small (see
`IMPLEMENTATION_PLAN.md` §"SDL feature creep"). Every construct here costs parser + validator +
codegen + differ + SQL + docs, so the language says *no* by default.

**In scope for v1:** entities, scalar fields, enums, single-field `@id`, one-to-one and one-to-many
relations, field/entity attributes, indexes.

**Deferred (parser recognizes and rejects with a clear diagnostic):**

- `@@id([...])` composite primary keys — Phase 2.5 (D6).
- many-to-many relations — Phase 2.5 (D7).
- `@@schema("...")` multi-schema placement — later (D14); the `schemaName` slot exists in the model
  but the attribute is rejected as "not yet supported".
- the `migrations { ... }` block — Phase 2, with `vantix migrate`.
- composite foreign keys (`fields: [a, b]`) — Phase 2.5, with composite primary keys.

## Notation

EBNF-ish: `{ x }` = zero or more, `[ x ]` = optional, `|` = alternation, `"x"` = literal,
`UPPER` = lexical token. Comments in source are `// line` or `/* block */` and are not significant.

## Lexical grammar

```
IDENT     = LETTER { LETTER | DIGIT | "_" } ;
STRING    = '"' { char | escape } '"' ;          (* on one line *)
escape    = '\' ( '"' | '\' | "n" | "t" ) ;      (* any other escape is an error *)
INT       = [ "-" ] DIGIT { DIGIT } ;
FLOAT     = [ "-" ] DIGIT { DIGIT } "." DIGIT { DIGIT } ;
            (* no leading zeros (`010` would be octal in the generated Java), no `.5` or `1.`,
               no exponents (`1e5`) and no suffixes (`10L`): each is an error with the fix *)
BOOL      = "true" | "false" ;
LETTER    = "A".."Z" | "a".."z" | "_" ;
DIGIT     = "0".."9" ;
```

Keywords (contextual): `datasource`, `generator`, `enum`, `entity`, `env`, `true`, `false`.
Punctuation: `{ } ( ) [ ] , : = ? @ @@ .` and the list marker `[]`.

## Syntactic grammar

```
schema         = { topLevelDecl } EOF ;

topLevelDecl   = datasourceBlock
               | generatorBlock
               | enumDecl
               | entityDecl ;

datasourceBlock = "datasource" "{" { assignment } "}" ;
generatorBlock  = "generator"  "{" { assignment } "}" ;
assignment      = IDENT "=" value ;
value           = STRING | INT | FLOAT | BOOL | envCall ;
envCall         = "env" "(" STRING ")" ;

enumDecl        = "enum" IDENT "{" { IDENT } "}" ;   (* whitespace-separated; commas are an error *)

entityDecl      = "entity" IDENT "{" { entityMember } "}" ;
entityMember    = fieldDecl | entityAttribute ;

fieldDecl       = IDENT typeRef { fieldAttribute } ;
                  (* a field's attributes must be on the same line as its name *)
typeRef         = IDENT [ "?" ] [ "[" "]" ] ;
                  (* IDENT names a scalar type, an enum, or a related entity.
                     "?" = nullable/optional. "[]" = to-many relation. *)

fieldAttribute  = "@"  IDENT [ "(" [ argList ] ")" ] ;
entityAttribute = "@@" IDENT [ "(" [ argList ] ")" ] ;

argList         = arg { "," arg } ;
arg             = namedArg | listArg | value | IDENT | callArg ;
namedArg        = IDENT ":" arg ;
listArg         = "[" [ IDENT { "," IDENT } ] "]" ;
callArg         = IDENT "(" [ argList ] ")" ;   (* e.g. now(), uuid() *)
```

## Scalar types (`typeRef` IDENT that is not an enum or entity)

| Vantix type     | Java type       | PostgreSQL type    |
| --------------- | --------------- | ------------------ |
| `String`        | `String`        | `varchar(n)`/`text`|
| `Int`           | `Integer`       | `integer`          |
| `Long`          | `Long`          | `bigint`           |
| `Decimal`       | `BigDecimal`    | `numeric(p,s)`     |
| `Float`         | `Double`        | `double precision` |
| `Boolean`       | `Boolean`       | `boolean`          |
| `Instant`       | `Instant`       | `timestamptz`      |
| `LocalDate`     | `LocalDate`     | `date`             |
| `LocalDateTime` | `LocalDateTime` | `timestamp`        |
| `UUID`          | `UUID`          | `uuid`             |
| `Json`          | `String`        | `jsonb`            |
| `Bytes`         | `byte[]`        | `bytea`            |

`T?` = nullable column; `T[]` = to-many relation (no column on this side).

## Field attributes (v1)

| Attribute            | Meaning                                                        |
| -------------------- | ------------------------------------------------------------- |
| `@id`                | Primary key (exactly one per entity in v1).                   |
| `@generated`         | Identity/sequence generation.                                 |
| `@unique`            | Unique constraint + derived `findByX`.                        |
| `@default(v)`        | Column default: `now()`, `uuid()`, an enum value, or literal. |
| `@length(n)`         | `varchar(n)` for `String`.                                    |
| `@precision(p, s)`   | `numeric(p,s)` for `Decimal`.                                 |
| `@column("name")`    | Column name override.                                         |
| `@updatedAt`         | Auto-managed update timestamp.                                |
| `@ignore`            | Present in Java, absent from the DB (`@Transient`).           |
| `@raw("...")`        | Escape hatch: emit this column DDL verbatim.                  |
| `@relation(...)`     | Owning side of an association (see below).                    |

`@relation(fields: [fkField], references: [targetField], onDelete: Restrict)` marks the owning side
of a 1-1 or N-1 association. `onDelete` ∈ `NoAction | Restrict | Cascade | SetNull | SetDefault`.

## Entity attributes (v1)

| Attribute          | Meaning                       |
| ------------------ | ----------------------------- |
| `@@table("name")`  | Table name override.          |
| `@@index([a, b])`  | (Composite) index. Optional `name: "..."`.  |
| `@@unique([a, b])` | (Composite) unique constraint. Optional `name: "..."`. |
| `@@id([a, b])`     | **Rejected** in v1 (D6).      |
| `@@schema("name")` | **Rejected** in v1 (D14).     |

## Example (the canonical v1 sample)

```
datasource {
  provider = "postgresql"
  url      = env("DATABASE_URL")
}

generator {
  package = "com.acme.shop"
  output  = "target/generated-sources/vantix"
}

enum OrderStatus { PENDING PAID SHIPPED CANCELLED }

entity User {
  id        Long    @id @generated
  email     String  @unique @length(180)
  name      String  @length(120)
  createdAt Instant @default(now())
  orders    Order[]
  profile   Profile?

  @@index([createdAt])
  @@table("users")
}

entity Profile {
  id     Long    @id @generated
  bio    String? @length(500)
  user   User    @relation(fields: [userId], references: [id])
  userId Long    @unique
}

entity Order {
  id       Long        @id @generated
  status   OrderStatus @default(PENDING)
  total    Decimal     @precision(12, 2)
  placedAt Instant     @default(now())
  user     User        @relation(fields: [userId], references: [id], onDelete: Restrict)
  userId   Long

  @@index([userId, placedAt])
  @@table("orders")   // `order` is a reserved word in PostgreSQL
}
```

## Semantics (v1)

What semantic analysis (`vx-core`'s `SchemaAnalyzer`) checks and decides, beyond the grammar. Every
rule below has a case in `vx-core/src/test/resources/diagnostics/`.

**Names.**
- Tables default to the snake_case entity name (`OrderItem` becomes `order_item`); columns to the
  snake_case field name (`createdAt` becomes `created_at`). `@@table` / `@column` override them and
  must be lower_snake_case, at most 63 characters.
- PostgreSQL reserved words (`user`, `order`, `group`, ...) as table or column names are a
  **warning** that suggests an override.
- Entity, enum and field names must be legal Java names that do not shadow `java.lang` types used by
  generated code (`Record`, `Object`, ...). Generated class names (`User`, `UserBase`,
  `UserRepository`) must not collide.
- Index and unique-constraint names default to PostgreSQL's convention: `<table>_<columns>_idx` and
  `<table>_<columns>_key`, shortened with a stable hash past 63 characters.

**Keys.** Exactly one `@id` per entity, of type `Long`, `Int`, `String` or `UUID`, never optional.
`@generated` is only valid on a `Long`/`Int` id (identity) or a `UUID` id. An `@id` may not have a
`@default`: the generated entity would start with an id, so Spring Data's `save()` would treat every
new instance as existing (a `SELECT` before each `INSERT`) and unsaved instances would compare equal.
Use `@generated` (for `UUID`, Hibernate generates it on persist).

**Relations.** A relation field's type names an entity.
- The side with `@relation(fields: [fk], references: [ref])` **owns** the foreign key. `fk` must be a
  scalar field on the same entity, `ref` an `@id` or `@unique` field on the target, and the two must
  have the same type. `fk` is optional exactly when the relation is.
- A `@unique` foreign key makes the relation **one-to-one**; otherwise it is **many-to-one**.
- The other side (no `@relation`) is the **inverse**: `T[]` for one-to-many, `T?` for one-to-one
  (it must be optional). Its `mappedBy` is inferred from the single owning relation pointing back.
- An inverse one-to-one is a **warning**: without bytecode enhancement Hibernate cannot load it
  lazily, so every loaded row costs one extra query (measured in the demo app). Querying the owning
  side's repository (`findByUserId`) when needed avoids it.
- `onDelete` defaults to `NoAction`. `SetNull` needs an optional foreign key; `SetDefault` needs a
  `@default` on it.
- Two relations between the same pair of entities, and many-to-many, are rejected in v1.

**Defaults.** `@default` must fit the field's type: `now()` for `Instant`/`LocalDateTime`/`LocalDate`,
`uuid()` for `UUID`, a string for `String`/`Json`, a whole number for `Int`/`Long` (range-checked)
or `Decimal`/`Float`, a decimal for `Decimal`/`Float`, `true`/`false` for `Boolean`, and an unquoted
value for an enum. Generated entities initialise the field to the same value, so a new instance
already holds the database default.

**Generator settings.** `package` (default `vantix.generated`, with a warning: Spring Boot only
scans entities under your application's package), `output` (default
`target/generated-sources/vantix`; resolved against the project base directory and required to stay
inside it — absolute paths and `..` escapes are errors), `entitySuffix`, `generateRepos`,
`useGenerationGap`.
`generateMetamodel` (Phase 3) and `generateDtos` (Phase 5) are accepted but ignored, with a warning.
