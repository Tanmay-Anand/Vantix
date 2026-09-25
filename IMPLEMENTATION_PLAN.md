# Vantix — Comprehensive Implementation Plan

*Derived from `vantix-project-plan.md` and `README.md`. This document is the execution contract: it resolves open decisions, sequences the work, and breaks it into tasks with explicit dependencies.*

---

## 0. Decisions Required Before Coding (resolved here)

The source documents leave several items undecided or contradictory. Each is resolved below with a recommendation and rationale. **Treat these as binding unless overridden.**

| # | Open question / conflict | Source of conflict | Decision | Rationale |
|---|---|---|---|---|
| D1 | Where do snapshot + migrations live? | Plan §2 says `vantix/migrations/.../snapshot.json`; README config says `src/main/resources/db/migration` (Flyway default) | `schema.vx` → `vantix/schema.vx`; single latest snapshot → `vantix/snapshot.json`; emitted SQL → `src/main/resources/db/migration/V<timestamp>__*.sql` (per D11). **Known limitation (recorded, not settled):** a single mutable `snapshot.json` reintroduces the exact cross-branch collision D11 fixes for filenames — two branches each regenerate the whole JSON blob and produce an unresolvable git conflict. Defensible solo-on-one-branch for v1; the planned fix is the shadow-DB verify path in D13, and the authoritative previous state becomes the replayed migration history (snapshot is then a cache, not the truth). | Flyway finds SQL zero-config; Vantix state stays in a dedicated dir committed to VCS. Prisma's model is history-replayed-against-shadow-DB = truth, snapshot = cache; we adopt the cache now and the shadow-DB truth in Phase 2 (D13). |
| D2 | Does `db push` ship in v1? | §1.5 "recommend: no"; README lists it in CLI reference | **Out of v1.** Stub the command to print "not yet available." | Prototype-mode without migration history is a footgun and doubles the apply path. Defer to Phase 5. |
| D3 | When is the metamodel generated? + naming | README implies Phase 1 (`generate` emits metamodel); Plan §3 puts metamodel in Phase 3 | **Phase 3**, alongside the query builder that consumes it. Phase 1 emits entities + repositories only. **Naming decided now (hard to change post-release):** generated constants are **NOT** named `User_` — use `UserFields` (or a dedicated `…metamodel` subpackage). | Generating speculative constants with no consumer churns golden files. `User_` is the exact class `hibernate-jpamodelgen` emits (commonly enabled for typed Spring Data `Specification`s); two processors writing `com.acme.User_` is a duplicate-class compile error in the user's build that will look like Vantix's fault. Distinct name costs nothing today, avoids a breaking rename later. |
| D4 | Enum column representation | README type map: "varchar + check, or native enum" | **`varchar` + `CHECK` constraint as the permanent default** (not just v1); store as string via `@Enumerated(EnumType.STRING)`. Expose a config knob `enumStorage = "varchar_check" \| "varchar" \| "native"` (default `varchar_check`); `native` is opt-in and **never auto-migrated**. | Decisive argument: `ALTER TYPE … ADD VALUE` historically **cannot run inside a transaction block**, and Flyway wraps migrations in transactions by default — so "add an enum value" (the most common enum migration) fails or forces per-migration transaction special-casing. A CHECK constraint is dropped+recreated inside a normal transaction with zero special-casing. `varchar` (no constraint) serves teams that deliberately want no DB-level constraint. |
| D5 | `Json` type mapping | README: "String / JsonNode" | **`String` mapped to `jsonb`** in v1 (via Hibernate `@JdbcTypeCode(SqlTypes.JSON)`). | Avoids a Jackson dependency in generated entities; users can change the field type manually if they want `JsonNode`. |
| D6 | Composite primary keys (`@@id`) in MVP? | README lists `@@id`; Plan §3 Phase 1 grammar omits it | **Defer to Phase 2.5.** Phase 1 requires a single `@id` field per entity. | `@IdClass`/`@EmbeddedId` codegen + Criteria + diffing is a large multiplier. |
| D7 | Many-to-many in MVP? | Plan §3 Phase 1 lists "N-N"; README examples show only 1-1/1-N | **Defer N-N to Phase 2.5.** Phase 1 supports scalar fields, 1-1, and 1-N only. | Join-table synthesis complicates both codegen and migration. Ship the 80% first. |
| D8 | Config location: `schema.vx` blocks vs `pom.xml` plugin config? | README shows both `generator {}` in schema and `<configuration>` in the plugin | **`schema.vx` is authoritative** for `datasource`, `generator`, `migrations` blocks. The Maven plugin only needs `<schemaPath>` (default `vantix/schema.vx`). | Single source of truth. Plugin config stays minimal so `mvn` and standalone CLI behave identically. |
| D9 | How does `vantix.user()` resolve entity accessors? | README shows fluent facade; mechanism unspecified | **Generate a `VantixClient` facade** with one typed accessor method per entity. Bean named `vantix`. | No reflection; full IDE autocomplete; native-image safe. |
| D10 | Licensing | §1.5 "recommended" | **Apache-2.0**, `LICENSE` + SPDX headers from commit 1. | Permissive, standard for dev tooling, contributor-friendly. |
| D11 | Migration version numbering under concurrent devs | Not addressed | Use **timestamp-based versions** `V<yyyyMMddHHmmss>__slug.sql` (Flyway supports this) instead of `V1, V2…`. This also resolves U5: Vantix never needs to *preserve* existing numbering, only to never write into the existing range — a 2026 timestamp sorts after `V17` and after `V2.1.3` automatically. | Sequential integers collide across branches/PRs. Timestamps don't. |
| D12 | Dev OS / toolchain | Not addressed; developer is on **Windows 11 + PowerShell** | Require Docker Desktop for Testcontainers; use `mvnw` wrapper; verify GraalVM native build on Windows separately in Phase 5. **Golden-file line-ending guard (do before the first golden test):** commit `.gitattributes` with `* text=auto eol=lf` + explicit `eol=lf` on `src/test/resources/golden/**`, and normalize line endings inside the comparison assertion itself. **Local Testcontainers:** pin local tests to a single Postgres version; run the 14–17 matrix in CI only (RAM budget on the laptop). **GraalVM native (Phase 5):** requires MSVC build tools installed separately on Windows. | Testcontainers and native-image both have Windows-specific gotchas; CRLF vs LF is the highest-probability failure and fails golden-file diffs nondeterministically by git autocrlf setting. |
| D13 | Correctness under merges / snapshot-as-cache | Emerges from the D1↔D11 contradiction | Add `vantix migrate dev --verify`: spin a throwaway Postgres (Testcontainers, already a dep), apply all migrations in order, introspect, diff against `schema.vx`; fail if non-empty. **Default-on in CI, opt-in locally** (not default — requiring Docker on every `migrate dev` is a DX tax for a DX tool). | Gives correctness under parallel branches (the real fix for D1's limitation) and proves every generated migration actually executes before commit. History-replayed-against-shadow-DB is the authoritative state; `snapshot.json` is a performance cache. |
| D14 | Multi-schema: defer feature, not field | U4 | **Defer the `@@schema` feature; do NOT defer the field.** Phase 1: include a **nullable `schemaName`** in the resolved model *and* snapshot from day one (parser rejects `@@schema` with "not yet supported"; field stays null). Add a **`formatVersion` integer at the top of `snapshot.json` from commit 1** with a documented upgrade path. | Adding `schemaName` later invalidates every existing snapshot, breaking the first user upgrade's next diff. `formatVersion` makes snapshot-format migration a solvable problem instead of a breaking one — you *will* need it. |

**Assumptions carried forward (from §1.5, updated):** PostgreSQL only; Java 21; **Spring Boot 4.1 / Hibernate 7 as the primary target** (see U1 — Boot 3.5 reached OSS end-of-life 2026-06-30 and is now unpatched; targeting it is an adoption-killer); Maven first (Gradle later); Kotlin out of scope.

---

## 1. Project Overview

### 1.1 Objective
Build **Vantix**, a compile-time developer-experience toolchain for Spring Boot that makes a single declarative file (`schema.vx`) the source of truth for JPA entities, Spring Data repositories, Flyway migrations, a type-safe query API, and a local data browser. Vantix sits **on top of** Hibernate/Spring Data JPA/Flyway — it never replaces their runtime behavior, and generated code is plain, ejectable Spring code.

### 1.2 Core Features (the seven pillars)
1. **SDL** — `schema.vx` language (entities, fields, types, constraints, relations, indexes).
2. **Code generation** — JPA entities + Spring Data repositories (DTOs/mappers later).
3. **Migration generation** — snapshot-diff → ordered DDL → Flyway `V__*.sql`.
4. **Type-safe query builder** — generated metamodel + fluent API → JPA Criteria.
5. **CLI** — `init`, `validate`, `generate`, `migrate`, `db pull`, `studio`, `doctor`.
6. **Studio** — localhost data browser over JDBC.
7. **Error translation** — Spring Boot starter that rewrites Hibernate exceptions.

### 1.3 Success Criteria
- **MVP (Phase 1):** a stranger clones a Spring Boot app, writes `schema.vx`, runs `mvn compile`, and gets working entities + repositories. Error messages are good enough that they never read Vantix source.
- **Highest-value differentiator (Phase 2):** `vantix migrate dev` produces correct, ordered, human-reviewable Flyway SQL with safe rename/destructive handling.
- **Trust guarantees, verified by tests:** deterministic generation (byte-identical output for identical input); ejectability (delete Vantix, generated code still compiles and runs); no applied migration is ever rewritten.
- **Quality bars:** parser reports *all* errors in one pass with source positions + "did you mean"; generated entities pass an exhaustive `equals`/`hashCode`/proxy correctness suite; every emitted DDL statement actually executes against PostgreSQL 14–17 in CI.

---

## 2. Architecture Analysis

### 2.1 The Proposed Shape (validated — keep it)
A **compile-time toolchain with a thin runtime**. All heavy work (lex, parse, analyze, diff, generate) runs at build time via the CLI/Maven plugin. The only artifact shipped inside the user's app is `vx-runtime` (query builder execution + error translation) plus the auto-config starter. Hibernate/JPA/Flyway are unmodified.

**This architecture is sound and should be preserved.** Its three load-bearing decisions each hold up:
- *Compile-time tool / thin runtime* → zero runtime perf risk, full ejectability.
- *Emit Flyway SQL, don't run it* → inherit Flyway's checksums/CI story/trust.
- *Snapshot diffing, not live-DB diffing* → deterministic, offline, CI-friendly.

### 2.2 The Resolved Schema Model is the Keystone
Everything downstream (codegen, differ, snapshot) consumes the **post-semantic-analysis `Schema` model**. Define it **first**, as immutable Java 21 records, with `SourcePosition` threaded through every element so any downstream error can point at a line in `schema.vx`. This is the single most important contract in the system — freeze its API early.

```
Schema
 ├─ List<Entity>
 │   ├─ name, tableName, SourcePosition
 │   ├─ List<Field> (name, columnName, ScalarType|EnumRef, nullable, attributes, pos)
 │   ├─ IdStrategy (single-field for v1)
 │   ├─ List<Relation> (kind: OneToOne|OneToMany|ManyToOne, owning?, mappedBy, fk, onDelete)
 │   └─ List<IndexDecl>, List<UniqueDecl>
 ├─ List<EnumDecl>
 ├─ Datasource (provider, url-expr, schema)
 └─ Generator (package, output, flags)
```

### 2.3 Module Boundaries (from §2.2 — enforce them)
`vx-core` (no Spring, no JavaPoet) → `vx-codegen` (JavaPoet) and `vx-migrate` (JDBC) both depend on it. `vx-cli` depends on core+codegen+migrate. `vx-runtime` and `vx-spring-boot-starter` ship inside user apps → **near-zero dependencies, no CLI/codegen classes leaking in** (enforce with ArchUnit or `maven-enforcer`). `vx-maven-plugin` wraps the CLI generate path. `vx-studio` is a standalone Spring Boot app.

### 2.4 Missing Pieces / Risks / Ambiguities Identified
- **Spring Boot baseline is a dead branch** — the source plan targets Boot 3.5, which reached OSS end-of-life 2026-06-30 and is now unpatched. Re-targeted to Boot 4.1 / Hibernate 7 (U1), which the architecture makes cheap (~80% of the code has no Spring dependency; only `vx-spring-boot-starter` is version-pinned).
- **D1 ↔ D11 contradiction (recorded):** D11 fixes cross-branch collision for migration *filenames* (timestamps) but D1 leaves it in place for migration *state* — a single mutable `snapshot.json` produces unresolvable git conflicts on parallel branches. Defensible for solo v1, but recorded as a known limitation with the D13 shadow-DB `--verify` path as the planned fix (history-replay = truth, snapshot = cache).
- **Metamodel timing + naming** — resolved in D3 (Phase 3; `UserFields`, not `User_`, to avoid the `hibernate-jpamodelgen` duplicate-class collision).
- **Version collision across branches** — resolved in D11 (timestamps) for filenames; see D1/D13 for state.
- **The "baseline" problem for existing projects** — `db pull` must emit an initial snapshot *and* a baseline marker so Flyway `baselineOnMigrate` and Vantix's first diff agree; **`migrate baseline` is made self-verifying** (re-introspect → diff → refuse unless empty) so weak introspection fails loudly on first contact instead of emitting a spurious `ADD` (U5). Phase 2.
- **Multi-schema forward-compat** — resolved in D14 (ship nullable `schemaName` + snapshot `formatVersion` in Phase 1 even though `@@schema` is unsupported, so later addition doesn't invalidate existing snapshots).
- **Enum migration hazard** — resolved in D4 (`ALTER TYPE … ADD VALUE` can't run in Flyway's transaction; varchar+CHECK is the permanent default with an `enumStorage` knob).
- **Client facade mechanism** — resolved in D9.
- **Config duality** — resolved in D8.
- **No CI/CD, release, or signing pipeline defined** — added as a first-class Phase 0/5 workstream (§4.6), now including the Boot 3.5-portability matrix.
- **`equals`/`hashCode`/`toString` correctness** — flagged in the doc as a top trap; elevated here to a dedicated, exhaustively-tested codegen concern (M1.4-T13).
- **Incremental generation** — content-hash-before-write is a correctness requirement (avoids full recompiles), not a nice-to-have; build it into the writer from day one.
- **Windows dev environment** — Testcontainers/Docker Desktop and native-image both need Windows verification; **CRLF/LF golden-file guard (`.gitattributes`) and MSVC-for-native are the concrete gotchas** (D12).

### 2.5 Recommended Improvements Over the Source Plan
1. **Re-target Boot 4.1 / Hibernate 7 as primary** (U1) — the source plan's Boot 3.5 baseline is now EOL/unpatched; shipping onto it is an adoption-killer. Cheap here because the toolchain is Spring-free by design.
2. **Timestamp migration versions** (D11) — avoids a whole class of merge pain the doc never mentions.
3. **Shadow-DB `migrate dev --verify`** (D13) — makes correctness hold under parallel branches (the real fix for the D1 snapshot-collision limitation) and proves every migration executes before commit; Testcontainers is already a dependency.
4. **Self-verifying `migrate baseline`** (U5) — re-introspect and refuse on non-empty diff, converting weak introspection coverage into a loud early error and a free `db pull` correctness harness.
5. **Forward-compatible snapshot format** (D14) — nullable `schemaName` + `formatVersion` from commit 1 so deferred features don't break existing users' snapshots.
6. **A `SchemaChange` "reverse renderer"** for down-migrations is explicitly *not* built (Flyway forward-only), but generate a commented `-- rollback:` hint block per migration for human use.
7. **Add ArchUnit module-boundary tests** in Phase 1 so the thin-runtime guarantee can't silently rot.
8. **Ship the error-translation starter as its own releasable artifact early** (the doc notes this as an adoption wedge — make it an explicit Phase 4a that can release before the rest).

---

## 3. Implementation Roadmap

Phases mirror the source doc; each is decomposed into milestones (M#) and tasks (T#) with dependencies. **`→` means "depends on".**

### Phase 0 — Spike + Foundations (1–2 weekends)
**M0.1 Throwaway end-to-end spike**
- T1: Regex-parse a hardcoded 2-entity schema, print `CREATE TABLE`, emit one entity as a string. *Throw away after.*
- T2: One evening building a scratch **Prisma** (Node) app; write down every DX detail (migrate prompts, error text, snapshot behavior).

**M0.2 Repo + build foundation** (can run parallel to M0.1)
- T3: Maven multi-module skeleton (parent `pom.xml` + all empty modules from §2.2). → none
- T4: CI pipeline (GitHub Actions): build, test, Spotless, error-prone, ArchUnit. → T3
- T5: `LICENSE` (Apache-2.0), SPDX header enforcement, `CONTRIBUTING.md`, `.gitignore` (excludes `target/generated-sources`). → T3
- T6: Coding-standard config: Spotless + error-prone + `maven-enforcer` (dependency convergence, banned deps in runtime modules). → T3
- T7: **`.gitattributes` with `* text=auto eol=lf` + explicit `eol=lf` on `src/test/resources/golden/**`** — must land *before the first golden-file test* or CRLF/LF diffs fail nondeterministically by git autocrlf setting (D12). → T3

### Phase 1 — MVP: `schema.vx → validate → generate`

**M1.1 — Schema model + grammar spec** *(the contract)*
- T1: Write EBNF grammar spec in `docs/grammar.md` **before** parser code. → none
- T2: Define resolved `Schema`/`Entity`/`Field`/`Relation`/`EnumDecl` records + `SourcePosition`. **Include a nullable `schemaName` on the entity model now** even though `@@schema` is unsupported (D14). → none
- T3: JSON serialization/deserialization of the `Schema` model (Jackson), round-trip tested. **Write a `formatVersion` integer at the top of `snapshot.json` from commit 1**, with a documented upgrade path (D14). → T2
- T4: Parser rejects `@@schema` with a "not yet supported" diagnostic (field stays null) — reserves the syntax without shipping the feature (D14). → M1.2

**M1.2 — Lexer + Parser** (`vx-core`)
- T4: `Token`, `TokenType`, `Lexer` with `SourcePosition` (line/col/offset) on every token. → M1.1-T1
- T5: Recursive-descent `Parser` → AST (`EntityDecl`, `FieldDecl`, `RelationDecl`, `AttributeDecl`). → T4
- T6: **Error recovery**: on syntax error, skip to next entity block, keep collecting; report ALL errors at once with caret + "did you mean". → T5
- T7: `Diagnostics` type (severity, message, position, suggestion) + a pretty terminal renderer. → T4

**M1.3 — Semantic analysis** (`vx-core`)
- T8: Resolve AST → `Schema` model: type checking, relation target resolution, ownership/`mappedBy` inference. → T5, M1.1-T2
- T9: Validation rules: duplicate names, unresolved relation targets, missing `@id`, invalid attribute combinations, unknown types (with suggestions). → T8
- T10: "did you mean" engine (Levenshtein over known types/attributes/entity names). → T7

**M1.4 — Codegen** (`vx-codegen`)
- T11: Content-hash-aware source writer (skip write if unchanged). → M1.1-T2
- T12: **Entity generator** (JavaPoet): fields, JPA annotations, relation mapping (`@OneToMany(mappedBy)`, `@ManyToOne` + `@JoinColumn`, `@OneToOne`), fetch defaults (LAZY associations). → T8, T11
- T13: **`equals`/`hashCode`/`toString`**: id-based equality with `Hibernate.getClass()` unwrap, constant hashCode for unsaved instances, no relations in `toString`. *Exhaustively tested.* → T12
- T14: **Repository generator**: `JpaRepository<T, IdType>` per entity + derived `findByX` for `@unique` fields. → T12
- T15: Generation Gap support (`useGenerationGap` flag): emit `abstract <Entity>Base` + scaffold `<Entity>` once. → T12

**M1.5 — CLI + Maven plugin**
- T16: picocli app skeleton; human output vs `--verbose` logs vs `--json` cleanly separated. → M1.3
- T17: `vantix init` (scaffold `vantix/schema.vx`, `vantix/`, `.gitignore` entries). → T16
- T18: `vantix validate` (parse + analyze, report all, exit non-zero on error). → T16, M1.3
- T19: `vantix generate` (→ configured output dir). → T16, M1.4
- T20: `vantix generate --watch`. → T19
- T21: `vx-maven-plugin` `GenerateMojo` bound to `generate-sources`; reads `vantix/schema.vx`. → T19

**M1.6 — Dogfood + tests**
- T22: `examples/demo-app` — Spring Boot app that boots on generated entities/repos. → T21
- T23: Golden-file test harness (`expected/User.java` diffed vs actual). → T12
- T24: Compilation smoke test (feed generated sources to Java Compiler API, assert zero errors). → T12
- T25: Parser diagnostic-corpus tests (large set of invalid inputs asserting exact diagnostic text). → M1.2
- T26: ArchUnit module-boundary tests. → all

**Phase 1 DoD:** demo-app boots on generated code; golden-file + compile-check + diagnostic-corpus tests green; `mvn compile` runs generation with zero manual steps.

### Phase 2 — Migrations: `vantix migrate dev`

**M2.1 — Snapshot + diff model**
- T1: Snapshot store: write/read `vantix/snapshot.json` (reuse M1.1-T3 serialization). → Phase 1
- T2: `sealed interface SchemaChange permits CreateTable, DropTable, AddColumn, DropColumn, AlterColumnType, RenameColumn, AddIndex, DropIndex, AddUnique, AddForeignKey, DropConstraint …` (records). → T1
- T3: `SchemaDiffer`: match entities → fields → attributes; emit `List<SchemaChange>`. Pure, no DB. Log decision trail at debug. → T2

**M2.2 — Rename + destructive handling**
- T4: Rename detection: field removed AND field added on same entity → interactive prompt (`renameStrategy = prompt|never|always-rename`). → T3
- T5: Record confirmed rename decisions in snapshot so it never re-asks. → T4
- T6: Destructive-change classification (per README change-type table) + guard: refuse to emit uncommented `DROP`/narrowing without `--allow-destructive`; emit commented-out + loud warning + non-zero exit in CI otherwise. → T3

**M2.3 — SQL rendering**
- T7: `PostgresRenderer`: `SchemaChange` → DDL string. → T2
- T8: **Topological ordering**: create tables → columns → indexes → FKs; reverse for drops. → T7
- T9: Enum rendering honoring `enumStorage` (`varchar_check` default → `varchar` + `CHECK`; `varchar`; `native` opt-in, never auto-migrated) (D4); `Json` → `jsonb` (D5). **Enum-value additions render as CHECK drop+recreate, never `ALTER TYPE … ADD VALUE`** (transaction-incompatible with Flyway). → T7
- T10: Flyway file writer: `V<timestamp>__<slug>.sql` (D11) into `src/main/resources/db/migration`. → T8

**M2.4 — Commands**
- T11: `vantix migrate dev [--name] [--dry-run] [--allow-destructive] [--verify]`. → M2.2, M2.3
- T11b: **`--verify` (D13):** spin a throwaway Postgres (Testcontainers), apply all migrations in order, introspect, diff against `schema.vx`; fail on non-empty diff. **Default-on in CI, opt-in locally.** Fixes correctness under parallel branches and proves each migration executes pre-commit. → T11, M2.5-T14
- T12: `vantix migrate status` (which migrations exist/applied, snapshot currency). → T11
- T13: **`vantix migrate baseline` — self-verifying** (U5): write `schema.vx` + snapshot, then re-introspect the live DB, diff against the snapshot, and **refuse to complete unless the diff is empty**, printing exactly what didn't round-trip. Turns weak introspection coverage into a loud early error and doubles as a `db pull` correctness harness. → T1, M2.5-T14

**M2.5 — Introspection (`db pull`)**
- T14: `information_schema`/`pg_catalog` reader → `Schema` model. → M2.1
- T15: `vantix db pull` → writes `schema.vx`. → T14
- T16: `vantix migrate diff --against-db` (drift check: snapshot vs live DB). → T14

**M2.6 — Tests**
- T17: Differ pure unit tests + property test (apply changes to snapshot A ⇒ equals B). → M2.1
- T18: SQL renderer Testcontainers tests (PG 14–17, `@ParameterizedTest`): emitted DDL actually executes. → M2.3
- T19: Round-trip introspection test (schema → SQL → apply → `db pull` → compare). → M2.5

**Phase 2 DoD:** `migrate dev` produces ordered, executable, review-able SQL; rename prompts and destructive guards work; `db pull` round-trips; all Testcontainers tests green.

### Phase 2.5 — Deferred SDL features (composite keys + N-N)
- T1: `@@id([a,b])` → `@IdClass`/`@EmbeddedId` codegen + Criteria support + diffing. (D6)
- T2: N-N relations → join-table synthesis in codegen + migration. (D7)
- T3: `@@unique([a,b])`, `@@index([a,b])` composite forms end-to-end.
*(Gate: only start once Phase 2 is stable; each item is a full parser→validator→codegen→differ→SQL→docs slice.)*

### Phase 3 — Type-safe query builder
**M3.1** — Design API by writing README usage examples first (API-by-documentation).
**M3.2** — Metamodel generator: typed field constants per entity named **`UserFields.EMAIL`** — **not `User_`** (D3: avoids `hibernate-jpamodelgen` duplicate-class collision) — + relation traversal (`.dot()`). → M3.1
**M3.3** — Generated `VantixClient` facade with per-entity accessors (D9). → M3.2
**M3.4** — Fluent API → `CriteriaQuery` compilation: `where` (predicates: eq/in/after/endsWith/and/or), `orderBy`, `page`, `include` (fetch join / `EntityGraph`). → M3.3
**M3.5** — `vantix.raw(sql, Type.class)` escape hatch (documented). → M3.4
**M3.6** — `vx-spring-boot-starter`: auto-configure `VantixClient` bean from `EntityManagerFactory`. → M3.4
**M3.7** — Tests: predicate-tree unit tests; integration tests (Testcontainers) asserting generated SQL + managed-entity identity.

### Phase 4 — Studio + error translation
**Phase 4a — Error-translation starter (independently releasable — ship first)**
- Intercept `LazyInitializationException`, constraint violations, `NonUniqueResultException` → structured "what → why → ranked fixes + `VX-####` doc link" messages via `BeanPostProcessor`/AOP.
- Release as standalone artifact + first blog post (adoption wedge).

**Phase 4b — Studio**
- Backend: standalone Spring Boot app, `localhost`-only bind, random session token (Jupyter-style), CSRF on mutations, **parameterized SQL only**, talks to DB via plain JDBC + `information_schema` (works when app doesn't compile).
- Frontend: React 19 + TS 5 + Tailwind v4; table list, paginated browse, row edit/add, FK navigation.
- `vantix studio` command starts it on `:5555` (`VANTIX_STUDIO_PORT` override).

### Phase 5 — Ecosystem & polish
- Gradle plugin; `vantix doctor`; DTO/mapper generation (opt-in flag); OpenAPI annotations; `db push` (D2, gated to local/non-prod URLs); docs site; **GraalVM native CLI** (verify on Windows — D12); `.vx` IntelliJ syntax highlighting; Maven Central publish + release signing/provenance.

---

## 4. Technical Breakdown

### 4.1 Backend Components
| Component | Module | Responsibility | Key deps |
|---|---|---|---|
| Lexer/Parser/Semantic | `vx-core` | text → `Schema` model | none (no Spring/JavaPoet) |
| Entity/Repo/Metamodel gen | `vx-codegen` | model → `.java` | JavaPoet (Palantir fork) |
| Snapshot/Differ/Renderer/Introspect | `vx-migrate` | model diff → Flyway SQL; DB → model | JDBC, Jackson |
| Query builder + error translator | `vx-runtime` | fluent API → Criteria; exception rewrite | Jakarta Persistence API only |
| CLI | `vx-cli` | commands | picocli, core+codegen+migrate |
| Maven plugin | `vx-maven-plugin` | bind generate to lifecycle | Maven Plugin API |
| Starter | `vx-spring-boot-starter` | auto-config client + translator | Spring Boot autoconfigure |
| Studio backend | `vx-studio` | JDBC data browser API | Spring Boot Web, JDBC |

### 4.2 Frontend Components (Studio only)
- React 19 + TypeScript 5 + Tailwind CSS v4 (matches existing stack).
- Views: table sidebar, paginated data grid, inline row editor, add-row form, FK link navigation, filter builder.
- All mutations CSRF-protected; token passed via query param on load then held in memory.

### 4.3 Database Design
- **Target:** PostgreSQL 14–17 only (v1). Type map per README §Type Mapping; enum per `enumStorage` (default `varchar_check`, D4); Json → jsonb (D5).
- **Migration artifacts:** Flyway `V<timestamp>__slug.sql` in `src/main/resources/db/migration`.
- **State:** `vantix/snapshot.json` (with a leading `formatVersion` int, D14) = last-migrated resolved model (the diff baseline; a *cache* — the shadow-DB replay in D13 is the authoritative correctness check). Model carries a nullable `schemaName` reserved for multi-schema (D14).
- **DDL ordering:** topological (tables → columns → indexes → FKs; reversed for drops).
- **Immutability:** never rewrite an applied migration; corrections are new migrations (Flyway checksums enforce).

### 4.4 APIs
- **CLI surface:** `init`, `validate`, `generate [--watch]`, `migrate dev|status|baseline|diff --against-db`, `db pull`, `studio`, `doctor`, `format`; global `--verbose`, `--json`, `--version`.
- **Maven goals:** `vantix:init`, `vantix:generate` (auto in `generate-sources`), `vantix:migrate`, `vantix:studio`.
- **Runtime API:** `VantixClient` fluent query builder + `vantix.raw(...)`.
- **Studio REST:** JDBC-backed CRUD endpoints (localhost, token-guarded).

### 4.5 Authentication & Authorization
- **Only surface with auth = Studio.** Localhost bind + random per-session token (printed to terminal) + CSRF on mutations. No user accounts, no RBAC (single local developer). Do **not** expose via reverse proxy.
- **Credentials:** environment variables only (`DATABASE_URL`); `${env:VAR}` interpolation in `schema.vx`; never logged, never written to snapshot/error/log.

### 4.6 Infrastructure & Deployment
- **CI:** GitHub Actions — build, unit tests, Testcontainers integration (Docker), Spotless, error-prone, ArchUnit boundary tests, `maven-invoker-plugin` plugin ITs, demo-app e2e (`init→generate→migrate→boot→query`).
- **CI matrix jobs (added):** (a) **Boot-portability** — compile generated entities/repositories against Boot 3.5 *and* Boot 4.1 to prove the contract stays portable; (b) **Postgres 14–17 version matrix** for SQL-renderer/introspection Testcontainers tests (matrix runs in CI only; local tests pin one version — D12); (c) **`migrate dev --verify`** default-on (shadow-DB replay — D13).
- **Distribution:** Maven Central (plugin + starter + runtime); standalone JAR via JBang/SDKMAN; GraalVM native binary (Phase 5, requires MSVC build tools on Windows — D12).
- **Release:** signed artifacts, provenance, minimal dependency tree; semantic versioning starting `0.1.0`.
- **Dev env:** Windows 11 + PowerShell + Docker Desktop; `mvnw` wrapper committed; `.gitattributes` enforcing `eol=lf` (esp. golden fixtures — D12).

### 4.7 Integrations
Hibernate ORM 7 (Spring Boot 4.1 primary target; see U1), Spring Data JPA, Flyway (all unmodified/consumed), JavaPoet, picocli, Testcontainers, Jackson. GraalVM (Phase 5). IntelliJ (syntax highlighting, Phase 5). **Boot-portability check:** CI matrix job compiles generated output against Boot 3.5 to keep the entity/repo contract portable; only `vx-spring-boot-starter` is version-pinned to Boot 4.

### 4.8 Testing Strategy (per layer)
| Layer | Approach |
|---|---|
| Lexer/Parser | Table-driven valid + large invalid corpus asserting exact diagnostic text |
| Semantic | Property: each invalid model shape → exactly one diagnostic with correct position |
| Codegen | Golden files + Java Compiler API smoke test; dedicated `equals/hashCode`/proxy suite |
| Differ | Pure unit tests + round-trip property (apply A-changes ⇒ B) |
| SQL renderer / `db pull` | Testcontainers PG 14–17; DDL executes; introspection round-trip |
| Maven plugin | `maven-invoker-plugin` on throwaway project |
| Query builder | Predicate unit tests + Testcontainers SQL/identity assertions |
| E2E | Demo-app CI: `init→generate→migrate→boot→query` every push |

### 4.9 Monitoring & Observability
- SLF4J throughout; strict separation of human CLI output (colors, ✓/✗) vs `--verbose` debug logs vs `--json` machine output.
- Differ logs a full decision trail at debug (essential — diff bugs are unreproducible without it).
- Never log connection strings/credentials.
- `vantix doctor`: Java version, datasource reachability, Flyway config, snapshot/migration consistency, plugin wiring.

---

## 5. Development Order (and why)

1. **Repo/build/CI foundation (Phase 0, M0.2)** — nothing is testable without it; ArchUnit + golden-file harness must exist before code they guard.
2. **Resolved `Schema` model + JSON (M1.1)** — the contract every module consumes. Freeze it first so parser/codegen/differ proceed semi-independently. *Highest-leverage early work.*
3. **Parser + diagnostics (M1.2–1.3)** — error quality is a headline feature; building it early sets the quality bar and produces the model instances everything else needs.
4. **Codegen + demo-app (M1.4–1.6)** — delivers the MVP "wow" and a permanent, always-green integration harness (empathy machine).
5. **Migrations (Phase 2)** — the true differentiator, but it *consumes* the model, so it comes after codegen proves the model. Build the **change model before any SQL** (differ unit-tested with no DB, renderer Testcontainers-tested) — coupling diff to SQL emission is the classic failure.
6. **Query builder (Phase 3)** — independent of migrations; API-by-documentation first, then metamodel, then Criteria compilation.
7. **Error-translation starter (Phase 4a)** — cheapest pillar, independently shippable, adoption wedge → release *before* Studio for early users/feedback.
8. **Studio (Phase 4b)** — high effort, low architectural risk → last of the core pillars.
9. **Polish (Phase 5)** — Gradle/native/DTO/docs once the core is proven.

**Why this minimizes risk & maximizes velocity:** it front-loads the two irreversible-if-wrong decisions (the model contract and the diff engine), keeps a runnable dogfood app green from Phase 1 as a continuous integration signal, defers the highest-scope/lowest-architectural-risk work (Studio, native image) to the end, and carves off the error-translation starter as an early independent release to get real users before the whole thing is done.

---

## 6. Potential Risks

### 6.1 Technical
- **Schema diffing / rename ambiguity (highest):** undecidable without user input. *Mitigation:* interactive prompt, safe default (drop+add is data-losing → default to *not* auto-renaming unless confirmed), persist decisions in snapshot, log decision trail.
- **DDL ordering bugs:** FK-before-table etc. *Mitigation:* explicit topological sort + Testcontainers execution of every generated migration.
- **`equals`/`hashCode`/proxy traps:** amplified across every user entity. *Mitigation:* single tested template, dedicated proxy/detached/pre-persist test matrix.
- **Codegen coexisting with user code:** Java has no partial classes. *Mitigation:* generated code under `target/`, never edited; Generation Gap for customization; decided in Phase 1, not later.
- **Parser scope creep vs error quality:** *Mitigation:* keep SDL small (D6/D7 deferrals), `@raw` escape hatch instead of first-class features.
- **Native-image + reflection:** query builder must avoid `SerializedLambda`/reflection (chosen metamodel approach already avoids this) — verify on Windows in Phase 5.

### 6.2 Scalability (of the tool and of apps built with it)
- Parsing/diffing are trivially fast at realistic sizes (hundreds of entities) — **do not optimize prematurely.**
- What matters: CLI startup (lazy-load subcommand deps; GraalVM later), incremental generation (content-hash skip), and **generated-code quality as the user's scalability story** — LAZY associations by default, explicit `.include()`, pagination-first query API, indexed FK columns by default.
- Schema file splitting (`import "user.vx"`) deferred until schemas grow.

### 6.3 Security
- Studio is the main attack surface (a data editor is an SQL-injection magnet): parameterized SQL only, localhost bind, token auth, CSRF, no reverse-proxy exposure.
- Credentials via env only; never logged/serialized; `db push` + destructive migrations refuse production-heuristic URLs unless forced.
- Supply chain: signed releases, Maven Central provenance, minimal deps, banned-dependency enforcement in runtime modules.

### 6.4 Performance Bottlenecks
- Full recompiles from timestamp-churn on unchanged generated files → content-hash-before-write (correctness requirement).
- CLI cold start (JVM) → GraalVM native binary (Phase 5); lazy subcommand loading meanwhile.

### 6.5 Previously-Open Questions — now Resolved
All five source unknowns are resolved; kept here with their reasoning as a decision record.
- **U1 — Spring Boot 4 target? → Boot 4.1 is the *primary* target, not "verify later."** Boot 3.5 reached OSS end-of-life 2026-06-30 (final OSS release 3.5.16, 2026-06-25); 4.1.0 is current (supported to 2027-07-31), 4.0 to 2026-12-26. Shipping a new tool onto an unpatched branch is an adoption-killer for exactly the enterprise Spring teams we want. The cost is low: `vx-core`/`vx-codegen`/`vx-migrate`/`vx-cli` (~80% of the code) have **zero** Spring dependency by design. Only three things couple to the Boot version — (1) generated entities use plain `jakarta.persistence`, stable across JPA 3.1→3.2 (essentially free); (2) **`vx-spring-boot-starter`** is where Boot 4's auto-config removals bite (cf. Boot 4.0 silently dropping Zipkin auto-config / ignoring `management.zipkin.tracing.endpoint` — assume similar surprises), so it is the one version-pinned artifact; (3) `vx-studio` is its own Boot app and just picks a version. **Action:** build against Boot 4.1 / Hibernate 7; add a CI matrix job compiling the *generated output* against Boot 3.5 to prove the entity/repository contract stays portable (a Boot 3.5 user can then use codegen + migrations; only the starter is pinned). Secondary win: Hibernate 7 is where Jakarta Data + compile-time query checking live — the Phase 3 query builder should build on the same version it differentiates against.
- **U2 — Metamodel in Phase 1? → No** (D3). Naming decided now: `UserFields`, not `User_` (avoids `hibernate-jpamodelgen` duplicate-class collision).
- **U3 — Native PG enums? → varchar+check is the *permanent* default** (D4), with an opt-in `enumStorage` knob. Decisive reason: `ALTER TYPE … ADD VALUE` can't run in Flyway's transactional migration; CHECK drop+recreate can.
- **U4 — Multi-schema timing? → Defer the feature, not the field** (D14). Nullable `schemaName` + `formatVersion` ship in Phase 1 so later addition doesn't invalidate existing snapshots.
- **U5 — Preserve existing Flyway numbering? → Reframed:** never *preserve* numbering, just never write into the existing range — D11 timestamps give this free. What matters is that **`migrate baseline` writes a truthful snapshot**: after writing `schema.vx` + snapshot, re-introspect the live DB, diff against the snapshot, and **refuse to complete unless that diff is empty**, printing exactly what didn't round-trip. This turns the weakest introspection coverage (missed partial index / check constraint / default expression → spurious `ADD` on the very next `migrate dev` → apply failure → lost trust on first contact) into a loud, early, honest error, and doubles as a free correctness harness for `db pull`.

*(No open unknowns remain blocking Phase 1. The one item explicitly recorded as a known limitation rather than fully solved is the mutable single-`snapshot.json` cross-branch collision — see D1, with the D13 `--verify` shadow-DB path as the planned fix.)*

---

## 7. Deliverables by Phase

### Phase 0
- **Objectives:** feel the pipeline; stand up build/CI.
- **Tasks:** throwaway spike; Prisma study notes; Maven skeleton; CI; license; standards config.
- **Outputs:** compiling multi-module skeleton, green CI, `docs/` seeded, throwaway spike (deleted).
- **Completion:** `mvn verify` runs on empty modules in CI; Spotless/error-prone/ArchUnit wired.

### Phase 1 (MVP)
- **Objectives:** `schema.vx → validate → generate` with excellent errors.
- **Tasks:** M1.1–M1.6 above.
- **Outputs:** `vx-core`, `vx-codegen`, `vx-cli` (init/validate/generate), `vx-maven-plugin`, `examples/demo-app`, golden-file + compile + diagnostic tests.
- **Completion:** stranger writes `schema.vx`, runs `mvn compile`, gets working entities/repos; all Phase 1 tests green; demo-app boots.

### Phase 2 (Migrations)
- **Objectives:** correct, safe, review-able Flyway migration generation + legacy on-ramp.
- **Tasks:** M2.1–M2.6.
- **Outputs:** snapshot store, `SchemaDiffer`, `PostgresRenderer`, `migrate dev/status/baseline/diff`, `db pull`, Testcontainers suite.
- **Completion:** `migrate dev` emits executable ordered SQL with rename/destructive handling; `db pull` round-trips; PG 14–17 tests green.

### Phase 2.5 (Deferred SDL)
- **Objectives:** composite keys + N-N.
- **Completion:** each supported end-to-end (parse→codegen→migrate→SQL→docs→tests).

### Phase 3 (Query builder)
- **Objectives:** type-safe fluent queries compiling to Criteria.
- **Outputs:** metamodel generator, `VantixClient`, fluent API, `raw()` hatch, starter auto-config.
- **Completion:** README example queries run against Testcontainers PG, return managed entities, correct SQL; `include` prevents lazy-init.

### Phase 4 (Studio + errors)
- **4a Objectives:** independently shippable error-translation starter. **Completion:** three exception types rewritten with ranked fixes + `VX-####` links; released standalone.
- **4b Objectives:** localhost data browser. **Completion:** browse/paginate/edit/add/FK-nav over JDBC with token+CSRF; works when app doesn't compile.

### Phase 5 (Polish)
- **Objectives:** ecosystem reach + v0.1 announcement.
- **Outputs:** Gradle plugin, `doctor`, DTO/mapper (opt-in), OpenAPI, `db push` (gated), docs site, GraalVM binary (Windows-verified), `.vx` highlighting, Maven Central release pipeline.
- **Completion:** v0.1 published to Maven Central, signed, docs live.

---

## 8. Implementation Checklist (step-by-step)

### Phase 0 — Foundations
- [x] Throwaway spike: parse 2 entities, print CREATE TABLE, emit one entity string *(in `/spike`, git-ignored; runs green)*
- [ ] Build + use a scratch Prisma app; capture DX notes *(developer exercise — not automatable; too late to shape Phase 1's error text, so do it **before Phase 2** and focus on `migrate dev`'s rename and destructive-change prompts)*
- [x] Parent `pom.xml` + all 11 modules from §2.2 (compiling; `mvn verify` green)
- [x] GitHub Actions CI: build/test/Spotless/error-prone/ArchUnit *(`.github/workflows/ci.yml`; both `verify` and `-Perror-prone verify` verified green locally)*
- [x] Apache-2.0 `LICENSE` (canonical text) + `NOTICE`, SPDX headers on every source, `CONTRIBUTING.md`, `.gitignore`
- [x] `maven-enforcer` (Java 21 / Maven 3.9 baseline + reactor convergence) + banned-Spring rules in `vx-core` & `vx-runtime`; `mvnw` committed (pinned 3.9.14)
- [x] `.gitattributes` (`* text=auto eol=lf`; `eol=lf` on golden fixtures) — landed before any golden test (D12)
- [x] CI default build targets Boot 4.1 / Hibernate 7; Postgres 14–17 matrix still scaffolded (commented) until Phase 2 (U1, D12)
- [ ] Boot-portability matrix job **green on GitHub Actions** *(enabled in `ci.yml`; the same command passed locally on Boot 3.5.16 / Hibernate 6.6 — enabled is not verified: tick after the first green CI run)*
- *Note:* error-prone runs via `.mvn/jvm.config` add-exports + the `error-prone` profile (JDK-21 needs `-XDaddTypeAnnotationsToSymbol=true`); Testcontainers 2.x uses `org.testcontainers:testcontainers-*` coordinates, pinned by importing `testcontainers-bom` **before** the Boot BOM so the Boot 3.5 portability build (which only knows TC 1.x) still resolves them.

### Phase 1 — Core
- [x] EBNF grammar spec in `docs/grammar.md` (before parser) *(v1 grammar; scalar types, attributes, `@@id`/`@@schema` flagged as rejected)*
- [x] Resolved `Schema`/`Entity`/`Field`/`Relation`/`EnumDecl` records + `SourcePosition` + **nullable `schemaName`** (D14) *(immutable records in `dev.vantix.core.model`; sealed `FieldType`/`DefaultValue`)*
- [x] `Schema` model JSON serialize/deserialize (round-trip tested) + **`formatVersion` int in snapshot** (D14) *(`SchemaMapper`, Jackson 2.21; 4 tests incl. exact round-trip, determinism, formatVersion-first)*
- [x] Parser rejects `@@schema` with "not yet supported" diagnostic (D14) *(also `@@id` (D6); both are kept in the AST so analysis does not pile on follow-up errors)*
- [x] Lexer with `SourcePosition` on every token *(hand-written `Lexer`; contextual keywords, `@@`/`@`, comments, error recovery; 11 tests + `Diagnostic`/`Severity` types)*
- [x] Recursive-descent parser → AST *(`dev.vantix.core.parser.Parser` → `dev.vantix.core.ast.Ast` records; lexer gained `FLOAT_LITERAL` and string escapes first, while nothing depended on the token stream)*
- [x] Error recovery + multi-error reporting with caret + "did you mean" *(member-level recovery inside entities, top-level resync otherwise; syntax errors right after a lexical error are suppressed as cascades; `Suggestions` = optimal-string-alignment distance)*
- [x] `Diagnostics` type + terminal renderer *(`Diagnostic` record + `DiagnosticRenderer`: `file:line:col`, source line, caret, suggestion; optional ANSI colour)*
- [x] Semantic analysis → `Schema` model (types, relations, `mappedBy`) *(`SchemaAnalyzer`; `SchemaCompiler` is the one-call front end; rules documented in `docs/grammar.md` § Semantics)*
- [x] Validation rules (dup names, unresolved relations, missing `@id`, bad attribute combos) *(plus Java/PostgreSQL reserved words, `java.lang` shadowing, generated-class clashes, default-value typing/range, FK type/nullability/`onDelete` consistency)*
- [x] Content-hash-aware source writer *(`SourceWriter`: byte-identical files untouched (mtime preserved), atomic writes, stale generated files pruned by marker only, scaffolds never overwritten, conflicts with hand-written classes reported)*
- [x] Entity generator (relations, fetch defaults) *(owning side `@ManyToOne`/`@OneToOne(fetch = LAZY)` + `@JoinColumn`; the FK scalar is a read-only mirror (`insertable/updatable = false`) whose getter prefers the relation; `@default` mirrored as Java initialisers)*
- [x] `equals`/`hashCode`/`toString` (id-based, proxy-safe) + exhaustive tests *(effective class via `HibernateProxy.getHibernateLazyInitializer().getPersistentClass()` rather than `Hibernate.getClass()`, which would initialize the proxy; verified with real proxies against PostgreSQL in the demo app. `Hibernate.getClassLazy()` (present in 6.6 and 7.4) was considered and rejected: its bytecode throws `LazyInitializationException` for a detached uninitialized proxy and initializes the proxy when the entity has subclasses. The equality matrix covers proxy/proxy, proxy/loaded both ways, `HashSet.add(proxy)` without a load, detached vs managed, two unsaved instances, and hash stability across persist; a deliberate `other.id` mutation makes two of those tests fail)*
- [x] Repository generator + derived `findByX` for `@unique` *(returns `Optional`; skips `Bytes`/`Json`)*
- [x] Generation Gap (`useGenerationGap`) support *(`abstract @MappedSuperclass XBase` regenerated; `@Entity @Table X extends XBase` scaffolded once into `src/main/java`; a scaffold whose `@Table` drifts from the schema gets a warning)*
- [x] `vantix init` / `validate` / `generate` / `generate --watch` *(shared `Workflow`; `--json`, `--verbose`, `--no-color`, `--schema`, `-C`; `init` derives the package from the `@SpringBootApplication` class)*
- [x] `vx-maven-plugin` `GenerateMojo` bound to `generate-sources` *(runs `Workflow.generate`, adds the compile source root; plus `vantix:init`. Per D8 the `outputDirectory` parameter was removed: `generator.output` in `schema.vx` decides)*
- [x] `examples/demo-app` boots on generated code *(plugin-generated, Testcontainers PostgreSQL 17; `ddl-auto=create` until Phase 2 emits migrations; also green on Boot 3.5.16 / Hibernate 6.6)*
- [x] Golden-file harness + Java Compiler API smoke test *(3 cases: shop, generation-gap, kitchen-sink; compiled against real JPA/Hibernate/Spring Data with `-Xlint:all` and zero warnings; `-Dvantix.updateGolden=true` regenerates)*
- [x] Parser diagnostic-corpus tests *(67 cases asserting exact rendered text, lexer through semantic errors)*
- [x] ArchUnit module-boundary tests green *(5 rules: + core layering, codegen names-never-loads JPA/Hibernate/Spring, Spring-free toolchain)*

**Phase 1 review follow-ups (done):**
- [x] Secondary source locations on diagnostics (rustc-style): a type mismatch points at the FK declaration, with the `@relation` use as context; duplicates show "first declared here"
- [x] Strict number literals: `.5`, `1.`, `1e5`, `10L`, and leading zeros (octal in generated Java) are errors with the fix
- [x] `@id` with `@default` is an error (a Java-side id default makes `save()` merge and breaks unsaved-instance equality)
- [x] `generator.output` must stay inside the project (absolute paths and `..` rejected); resolved against the project base directory
- [x] Warning on inverse one-to-one fields (Hibernate cannot lazy-load them: 1 + N queries, measured with Hibernate statistics in the demo app)
- [x] Orphaned Generation Gap scaffolds (entity removed/renamed) reported by Vantix, not by javac
- [x] FK mirror getter documents `setX(repository.getReferenceById(id))`; no setter, no `@NotNull`
- [x] `Locale.ROOT` for every case conversion and number format; generation checked under `tr-TR`, `ar-SA`, `hi-IN`
- [x] Stranger integration test (`vx-maven-plugin/src/it/stranger-app`, maven-invoker-plugin): blank Boot app, `vantix:init`, `compile`, then `compile` again with nothing regenerated or recompiled

**Phase 1 DoD met:** `./mvnw verify` builds all 11 modules and runs 213 tests (core 140, codegen 21, CLI 20, plugin 4, ArchUnit 5, demo app 23) plus the stranger-app invoker IT; `-Perror-prone verify` clean. Only the Phase 0 Prisma study remains open (a developer exercise).

### Phase 2 — Migrations
- [ ] **Exit criterion:** the demo app drops `ddl-auto=create`, applies the Vantix-generated Flyway migrations and runs with `ddl-auto=validate` (today it exercises Hibernate's DDL, not Vantix's, so FK indexes, column lengths and defaults are not yet cross-checked)
- [ ] Decide before release how to treat inverse one-to-one fields: keep the warning, stop generating that side, or support bytecode enhancement
- [ ] Snapshot write/read (`vantix/snapshot.json`)
- [ ] `sealed interface SchemaChange` + record variants
- [ ] `SchemaDiffer` → typed change list (pure, debug decision trail)
- [ ] Rename detection + interactive prompt + `renameStrategy`
- [ ] Persist rename decisions in snapshot
- [ ] Destructive classification + guard (`--allow-destructive`, commented-out default, CI non-zero)
- [ ] `PostgresRenderer` (`enumStorage` knob, default varchar+check; enum-add = CHECK drop+recreate, never `ALTER TYPE ADD VALUE`; Json→jsonb)
- [ ] Topological DDL ordering (+ reverse for drops)
- [ ] Flyway `V<timestamp>__slug.sql` writer → `db/migration`
- [ ] `migrate dev [--name][--dry-run][--allow-destructive][--verify]`
- [ ] `migrate dev --verify`: shadow-DB replay + diff (default-on in CI, opt-in local) (D13)
- [ ] `migrate status`
- [ ] `migrate baseline` — **self-verifying** (re-introspect, diff, refuse unless empty) (U5)
- [ ] `information_schema` introspector → model
- [ ] `db pull` → writes `schema.vx`
- [ ] `migrate diff --against-db` drift check
- [ ] Differ unit + round-trip property tests
- [ ] SQL renderer Testcontainers tests (PG 14–17 in CI; single version locally)
- [ ] Introspection round-trip test green

### Phase 2.5 — Deferred SDL
- [ ] `@@id` composite keys end-to-end
- [ ] N-N relations (join-table synthesis) end-to-end
- [ ] Composite `@@unique` / `@@index` end-to-end

### Phase 3 — Query builder
- [ ] README query examples written first (API-by-docs)
- [ ] Metamodel generator (`UserFields.EMAIL` — **not `User_`**, D3; `.dot()`)
- [ ] Generated `VantixClient` facade
- [ ] Criteria compilation: where/predicates/orderBy/page/include
- [ ] `vantix.raw(sql, Type.class)` escape hatch (documented)
- [ ] `vx-spring-boot-starter` auto-config `VantixClient`
- [ ] Predicate unit tests + Testcontainers SQL/identity tests

### Phase 4 — Studio & errors
- [ ] Error-translation starter: LazyInit, constraint violation, non-unique result → ranked fixes + `VX-####`
- [ ] Release error starter standalone (+ blog post)
- [ ] Studio backend: localhost bind, token, CSRF, parameterized JDBC
- [ ] Studio frontend: browse/paginate/edit/add/FK-nav
- [ ] `vantix studio` on `:5555`

### Phase 5 — Polish
- [ ] Gradle plugin
- [ ] `vantix doctor`
- [ ] DTO/mapper generation (opt-in)
- [ ] OpenAPI annotations
- [ ] `db push` (gated to non-prod URLs)
- [ ] Docs site
- [ ] GraalVM native CLI (Windows-verified — **requires MSVC build tools installed separately**, D12)
- [ ] `.vx` IntelliJ syntax highlighting
- [ ] Maven Central release: signing, provenance, `0.1.0` tag + announcement
