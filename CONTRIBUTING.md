# Contributing to Vantix

Thanks for your interest in Vantix. This document covers the build, the coding standards the CI
enforces, and the module boundaries you must respect.

## Prerequisites

- **Java 21** (exactly — the build enforces `[21,22)`). e.g. `sdk install java 21-tem`.
- **Maven 3.9+** — or use the committed wrapper `./mvnw`.
- **Docker** — required for the Testcontainers-based migration/introspection tests (Phase 2+).
- **Node.js 20+** — only if building Vantix Studio's frontend (Phase 4).

## Build

```bash
./mvnw verify
```

This compiles every module, runs unit tests, checks formatting (Spotless), and runs the ArchUnit
module-boundary tests.

Auto-fix formatting before committing:

```bash
./mvnw spotless:apply
```

Run the extra static-analysis pass (error-prone) the way CI does:

```bash
./mvnw -Perror-prone verify
```

> error-prone is intentionally **not** on the default build. It stays behind the `error-prone`
> profile so the local inner loop is fast and free of the JDK-21 `--add-exports` fragility; CI runs
> the profile on every push.

## Coding standards

- **Java 21**: records for AST nodes and `SchemaChange` types; sealed interfaces for closed
  hierarchies (exhaustive `switch`); package-by-feature within each module.
- **Formatting**: palantir-java-format via Spotless — non-negotiable, CI-gated.
- **SPDX headers**: every source file starts with the Apache-2.0 SPDX header (see any existing
  file).
- **Immutability**: prefer immutable records; the resolved `Schema` model is immutable by contract.

## Module boundaries (enforced by `vx-architecture-tests`)

The single most important architectural rule: **the compile-time toolchain must never leak into the
thin runtime that ships inside user applications.**

| Module | May depend on | Must NOT depend on |
| ------ | ------------- | ------------------ |
| `vx-core` | Jackson (JSON only) | Spring, JavaPoet, picocli |
| `vx-runtime` | Jakarta Persistence API | Spring, picocli, JavaPoet, any other `vx-*` toolchain module |
| `vx-codegen` | `vx-core`, JavaPoet | Spring |
| `vx-migrate` | `vx-core`, JDBC | Spring |
| `vx-cli` | `vx-core`, `vx-codegen`, `vx-migrate`, picocli | Spring |
| `vx-spring-boot-starter` | `vx-runtime`, Spring Boot 4 | the toolchain modules |

`vx-core` and `vx-runtime` additionally have Maven `bannedDependencies` rules that fail the build if
Spring appears on their classpath.

## Target runtime

Vantix targets **Spring Boot 4.1 / Hibernate 7** as its primary runtime. Only
`vx-spring-boot-starter` is version-pinned to Boot 4; the generated entity/repository contract stays
portable and is checked against Boot 3.5 in a CI matrix job. Boot 3.5 reached OSS end-of-life on 2026-06-30, which is why
Boot 4.1 is the primary target.

## Commit / PR conventions

- Keep generated sources out of commits (`target/`, `**/generated-sources/` are git-ignored).
- Never edit an applied migration; correct it with a new one.
- CI must be green (build + error-prone) before review.
