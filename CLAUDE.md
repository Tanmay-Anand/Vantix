# Vantix

A Prisma-style developer-experience toolchain for Spring Boot — one `schema.vx` file drives entity generation, Flyway migration generation, a type-safe query API, and a local data browser. Runs on top of Hibernate/Spring Data JPA, not instead of them.

## Project Status

Pre-alpha. **Phases 0 and 1 are done**: `schema.vx` → lexer → parser → semantic analysis →
entities + repositories via `vantix init|validate|generate [--watch]` and the Maven plugin; the demo
app boots on generated code against PostgreSQL. Phase 2 (migrations) is next. `internal-docs/IMPLEMENTATION_PLAN.md`
(git-ignored, private; never reference it from public files) §8 is the live checklist; `docs/grammar.md` is the normative language spec (grammar + semantics).

## Working in this repo

- Build everything as CI does: `./mvnw verify` (tests, Spotless, enforcer, ArchUnit; the demo app's
  Testcontainers tests need Docker and are skipped without it). Static analysis: `./mvnw -Perror-prone verify`.
- Format before committing: `./mvnw spotless:apply`.
- Building one module needs its upstream modules: `-pl <module> -am` (plain `-pl` trips the
  enforcer's reactor-convergence rule).
- Diagnostic wording and generated code are pinned by golden files (`vx-core/src/test/resources/diagnostics/`,
  `vx-codegen/src/test/resources/golden/`). After an intentional change: `./mvnw test -Dvantix.updateGolden=true`,
  then review the diff like code.

## Tech Stack

- **Language:** Java 21 (records, sealed interfaces, pattern matching)
- **Parser:** Hand-written lexer + recursive-descent (no ANTLR)
- **Code generation:** JavaPoet (Palantir fork)
- **CLI:** picocli
- **Build integration:** Maven plugin (`generate-sources` phase)
- **ORM target:** Hibernate 7 via Spring Data JPA (Boot 4.1); generated code also CI-tested on Hibernate 6.6 / Boot 3.5
- **Migrations:** Flyway (Vantix emits SQL, Flyway runs it)
- **Database (v1):** PostgreSQL only
- **Studio frontend:** React 19, TypeScript 5, Tailwind CSS v4
- **Testing:** JUnit 5, Testcontainers, golden-file snapshots

## Module Layout (planned)

```
vantix/
├── vx-core/          # Lexer, parser, AST, semantic analysis, resolved Schema model
├── vx-codegen/       # Schema model → Java source via JavaPoet
├── vx-migrate/       # Schema diff → Flyway SQL (snapshot-based, not live-DB diff)
├── vx-runtime/       # Ships in user's app: query builder + Hibernate error translator
├── vx-cli/           # picocli commands
├── vx-maven-plugin/  # Binds generate to generate-sources phase
├── vx-spring-boot-starter/
├── vx-studio/        # Spring Boot + React data browser (localhost only)
├── examples/demo-app/
└── docs/
```

## Key Design Decisions

- **Build on Hibernate, don't replace it.** Generated code is plain Spring/JPA — users can eject at any time.
- **Emit Flyway SQL, don't run migrations.** Users review SQL before Flyway applies it.
- **Snapshot diffing** (not live-DB diffing) — deterministic, works offline.
- **Generated static metamodel** (`UserFields.EMAIL`, never `User_`, which clashes with `hibernate-jpamodelgen` — D3) not lambda reflection — better IDE support, GraalVM-safe.
- **Generation Gap pattern** — emit `abstract UserBase`, scaffold `User extends UserBase` once, never touch it again.
- **Generated sources go in `target/`**, never in VCS.

## Implementation Phases

1. **Core:** SDL grammar, lexer, parser, semantic analysis, entity/repo codegen, Maven plugin
2. **Migrations:** Snapshot diff, SchemaDiffer, PostgresRenderer, `migrate dev`, `db pull`
3. **Query builder:** Metamodel generator, fluent API → JPA Criteria
4. **Studio + error translation:** localhost data browser, Hibernate exception rewriter
5. **Polish:** Gradle plugin, GraalVM native CLI, docs site, IntelliJ syntax highlighting

## Hard Problems to Keep in Mind

- **Rename detection is undecidable** — always prompt the user; never guess.
- **Destructive changes** (`DROP COLUMN`, `DROP TABLE`) require `--allow-destructive`; emit commented-out otherwise.
- **Entity `equals`/`hashCode`** must use id-based equality with proxy unwrapping — a generator amplifies mistakes across every user's domain model.
- **Incremental builds** — hash generated content and skip identical writes to avoid triggering full recompiles.
- **SDL feature creep** — every new construct costs parser + validator + codegen + differ + SQL + docs; say no by default.

## Testing Approach

- Parser: table-driven tests including a large corpus of invalid inputs asserting on exact diagnostic text.
- Codegen: golden-file snapshots + Java Compiler API smoke test.
- Differ: pure unit tests (no DB); round-trip property tests.
- SQL rendering + `db pull`: Testcontainers against real PostgreSQL 14–17.
- End-to-end CI: `init → generate → migrate → boot → query` on the demo app.

## Security Notes

- Studio binds to `localhost` only with a random session token; never expose via reverse proxy.
- Credentials via environment variables only — never log or embed connection strings.
- `db push` is prototyping-only; gate it to local dev.
- In production: `spring.jpa.hibernate.ddl-auto: validate`, never `update`.
