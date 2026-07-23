# Vantix — Full Project Analysis & Implementation Plan

*A Prisma-style developer-experience layer for Spring Boot, built on top of Hibernate and Flyway — not a replacement for them.*

---

## 1. Project Analysis

### 1.1 Purpose

Vantix is a **developer-experience (DX) toolchain**, not an ORM. The runtime persistence work stays with Hibernate/Spring Data JPA; the migrations stay with Flyway. What you're building is the layer that today's Spring developer assembles by hand from five or six disconnected tools (Lombok, MapStruct, JPA Buddy, Flyway, springdoc, hand-written Criteria queries) — unified behind a single schema file and a single CLI.

The core value proposition, stated precisely: **"One declarative schema file is the source of truth. Everything derivable from it — entity classes, repositories, migrations, a typed query API, a data browser — is generated, checked, and kept in sync automatically."**

### 1.2 Core functional pillars

1. **Schema Definition Language (SDL)** — `schema.vx`, a small declarative language describing entities, fields, types, constraints, relations, indexes.
2. **Code generation** — from the parsed schema, emit Java source: JPA entities, Spring Data repositories, optionally DTOs/mappers/controllers.
3. **Migration generation** — diff the current schema against the last-migrated state and emit standard Flyway `V__` SQL files.
4. **Type-safe query builder** — a fluent API backed by a generated static metamodel, executing through JPA Criteria/Hibernate under the hood.
5. **CLI** — `init`, `generate`, `migrate`, `validate`, `db pull`, `db push`, `studio`, `doctor`.
6. **Studio** — a local web UI for browsing and editing data.
7. **Error-message translation** — intercepting common Hibernate exceptions and re-presenting them with diagnosis and fixes.

### 1.3 The real technical challenges (ranked by difficulty)

