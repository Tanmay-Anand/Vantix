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

## Notation

EBNF-ish: `{ x }` = zero or more, `[ x ]` = optional, `|` = alternation, `"x"` = literal,
`UPPER` = lexical token. Comments in source are `// line` or `/* block */` and are not significant.

## Lexical grammar

```
IDENT     = LETTER { LETTER | DIGIT | "_" } ;
STRING    = '"' { any-char-except-quote } '"' ;
INT       = [ "-" ] DIGIT { DIGIT } ;
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
value           = STRING | INT | BOOL | envCall ;
envCall         = "env" "(" STRING ")" ;

enumDecl        = "enum" IDENT "{" { IDENT } "}" ;

entityDecl      = "entity" IDENT "{" { entityMember } "}" ;
entityMember    = fieldDecl | entityAttribute ;

fieldDecl       = IDENT typeRef { fieldAttribute } ;
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
| `@@index([a, b])`  | (Composite) index.            |
| `@@unique([a, b])` | (Composite) unique constraint.|
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
}
```