1. **Schema diffing / migration generation (hardest).** Diffing two schema versions sounds simple until you hit *rename ambiguity*: if `name` disappears and `fullName` appears, is that a rename (ALTER … RENAME COLUMN, data preserved) or drop+add (data lost)? No algorithm can know. Prisma solves this with an interactive prompt and a migration-history snapshot; you'll need the same. Add destructive-change detection, ordering of dependent DDL (FKs before/after tables), and per-database SQL dialect differences, and this is the single largest engineering effort in the project.
2. **Codegen that coexists with user code.** Java has no partial classes. If users can never touch generated entities, the tool feels rigid; if they can, regeneration clobbers their edits. You need a deliberate strategy (see §5.3 — Generation Gap pattern + generated-sources directory).
3. **Query DSL design.** True compile-time type safety in Java requires *generated code* (a static metamodel, like jOOQ/QueryDSL/JPA's `User_`). Lambda/method-reference tricks (`u -> u.email()`) look nice but rely on fragile reflection over `SerializedLambda` and give worse IDE support. This is a design decision with real trade-offs (§5.4).
4. **Parser with excellent error messages.** "Great error messages" is a headline feature — which means the parser can't be an afterthought. Error recovery, precise source locations, "did you mean…" suggestions.
5. **Build-tool integration.** Generated code must exist *before* the user's code compiles, so you need a Maven plugin (and later Gradle) wiring generation into `generate-sources`, not just a standalone CLI.
6. **Runtime error translation.** Catching `LazyInitializationException` and rewriting it helpfully requires a Spring Boot starter with an exception post-processor and enough context (which entity, which association) to say something useful.

### 1.4 Honest market assessment (you asked for tradeoff honesty, so here it is)

You are not entering an empty niche — parts of this already exist, and you should know each one before designing:

- **JPA Buddy** (commercial, IntelliJ plugin) — does schema-aware entity generation and Flyway/Liquibase diff-based migration generation. This is your closest competitor for pillars 2–3.
- **JHipster JDL** — a schema DSL that generates entities, repositories, DTOs, mappers, controllers. Very close to your SDL idea, but coupled to the full JHipster generator.
- **Jakarta Data + Hibernate Data Repositories** (Hibernate 6.6+/7) — <cite index="14-1">compile-time-generated repository implementations with complete type checking of queries, reporting mistakes as understandable compiler errors</cite>. The Hibernate team is actively attacking the "type-safe, better-checked data access" pain point at the standards level. Note that <cite index="16-1">Jakarta Data repositories are stateless, built on Hibernate's StatelessSession, and deactivate dirty checking and implicit lazy loading</cite> — a philosophical shift you should study, because it validates the "less ORM magic" direction.
- **jOOQ** — the gold standard for generated, type-safe query DSLs in Java (database-first rather than schema-first).
- **Liquibase / Flyway Desktop** — schema diffing exists commercially.
- **Bootify, Telosys** — schema-driven Spring code generators.

**Implication:** the differentiator is not any single pillar — each exists somewhere — but the *unification* (one schema file, one CLI, one mental model) and the *quality of the experience* (error messages, docs, zero-config). That's genuinely valuable and genuinely absent from the ecosystem. But it also means: as an adoption play this is a long shot against mature incumbents; as a **learning and portfolio project it is close to ideal**, because it forces you through parsing, code generation, build tooling, database internals, and API design — exactly the topics that distinguish senior engineers in interviews.

### 1.5 Assumptions and missing requirements (decide these before coding)

- **Target database:** assume **PostgreSQL only** for v1. Every additional dialect multiplies migration-generation effort.
- **Java baseline:** Java 21 (your stack; records, pattern matching, and text blocks all help).
- **Spring Boot baseline:** Boot 3.5.x initially (largest installed base); verify against Boot 4/Framework 7 once stable in your test matrix.
- **Build tool:** Maven plugin first (your ecosystem), Gradle later.
- **Scope of generation for MVP:** entities + repositories only. DTOs/mappers/controllers/OpenAPI are Phase-2+ — generating controllers is where code generators historically become opinionated and brittle.
- **Kotlin support:** explicitly out of scope for v1.
- **Not decided by the idea doc:** licensing (Apache-2.0 recommended), whether `db push` (prototype mode, no migration files) ships in v1 (recommend: no), how users customize generated code (must be decided in Phase 1 — see §5.3).

---

## 2. Architecture & Design

### 2.1 Recommended architecture: layered toolchain over unmodified Hibernate

```
        ┌────────────────────────────────────────────┐
        │              vantix CLI               │  picocli
        │  init · generate · migrate · validate ·     │
        │  db pull · studio · doctor                  │
        └──────┬──────────┬──────────┬───────────────┘
               │          │          │
        ┌──────▼───┐ ┌────▼─────┐ ┌──▼─────────┐
        │ vx-core  │ │ vx-codegen│ │ vx-migrate │
        │ lexer/   │ │ JavaPoet  │ │ diff engine│
        │ parser/  │ │ templates │ │ SQL render │
        │ AST/     │ └────┬─────┘ └──┬─────────┘
        │ semantic │      │           │ emits Flyway V__*.sql
        │ analysis │      │ emits .java into
        └──────────┘      │ target/generated-sources
                          ▼
        ┌────────────────────────────────────────────┐
        │        User's Spring Boot application       │
        │  vx-runtime (query builder + error xlate)   │  thin JAR
        │  Spring Data JPA → Hibernate → JDBC → PG    │  untouched
        └────────────────────────────────────────────┘
```

**Why this shape:**

- **Compile-time tool, thin runtime.** Everything heavy (parsing, diffing, generating) happens at build time. The only runtime artifact is `vx-runtime`: the query-builder execution layer and the error-translation starter. This means zero performance risk, zero interference with Hibernate's proxies/caching/transactions, and users can eject at any time — the generated code is plain readable Spring code. "Ejectability" is a killer trust feature for adoption; make it a stated guarantee.
- **Flyway files as the migration contract.** By emitting standard `V__*.sql` files instead of a proprietary migration runtime, you inherit Flyway's execution, checksums, CI/CD story, and — critically — user trust. Your tool *writes* migrations; Flyway *runs* them. Prisma made the analogous choice (generated SQL, editable before execution) and it's widely considered correct.
- **Hibernate unmodified.** All the reasons in your idea doc, plus one more: Hibernate 7's own direction (Jakarta Data, compile-time checking) means fighting them is fighting the tide, while sitting above them lets you absorb their improvements for free.

### 2.2 Module / repository structure (Maven multi-module monorepo)

```
vantix/
├── pom.xml                        # parent
├── vx-core/                       # SDL lexer, parser, AST, semantic analysis, schema model
│   └── src/main/java/dev/vantix/core/
│       ├── lexer/                 # Token, Lexer, SourcePosition
│       ├── parser/                # recursive-descent Parser, error recovery
│       ├── ast/                   # EntityDecl, FieldDecl, RelationDecl, AttributeDecl
│       ├── semantic/              # type checking, relation resolution, Diagnostics
│       └── model/                 # resolved Schema model (post-analysis, what everything else consumes)
├── vx-codegen/                    # Schema model → Java source (JavaPoet)
│       ├── entity/  repository/  metamodel/  dto/ (later)
├── vx-migrate/                    # Schema diff → SQL
│       ├── snapshot/              # serialize/deserialize schema state (JSON)
│       ├── diff/                  # SchemaDiffer → List<SchemaChange>
│       ├── sql/                   # PostgresRenderer: SchemaChange → DDL
│       └── introspect/            # JDBC DatabaseMetaData / information_schema reader (db pull)
├── vx-runtime/                    # ships in user's app: query builder execution, error translation
├── vx-cli/                        # picocli commands; depends on core/codegen/migrate
├── vx-maven-plugin/               # binds generate to generate-sources phase
├── vx-studio/                     # Spring Boot app: REST + React UI (Phase 4)
├── vx-spring-boot-starter/        # auto-config: error translator, query-builder beans
├── examples/demo-app/             # dogfood application, also the integration-test bed
└── docs/                          # docs site source from day one
```

**Why multi-module:** each pillar has different dependencies (the CLI shouldn't drag Spring; the runtime must be tiny; codegen needs JavaPoet), different release cadences, and clean seams for testing. It also mirrors how Prisma, jOOQ, and Flyway themselves are organized — and it's directly transferable DDD-style boundary thinking from your Builder-CRM work, just applied to a tool instead of a business domain.

---

## 3. Implementation Roadmap

### Phase 0 — Spike (1–2 weekends)
Hand-write, with no abstractions, the crappiest possible version of the pipeline end-to-end: parse a hardcoded two-entity schema with regex-level parsing, print a `CREATE TABLE`, emit one entity class as a string. Purpose: feel every stage before designing any of them. Throw this code away.

### Phase 1 — MVP: `schema.vx → validate → generate` (the heart)
- SDL grammar v1: entities, scalar fields (`String`, `Long`, `Int`, `Boolean`, `Decimal`, `Instant`, `LocalDate`, `UUID`, enums), attributes (`@id`, `@generated`, `@unique`, `@default`, `@column`, `@length`, `@nullable`), 1-1 / 1-N / N-N relations.
- Hand-written lexer + recursive-descent parser with **position-tracked diagnostics** ("line 12: unknown type `Strng` — did you mean `String`?").
- Semantic analysis: duplicate names, unresolved relation targets, missing `@id`, invalid attribute combinations.
- Codegen: JPA entity + Spring Data `JpaRepository` per entity, into `target/generated-sources/vantix`.
- CLI: `vantix init | validate | generate`.
- Maven plugin wrapping `generate`.
- Dogfood: `examples/demo-app` compiles and boots against the generated code.

**MVP definition of done:** a stranger can clone a Boot app, write `schema.vx`, run `mvn compile`, and have working entities/repositories — with error messages good enough that they never need to read your source.

### Phase 2 — Migrations: `vantix migrate dev`
- Schema snapshot format (JSON serialization of the resolved model) stored in `vantix/migrations/…/snapshot.json`.
- Differ: snapshot vs current model → typed `SchemaChange` list (CreateTable, AddColumn, AlterColumnType, AddForeignKey, DropX…).
- Interactive rename resolution ("column `name` removed and `fullName` added — rename? [y/n]").
- Postgres DDL renderer → Flyway-named `V<n>__<slug>.sql`.
- Destructive-change guard: refuse to emit `DROP` without `--allow-destructive` (or generate it commented out).
- `db pull`: introspect an existing Postgres DB via `information_schema` → emit `schema.vx` (this is also your adoption on-ramp for legacy projects).

### Phase 3 — Type-safe query builder
- Generate static metamodel per entity (`User_.EMAIL`-style typed field references).
- Fluent API (`client.user().where(User_.EMAIL.eq(x)).orderBy(...).fetchOne()`) that **compiles down to JPA Criteria**, so Hibernate handles SQL generation, dialects, and execution.
- Relation fetching: explicit `.include(User_.ADDRESS)` (fetch join / EntityGraph under the hood) — this is your structural answer to LazyInitializationException.
- `vx-spring-boot-starter`: auto-configure the client from the app's `EntityManagerFactory`.

### Phase 4 — Studio + error translation
- `vantix studio`: boots a local Spring app on `localhost` only, reads the user's datasource config, serves a React table browser/editor (paginated CRUD via JDBC — do not route studio traffic through the user's entities).
- Error-translation starter: intercept `LazyInitializationException`, constraint violations, `NonUniqueResultException` → structured, fix-suggesting messages.

### Phase 5 — Ecosystem & polish
- Gradle plugin; `doctor` (config sanity checks); DTO/mapper generation (optional flag); OpenAPI annotations; docs site; GraalVM native CLI binary; IntelliJ plugin (syntax highlighting first — a full plugin is its own large project, keep it minimal).

**Sequencing rationale:** validate+generate first because everything else consumes the schema model; migrations second because they're the highest-value differentiator; query builder third because it's independent of the first two; studio last because it's high effort and low architectural risk.

---

## 4. Learning Roadmap (Before Coding)

### Must Know

| Topic | Why this project needs it | Resource |
|---|---|---|
| **Lexing & recursive-descent parsing, ASTs** | You are building a language. Hand-rolled parsing is what gives you Prisma-quality error messages. | *Crafting Interpreters* (free: craftinginterpreters.com) — chapters 4–6 are exactly your problem |
| **JPA & Hibernate mapping semantics** | Your generator must emit *correct* mappings: relation ownership, `mappedBy`, cascade, fetch types, `equals/hashCode` for entities. You can't generate what you don't deeply understand. | Hibernate ORM User Guide (docs.hibernate.org); Vlad Mihalcea's blog for the sharp edges |
| **Spring Data JPA internals** | Generated repositories must be idiomatic; you must know what Spring derives vs what needs `@Query`. | docs.spring.io/spring-data/jpa/reference |
| **Flyway conventions** | Your migration output *is* Flyway input: naming (`V<version>__desc.sql`), checksums, the immutability contract (never edit an applied migration). | documentation.red-gate.com/flyway |
| **DDL + information_schema (PostgreSQL)** | The differ emits DDL; `db pull` reads `information_schema`/`pg_catalog`. | PostgreSQL docs, chapters on DDL & information_schema |
| **JavaPoet (Palantir fork)** | Programmatic Java source generation — cleaner and safer than string templates for code. Note: use the maintained `com.palantir.javapoet` fork; the original Square repo is archived. | github.com/palantir/javapoet |
| **picocli** | The CLI framework: subcommands, ANSI colors, autocomplete, GraalVM-friendly. | picocli.info |
| **Maven plugin development** | `generate` must run in `generate-sources` or nothing compiles. Mojos, lifecycle bindings, `${project.build.directory}`. | maven.apache.org/plugin-developers |
| **JUnit 5 + Testcontainers** | Migration and introspection tests need a real Postgres. | testcontainers.com; junit.org |
| **JPA Criteria API** | Your query builder's compilation target. Painful to use, essential to understand. | Jakarta Persistence spec §6; Hibernate query guide |

### Should Know

| Topic | Why | Resource |
|---|---|---|
| **Prisma itself — as a user** | Build a small Node app with Prisma before designing anything. Study `migrate dev`, the snapshot/history model, the error messages. You're cloning judgment, not just features. | prisma.io/docs |
| **jOOQ's design** | The best prior art for generated type-safe query DSLs in Java; study why it's database-first and how its generated classes are shaped. | jooq.org/doc |
| **Jakarta Data / Hibernate Data Repositories** | Your closest philosophical competitor; also a source of ideas (compile-time query validation, stateless repositories). | hibernate.org/repositories; Jakarta Data spec |
| **Annotation processing (JSR 269)** | Alternative/complementary codegen path; how Hibernate's metamodel generator and MapStruct work. Even if you generate from the CLI, understanding APs informs the design. | *Awesome Java Annotation Processing*; MapStruct source |
| **Generation Gap pattern** | The canonical answer to "users want to customize generated code." | Vlissides' original write-up; Fowler's "Generated Code" bliki |
| **Transaction semantics & the persistence context** | To *translate* Hibernate errors helpfully you must be able to explain them: session lifecycle, flush modes, why lazy loading fails. | Vlad Mihalcea, *High-Performance Java Persistence* |
| **Semantic diffing concepts** | Set-based matching, rename heuristics, topological ordering of dependent changes. | Read Prisma Migrate's docs + Liquibase diff docs as case studies |
| **GraalVM native-image basics** | A CLI that starts in 30ms vs 2s is a DX statement. | graalvm.org/latest/reference-manual/native-image |

### Nice to Know

| Topic | Why | Resource |
|---|---|---|
| **ANTLR** | The generator alternative to hand-rolled parsing; worth knowing to defend your choice. | *The Definitive ANTLR 4 Reference*; antlr.org |
| **Language Server Protocol** | The future path to editor support for `.vx` files without per-IDE plugins. | microsoft.github.io/language-server-protocol |
| **IntelliJ plugin SDK** | For syntax highlighting of `.vx`. | plugins.jetbrains.com/docs/intellij |
| **Liquibase internals** | The other migration philosophy (changelog-first) — know why you didn't pick it. | docs.liquibase.com |
| **Rust-free Prisma internals blog posts** | Prisma's team has written extensively about moving off their query engine — instructive about abstraction costs. | prisma.io/blog |

---

## 5. Technology Deep Dive

### 5.1 Parser: hand-written recursive descent (vs ANTLR)

**Choice: hand-written.** ANTLR gets you a working parser in a day, but its default error messages are famously robotic, and customizing recovery fights the tool. Your SDL grammar is *small* (a schema language, not a programming language) — hand-rolling it is maybe two weeks, gives you total control over diagnostics ("did you mean", multi-error reporting with recovery, precise carets), zero runtime dependency, and is the single most educational component of the project. Prisma's own parser is hand-written for exactly these reasons. **Trade-off accepted:** more upfront work, grammar changes cost more than editing a `.g4` file. **Fallback:** if Phase 1 stalls, ANTLR is a legitimate rescue path — design `vx-core`'s AST as the public contract so the parser behind it is swappable.

### 5.2 Codegen: JavaPoet (vs template engines vs annotation processing)

**Choice: JavaPoet (Palantir fork) driven by the CLI/Maven plugin, writing to `target/generated-sources`.**
- vs **string templates (Mustache/Freemarker):** templates are easier to read for HTML-ish output but produce unparseable-until-compiled Java, make imports/formatting your problem, and rot fast. JavaPoet builds a syntactic model — imports, formatting, and escaping are handled.
- vs **annotation processing:** APs are the idiomatic Java codegen path, but they trigger *from annotations in Java source* — your source of truth is a non-Java file, which APs handle awkwardly. A Maven-plugin-driven generator reading `schema.vx` directly is the honest fit. (You may still add a small AP later for query-builder metamodel regeneration.)
- **Generated code is disposable, never edited:** it lives under `target/`, is regenerated every build, and is excluded from VCS. User customization happens via the **Generation Gap pattern** — generate `abstract UserBase` + a one-time-scaffolded `User extends UserBase` that the tool never touches again — or via config flags in the schema (`@service(skip)`). Decide and document this in Phase 1; it is the most common place code generators die.

### 5.3 Migration engine: snapshot-diff (vs DB introspection diff)

**Choice: diff the schema model against a serialized snapshot of the last-migrated schema** (Prisma's model), not against a live database.
- Snapshot-diff is deterministic, works offline and in CI, and keeps the schema file as the single source of truth.
- Live-DB diffing (Liquibase-style) drifts: local DBs accumulate manual changes and the diff becomes noise. You still *use* introspection — but for `db pull` (onboarding legacy DBs) and for a `migrate diff --against-db` drift-check command, not as the primary engine.
- Output is plain Flyway SQL the user can read and edit before applying — generated SQL, human-approved, is the trust-preserving compromise.
- **Hard parts to plan for:** rename detection needs interactive prompts (there is no correct automatic answer); DDL must be topologically ordered (create referenced tables before FKs); every schema feature you add to the SDL doubles work here — this is the reason to keep the SDL small.

### 5.4 Query builder: generated static metamodel → JPA Criteria (vs lambdas, vs SQL generation)

**Choice: generate typed field constants per entity; the fluent API assembles JPA Criteria internally.**
- vs **lambda/method-reference API** (`u -> u.email()`): looks beautiful, but extracting property names from method refs requires `SerializedLambda` reflection hacks or proxy tricks — fragile across JVM versions, hostile to native-image, and IDEs can't autocomplete field constraints as well. jOOQ, QueryDSL, and JPA's own metamodel all converged on generated metamodels; that convergence is evidence.
- vs **generating SQL directly** (jOOQ's approach): you'd re-own dialects, pagination, and mapping — the exact work you decided to leave to Hibernate. Compiling to Criteria means Hibernate does SQL, and your queries return *managed entities* consistent with the rest of the user's app.
- **Trade-off accepted:** Criteria has expressiveness ceilings (exotic SQL, window functions). Provide a documented escape hatch to HQL/native queries rather than chasing 100% coverage. That's also Prisma's stance (`$queryRaw`).

### 5.5 CLI & distribution: picocli (+ Maven plugin as the primary channel)

picocli over Spring Shell (no Spring dependency in the CLI, faster startup, GraalVM support, best-in-class help/completion). **Key insight for Java specifically:** unlike npm's `npx`, Java has no universal script runner — so the *Maven plugin is the real distribution channel* (`mvn vantix:migrate` works with zero installs), and the standalone (eventually native) binary is the premium experience layered on top. JBang and SDKMAN are the install paths for the binary.

### 5.6 Studio: separate local Spring Boot app + React, over JDBC

Runs from the CLI, binds to `localhost` only, reads the app's datasource config, and talks to the DB via plain JDBC + `information_schema` (works even when entities don't compile — important, since a broken build is exactly when you inspect data). React/TS frontend matches your existing frontend stack. Alternative — HTMX/Thymeleaf — is less code but you already know React and the table-editor UI is interaction-heavy; React earns its keep here.

### 5.7 Error translation: Spring Boot starter

Auto-configured `BeanPostProcessor`/AOP layer plus a Flyway/JPA exception mapper that catches known exception types and re-throws with structured messages (what happened → why → 2–3 ranked fixes with links). Ship it as an independently usable starter — it's the cheapest pillar to build and the most viral to share ("add one dependency, get readable Hibernate errors" is a great first blog post and adoption wedge).

---

## 6. Development Strategy

### Build order (and why)

1. **Schema model before parser polish.** Define the *resolved* `Schema`/`Entity`/`Field`/`Relation` model (post-semantic-analysis) first — it's the contract every other module consumes. Parser, differ, and codegen can then proceed semi-independently.
2. **Golden-file tests before features.** For codegen and SQL rendering, snapshot the expected output (`expected/User.java`, `expected/V2__add_email.sql`) and diff against actual. This turns "is the generator right?" into reviewable diffs and makes refactoring safe. It is *the* testing pattern for generators.
3. **Dogfood app from Phase 1, always green.** `examples/demo-app` is simultaneously your integration test, your documentation source, and your empathy machine.
4. **Migrations: build the change model before any SQL.** `SchemaDiffer` produces typed `SchemaChange` objects; a separate `PostgresRenderer` turns them into DDL. Diffing logic gets pure unit tests (no DB); rendering gets Testcontainers tests that actually *apply* the SQL. Coupling diff to SQL emission is the classic mistake here.
5. **Query builder: API sketch first.** Write the README's usage examples before implementing — API design by documentation. Then metamodel generation, then Criteria compilation.

### Common mistakes to avoid

- **SDL feature creep.** Every SDL construct costs parser + validator + codegen + differ + SQL + docs. Say no by default; `@column(raw: "...")`-style escape hatches are cheaper than first-class features.
- **Editing applied migrations / regenerating past migrations.** Migration files are immutable once applied — enforce it (Flyway checksums help you).
- **Generating code users must edit.** See §5.2 — decide the customization story before Phase 1 ends.
- **Silent destructive migrations.** A tool that generates a `DROP COLUMN` a user didn't notice is a tool that gets uninstalled with a blog post attached. Guard rails are a feature.
- **Entity `equals`/`hashCode`/`toString` traps.** Your *generated* entities must get this right (id-based equality with proxy-safety, no relation fields in `toString` → recursion). Generators amplify mistakes across every user.
- **Ignoring incremental builds.** Regenerating unchanged files with new timestamps forces full recompiles; write only when content changes.

### Debugging tips

- Give every AST node and schema-model element a `SourcePosition`; thread it through so *every* downstream error (semantic, diff, codegen) can point at a line in `schema.vx`.
- `vantix migrate dev --dry-run` printing the SQL without writing files — build it for users, use it constantly yourself.
- Log the diff decision trail at debug level ("matched column by name", "type change detected: varchar(50)→varchar(100)") — diff bugs are unreproducible without it.
- Testcontainers + `@ParameterizedTest` over Postgres versions (14–17) early; DDL behavior differences are real.

---

## 7. Best Practices

**Coding standards.** Java 21; records for AST nodes and `SchemaChange` types (immutability makes diffing and testing trivial); sealed interfaces for closed hierarchies (`sealed interface SchemaChange permits AddColumn, DropColumn…` — exhaustive switches catch missed cases at compile time); Spotless + error-prone in CI; package-by-feature within modules (you already live this via DDD).

**Project structure.** Multi-module as in §2.2; enforce module boundaries (no CLI classes leaking into runtime); `vx-runtime` and `vx-spring-boot-starter` keep near-zero dependencies — they ship inside user apps.

**Testing strategy (per layer).**
- Parser: table-driven tests for valid inputs *and* a large corpus of invalid inputs asserting on the diagnostic text itself (error messages are a feature → they get regression tests).
- Codegen: golden files + a compilation smoke test (feed generated sources to the Java Compiler API and assert zero errors).
- Differ: pure unit tests on model pairs; property-style tests (apply generated changes to snapshot A, assert result equals B).
- SQL rendering + `db pull`: Testcontainers against real Postgres; round-trip test (schema → SQL → apply → introspect → compare).
- Maven plugin: maven-invoker-plugin integration tests.
- End-to-end: demo-app CI job runs init→generate→migrate→boot→query.

**Logging & observability.** SLF4J everywhere; CLI human output (colors, ✓/✗) strictly separated from `--verbose` logs and from machine-readable `--json` output (CI users will want it); never log connection strings/credentials.

**Security.**
- Studio: `localhost` binding by default, random session token printed to the terminal (Jupyter-style), CSRF on mutating endpoints, parameterized SQL only — a data editor is an SQL-injection magnet.
- Never echo credentials in errors or logs; support `${env:VAR}` interpolation in config so secrets stay out of files.
- `db push` and destructive migrations refuse to run against URLs matching production heuristics unless forced.
- Supply chain: sign releases, publish to Maven Central with provenance, minimal dependency tree.

**Performance.** Parsing/diffing are trivially fast at realistic schema sizes (hundreds of entities) — don't optimize them. What matters: CLI startup (lazy-load subcommand deps; GraalVM later), incremental generation (skip unchanged output), and *generated code quality* — emit `@EntityGraph`/fetch-join-friendly repositories and indexed FK columns by default, so apps built with your tool are fast by construction.

**Scalability (of the tool, and of apps built with it).** Schema file splitting (`import "user.vx"`) once schemas grow; deterministic generation (same input → byte-identical output) so CI caching works; for user apps, your defaults *are* your scalability story — lazy associations by default, explicit `.include()`, pagination-first query API.

---

## 8. Deliverables

### 8.1 Implementation checklist

**Phase 1 — Core**
- [ ] Grammar spec written down (EBNF in `docs/`) before parser code
- [ ] Lexer with `SourcePosition` on every token
- [ ] Recursive-descent parser, multi-error recovery
- [ ] Semantic analyzer: types, relations, attribute rules, "did you mean"
- [ ] Resolved schema model (records, immutable) + JSON serialization
- [ ] Entity generator (correct `mappedBy`, equals/hashCode, fetch defaults)
- [ ] Repository generator
- [ ] `init` / `validate` / `generate` CLI commands
- [ ] Maven plugin bound to `generate-sources`
- [ ] Demo app boots on generated code; golden-file + compile-check tests green

**Phase 2 — Migrations**
- [ ] Snapshot write/read; migration directory layout
- [ ] Differ → typed `SchemaChange` list; rename prompt flow
- [ ] Postgres DDL renderer; topological ordering
- [ ] Flyway file naming/versioning; destructive-change guard; `--dry-run`
- [ ] `db pull` introspection; round-trip test green

**Phase 3 — Query builder**
- [ ] API designed in README first; metamodel generator
- [ ] Criteria compilation: where/order/paging/`include`
- [ ] Spring Boot starter auto-config; escape hatch to HQL documented

**Phase 4 — Studio & errors**
- [ ] Studio: localhost app, token auth, table browse/edit/paginate over JDBC
- [ ] Error-translation starter: LazyInit, constraint violations, non-unique result

**Phase 5 — Polish**
- [ ] Gradle plugin · `doctor` · docs site · GraalVM binary · syntax highlighting · CONTRIBUTING/license/release pipeline

### 8.2 Study checklist (ordered)

1. Build a toy app **with Prisma** (Node) — 1 evening; take notes on every DX detail
2. *Crafting Interpreters* ch. 4–6 → then write your lexer
3. Hibernate association-mapping deep-dive (Vlad Mihalcea's mapping + equals/hashCode articles) → before writing the entity generator
4. Flyway docs (naming, checksums, workflow) → before Phase 2
5. Postgres `information_schema` + DDL chapters → before the differ/`db pull`
6. JavaPoet examples + MapStruct's generated output (read it as prior art) → before codegen
7. picocli user manual → before the CLI
8. Maven plugin dev guide → before the plugin
9. JPA Criteria + jOOQ manual skim + Jakarta Data spec skim → before Phase 3
10. Generation Gap pattern + Prisma Migrate internals docs → ongoing

### 8.3 Milestone roadmap (side-project pacing, honest estimates)

| Milestone | Scope | Est. effort |
|---|---|---|
| M0 | Throwaway end-to-end spike | 1–2 weekends |
| M1 | Parser + semantic analysis + `validate` | 3–4 weeks |
| M2 | Entity/repo codegen + Maven plugin + demo app | 3–4 weeks |
| M3 | Snapshot + differ + Postgres SQL + `migrate dev` | 5–8 weeks *(hardest)* |
| M4 | `db pull` + drift check | 2–3 weeks |
| M5 | Query builder + starter | 4–6 weeks |
| M6 | Error-translation starter (shippable standalone!) | 1–2 weeks |
| M7 | Studio | 4–6 weeks |
| M8 | Docs site, examples, v0.1 announcement | 2–3 weeks |

Realistically **6–10 months of consistent side-project time to v0.1**. If that's too long, the standalone error-translation starter (M6) can ship *first* as its own small OSS project — real users in weeks, and a feeder audience for the main tool.

### 8.4 Interview questions this project arms you for

**Language & tooling**
- How does a recursive-descent parser work, and why did you hand-write one instead of using ANTLR?
- How do you produce good compiler-style error messages (recovery, positions, suggestions)?
- How does annotation processing work in Java, and when is external codegen a better fit?
- Why is generated code placed in `target/generated-sources`, and how does the Maven lifecycle make that compile?

**Persistence internals**
- Why does `LazyInitializationException` happen, and what are the three structural fixes? (You built a tool that explains this — gold in interviews.)
- How should entity `equals`/`hashCode` be implemented and why is it subtle with proxies?
- Stateful `Session` vs `StatelessSession` — and why is Jakarta Data built on the latter?
- How does Hibernate's dirty checking work; what does the persistence context actually hold?
- JPQL vs Criteria vs native SQL — trade-offs, and how your query builder compiles to Criteria.

**Databases & migrations**
- Why are applied migrations immutable? What do Flyway checksums protect against?
- How do you algorithmically diff two schemas, and why is rename detection undecidable without user input?
- Snapshot-based vs database-introspection-based diffing — trade-offs?
- What ordering constraints exist among DDL statements, and how did you solve them (topological sort)?

**Design & architecture**
- Why build *on* Hibernate rather than replace it? (Buy-vs-build, risk, ecosystem leverage — a genuinely senior answer.)
- Generation Gap pattern: how do you let users customize generated code safely?
- Why sealed interfaces + records for your change model? (Exhaustiveness, immutability.)
- How did you keep the runtime dependency thin, and why does "ejectability" matter for adoption?
- How would you add MySQL support — what abstractions did you prepare (renderer interface), what did you deliberately not abstract prematurely?

---

*Suggested first step: Phase 0 spike this weekend, plus one evening actually using Prisma in a scratch Node project — the second one will improve every design decision you make more than any document, including this one.*
