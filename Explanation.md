# Vantix — Explanation

> **Status: pre-alpha. Phases 0 and 1 are done; Phase 2 (migrations) is next.** A `schema.vx` file
> goes through a hand-written lexer, a recursive-descent parser and a semantic analyzer, and comes
> out as JPA entities and Spring Data repositories — via `vantix init | validate | generate [--watch]`
> or simply `mvn compile` through the Maven plugin. About 5,900 lines of main Java and 2,400 of tests.
>
> **What is verified, by running it:** `./mvnw verify` builds 11 reactor modules and runs 213 tests,
> including 73 invalid schemas pinned to their exact error text, generated code compiled with every
> javac warning enabled, and a demo app that boots on generated code against PostgreSQL 17. A blank
> Spring Boot app goes `vantix:init` → `compile` → `compile` with nothing regenerated or recompiled
> the second time. The same demo also passes on Boot 3.5 / Hibernate 6.6 (locally).
>
> **What doesn't exist yet:** migrations (snapshot store, differ, SQL renderer, `migrate dev`,
> `db pull`), the query builder and its `UserFields` metamodel, Studio, and the error translator.
>
> **Honest caveats:** the Boot 3.5 CI job has never run on GitHub; until Phase 2 the demo lets
> Hibernate create its tables, so Vantix's own DDL is not yet cross-checked against the database;
> and the Prisma study the plan asked for hasn't been done.

---

## 1. The One-Liner

Vantix lets you describe your database in one readable file, and then writes the Java classes, the
database migration scripts, and the query code for you — so the three can't drift out of sync,
which is the thing that actually goes wrong.

---

## 2. The Problem

### The three-places problem

The README's opening is the clearest statement of it in this portfolio:

> "Your domain model lives in three places at once. The Java entity says one thing, the Flyway
> migration says another, and the actual database has drifted from both."

Adding one column means editing an entity, hand-writing `V17__add_column.sql`, updating a DTO,
updating a mapper, updating a repository method, "and hoping you didn't typo a column name that only
fails at runtime."

### The tooling problem underneath it

> "You have Lombok, MapStruct, Flyway, springdoc, JPA Buddy, and QueryDSL — six tools with six
> mental models and no shared source of truth."

Each is good. None of them knows about the others. The typo that fails at runtime is a *consequence*
of there being no single artifact that all six derive from.

### Why "just use Prisma" isn't the answer

Prisma solved exactly this for TypeScript — one schema file, generated client, generated migrations.
The Java ecosystem has all the individual pieces and no equivalent unification. But the naive port
would be to replace Hibernate, and that's the trap: Hibernate is the most battle-tested ORM in the
industry and its persistence context, dirty checking, and caching are not things a new project
should reimplement.

So the positioning is precise: **"On top of Hibernate, not instead of it."** Everything heavy
happens at build time; at runtime your app is plain Spring Data JPA.

---

## 3. How It Works — Plain English

### The analogy

Think of building a house from a single architectural drawing.

Today's situation is that you have three documents: the architect's drawing, the builder's
worksheet, and the actual house. Every change means updating all three by hand. Inevitably the
drawing shows a window the builder never cut, and nobody notices until someone tries to open it.

Vantix says: **there is one drawing, and everything else is printed from it.** The builder's
worksheet isn't maintained — it's generated. The materials list isn't maintained — it's generated.
Change the drawing, reprint everything.

Two details make this workable rather than naive.

**The house already exists and has people living in it.** You can't reprint a house. So when the
drawing changes, the tool doesn't produce a new house — it produces a *change order*: "knock through
here, add a window there." And a change order that says "demolish the east wing" is not something
you execute because a drawing changed; the tool writes it out commented and makes you say so
explicitly.

**Sometimes the change is ambiguous, and no amount of cleverness resolves it.** The old drawing has
a room called "study." The new one has a room called "office," same size, same position. Did you
*rename* the study, or *demolish* it and build an office? Those produce identical drawings and very
different outcomes for the furniture inside.

**No algorithm can tell.** The answer exists only in your head. So the tool asks — and writes your
answer down, so it never asks twice.

### Tracing one change

You add `phoneNumber String?` to the `User` entity in `schema.vx`.

1. **`mvn compile`.** The Maven plugin runs in `generate-sources`, before your code compiles.
2. **Lex and parse** `schema.vx`. Errors come out compiler-grade — line, column, caret, "did you
   mean" — and multiple errors are reported in one pass, not one-at-a-time.
3. **Generate** the JPA entity and the Spring Data repository into `target/generated-sources` —
   never into version control. Unchanged files are left alone, so an unrelated build recompiles
   nothing. (A typed metamodel, `UserFields.PHONE_NUMBER`, arrives with the query builder in Phase 3.)
4. **Your code compiles** against the freshly generated classes. A typo in a column name is now a
   compile error rather than a runtime one.
5. **`vantix migrate dev`** *(Phase 2 — designed, not built).* The tool diffs your schema against
   a *committed snapshot* of the last migrated state — not against the live database — and emits a
   standard Flyway file with a timestamp version, `V20260925143012__add_user_phone_number.sql`, so
   two branches can never pick the same number.
6. **You read the SQL** before it runs. Vantix doesn't apply it; Flyway does, on your schedule.
7. Had you *removed* a column instead, the `DROP COLUMN` would be emitted **commented out** unless
   you passed `--allow-destructive`.

---

## 4. How It Works — Technical

### 4.1 The module graph

```
                       schema.vx  ← single source of truth
                            │
┌────────── vx-cli (picocli) ─────────────┐   ✅ init · validate · generate [--watch]
│  shared Workflow ◄── vx-maven-plugin    │      (the plugin calls the same code path)
│  migrate · db pull · studio · doctor    │   ❌ later phases
└────────────────┬────────────────────────┘
                 │
┌──── vx-core ───▼──────────────────────────────┐   ✅ all of it
│  Lexer ──► Parser ──► SchemaAnalyzer ──►      │
│             (AST)      Resolved Schema Model  │
│  Diagnostics: caret, related locations,       │
│               did-you-mean                    │
└───┬─────────────────────┬─────────────────────┘
    │                     │
┌───▼──── vx-codegen ─┐ ┌─▼──── vx-migrate ────────┐
│ ✅ entities         │ │ ❌ Phase 2               │
│ ✅ repositories     │ │  snapshot → differ →     │
│ ✅ SourceWriter     │ │  ordered DDL → Flyway    │
│ ❌ UserFields (P3)  │ │                          │
└─────────┬───────────┘ └──────────────────────────┘
          │
   target/generated-sources/vantix   ← never in VCS
          │
┌─────────▼──── YOUR APP ──────────────────────────┐
│  plain Spring Data JPA on Hibernate              │
│  + vx-runtime  ← the ONLY shipped artifact (P3+) │
└──────────────────────────────────────────────────┘
```

Eleven reactor modules: eight `vx-*` modules, the ArchUnit tests, the demo app, and the parent.
Core, codegen, CLI and the Maven plugin are real; migrate, runtime, starter and studio are still
placeholders for their phases.

### 4.2 What's actually implemented

**The front end (`vx-core`, ~3,700 lines).** A hand-written lexer that *collects* diagnostics rather
than throwing, with contextual keywords (`entity` can be a field name) and deliberately strict
numbers: `010`, `.5`, `1e5` and `10L` are errors with the fix, because numbers flow verbatim into
generated Java, where `010` means eight. A recursive-descent parser that recovers per line inside an
entity and per declaration at the top level, and suppresses syntax errors that are only echoes of a
lexical one. A semantic analyzer that resolves types, pairs both sides of every relation and infers
`mappedBy`, and checks what the grammar can't: foreign-key types and nullability, `onDelete`
consistency, reserved words in Java and PostgreSQL, generated-class clashes, and an `output` path
that must stay inside the project.

**Diagnostics, rustc-style.** Every error carries a primary location (where the fix goes), optional
secondary locations, and a suggestion:

```
error: Type mismatch: `Order.userId` is `Int` but `User.id` is `Long`
  --> vantix/schema.vx:11:12
   |
11 |   userId Int
   |          ^^^ change `userId` to `Long`
   |
10 |   user   User @relation(fields: [userId], references: [id])
   |                                  ------ used as the foreign key here
```

**Code generation (`vx-codegen`, ~1,100 lines).** JavaPoet entities with owning/inverse mappings,
`LAZY` associations, Java-side mirrors of `@default`, and a read-only foreign-key field (no setter:
the relation owns the column). Repositories with `findByX` for every `@unique` field. The Generation
Gap pattern on request. A `SourceWriter` that leaves byte-identical files untouched, writes
atomically, deletes only files carrying its own marker, never overwrites a scaffold, and reports
hand-written classes that would clash — or scaffolds orphaned by a removed entity — in its own words
rather than javac's.

**One code path for CLI and Maven.** `Workflow.generate` is what both `vantix generate` and
`mvn compile` run, and all configuration lives in `schema.vx` (decision D8), so the two cannot drift.

**Tests.** A 73-case diagnostic corpus asserting exact rendered text; golden-file snapshots of
generated code, which is also compiled against real JPA, Hibernate and Spring Data with `-Xlint:all`
and must produce zero warnings; generation checked under Turkish, Arabic and Hindi locales; a demo
app on Testcontainers PostgreSQL; a maven-invoker "stranger" test; and ArchUnit rules, including one
that codegen only *names* JPA/Hibernate/Spring types and never loads them.

### 4.3 Snapshot diffing, not live-DB diffing *(Phase 2 design)*

The most consequential architectural decision, stated in `CLAUDE.md` as:

> "**Snapshot diffing** (not live-DB diffing) — deterministic, works offline."

Vantix will commit a snapshot of the last-migrated schema state to version control. `migrate dev`
diffs `schema.vx` against *that file*, not against a database. The resolved model and its JSON form
already exist and round-trip exactly, with a `formatVersion` written first so the format can evolve.

```
schema.vx (desired)  ──┐
                       ├──► SchemaDiffer ──► ordered DDL ──► V<timestamp>__*.sql
snapshot.json (last) ──┘                                     (Flyway runs it)

vs. the alternative:

schema.vx (desired)  ──┐
                       ├──► diff ──► DDL     ← needs a live DB, and which one?
live database        ──┘
```

Three things fall out. It works offline and in CI. It's deterministic — two developers on the same
commit generate byte-identical migrations. And it's reviewable, because the snapshot is a file in
the pull request. Drift against reality doesn't disappear; it becomes a *separate, explicit*
command: `vantix migrate diff --against-db`.

### 4.4 The generation contract

Three rules that together make the tool survivable — all implemented now:

- **Generated sources go in `target/`, never in VCS.** Regenerated every build.
- **Generation Gap pattern** — emit `abstract UserBase`, scaffold `User extends UserBase` once,
  never touch it again. Java has no partial classes, so this is the only way to let users add
  behaviour without their edits being overwritten.
- **Skip identical writes**, because rewriting unchanged files with fresh timestamps forces
  recompiles. Verified end to end: the second `mvn compile` of an unchanged project reports
  "Nothing to compile". The scope is narrower than it sounds: Maven recompiles a whole module when
  any file changes, so this makes no-op builds free, not per-entity recompiles possible.

> "This is the single most common way code generators die, so it's decided up front rather than
> patched later."

---

## 5. The Core Concept: Deriving Imperative Migrations From a Declarative Schema

The hard idea, and the one worth a whiteboard. Six moves.

### 5.1 Two incompatible descriptions of the same thing

`schema.vx` is **declarative**: it says what the database *should look like*. A migration is
**imperative**: it says what to *do*. And the database is **stateful and historical** — it contains
data that must survive.

Generating the first from the second is trivial (run the migrations, observe the result). Generating
the second from the first is the whole problem, because *a desired end state does not determine the
path to it.*

### 5.2 Why you can't just recreate

The naive answer to "make the database match this file" is to drop everything and rebuild. Correct,
and unusable — the data is the point.

So you must compute a **minimal transformation** from a previous state to a desired state. Which
means you need the previous state, and here the design makes its first real decision: keep it as a
committed **snapshot** rather than reading the live database.

The alternative — diff against a real database — sounds more truthful and is worse. *Which* database?
The developer's laptop has different drift from staging. Two developers on the same commit produce
different migrations. It can't run in CI or offline. And the generated migration depends on the
state of whatever machine happened to run it, which is the opposite of reproducible.

Snapshot diffing makes migration generation a **pure function of two files**, both in version
control. That's what makes it deterministic, reviewable in a pull request, and runnable anywhere.

### 5.3 The undecidability at the centre

Now the hard part, and the design states it exactly right:

> "**Rename detection is undecidable.** If `name` disappears and `fullName` appears, no algorithm can
> know whether that's a rename or a drop-and-add — the answer lives in the developer's head."

Consider the two schemas:

```
   before:  User { id, name String }
   after:   User { id, fullName String }
```

Two valid interpretations, producing very different SQL:

```sql
-- interpretation A: rename        -- data preserved
ALTER TABLE users RENAME COLUMN name TO full_name;

-- interpretation B: drop and add  -- data destroyed
ALTER TABLE users DROP COLUMN name;
ALTER TABLE users ADD COLUMN full_name varchar;
```

The two input schemas are *identical* under both interpretations. The information distinguishing
them was never written down — it exists only as the developer's intent.

This is a genuine impossibility, not a hard problem awaiting a better algorithm. Heuristics can
*guess* — same type, similar name, same position — and the design's verdict on that is the sharpest
line in the document:

> "Any tool that claims to solve this automatically is guessing with your data."

### 5.4 The right response to undecidability

Three options when an algorithm cannot decide:

1. **Guess.** Fast, silent, and occasionally destroys a production column.
2. **Refuse.** Safe and useless — every rename becomes manual SQL.
3. **Ask, default safe, and remember.**

Vantix takes the third, and each clause is doing work:

- **Ask** — an interactive prompt, because the human genuinely holds information the tool doesn't.
- **Default safe** — if unanswered, the non-destructive interpretation wins.
- **Remember** — *"records the decision in the snapshot so it never asks twice."* Without this,
  prompting would be unbearable; every subsequent migration would re-ask about the same rename.

That last clause is what makes the approach viable rather than merely correct. **The snapshot isn't
just a record of schema state — it's a record of resolved ambiguities.** It accumulates the answers
to questions only a human could answer, which is why it must be committed to version control
alongside the code.

### 5.5 The same principle, applied to destruction

Renames aren't the only place where the schema underdetermines the action. Removing a field from
`schema.vx` unambiguously implies `DROP COLUMN` — the diff is clear. What's *not* clear is whether
you meant it.

> "`DROP COLUMN` / `DROP TABLE` require `--allow-destructive`; emitted commented-out otherwise."

Note the design: not blocked, not silently executed — **emitted, commented out.** You see exactly
what would happen, in the migration file, and uncommenting is a deliberate act recorded in the diff.

The distinction generalises: *the tool computes what follows from the schema, and refuses to
unilaterally act on the irreversible subset.*

### 5.6 Why this is the right shape

Step back and the migration engine is doing something specific: it is **a compiler for a language
with an undecidable fragment.**

Most of the translation is mechanical and should be automatic. A small fragment is genuinely
undecidable and must be escalated to a human. And the design's contribution is not solving the
undecidable part — it's *identifying exactly where the boundary is* and building the escalation into
the workflow so the tool stays trustworthy on the mechanical 95%.

That's the same shape as everything else in this portfolio: Sheaf's closed algebra refusing what it
cannot verify, PNAP's `UNPROVEN`, Rune's review queue, Praxis-Chess handing the engine every decision
it can make. **Know which questions your tool cannot answer, and make the not-answering visible.**

---

## 6. Key Decisions & Tradeoffs

**On top of Hibernate, not instead of it.** *Alternative:* a new ORM with its own runtime. *Why:*
Hibernate's persistence context, dirty checking and caching represent two decades of edge cases.
*Cost:* you inherit Hibernate's model, including `LazyInitializationException` — which the design
addresses with explicit `.include()` fetch joins rather than by escaping Hibernate.

**Ejectability as a first-class property.** *"Generated code is plain, readable Spring code with no
proprietary runtime — delete Vantix and keep working."* *Why:* it's the honest answer to "what if
this project dies?", which is the first question anyone sensible asks about a code generator.
*Cost:* it constrains generation permanently — no clever runtime tricks, nothing that only works
with `vx-runtime` present.

**Emit Flyway SQL; don't run migrations.** *Why:* the user reviews the SQL before it executes, and
Flyway is already the ecosystem standard. *Cost:* a two-step workflow rather than one command.

**Snapshot diffing over live-DB diffing.** Covered in §5.2. *Cost:* the snapshot can diverge from
reality, so drift detection becomes a separate explicit command.

**Hand-written lexer + recursive descent, no ANTLR.** *Why:* compiler-grade diagnostics — line,
column, caret, "did you mean" — are hard to get from a generated parser, and error *recovery* is
the point. *Cost:* you write and maintain every rule yourself — the Phase 0 lexer shipped without float
literals or string escapes, found by probing it before any parser depended on its tokens.

**Generated static metamodel, not lambda reflection — named `UserFields`, never `User_`** *(Phase 3)*.
*Why:* better IDE support and GraalVM-safe; and `User_` is exactly the class Hibernate's own
`jpamodelgen` emits, so two generators would collide with a duplicate-class error. *Cost:* more
generated files, and a naming choice that is hard to change after release — which is why it was
fixed before any of it was built.

**Proxy-safe `equals` from the proxy's own bookkeeping.** The plan said `Hibernate.getClass()`; that
initializes the proxy. `Hibernate.getClassLazy()` looked like the fix, but its bytecode throws
`LazyInitializationException` on a detached proxy and loads the row when the entity has subclasses.
The generated code asks the proxy's `LazyInitializer.getPersistentClass()` and compares ids through
`getId()`. *Cost:* in a future inheritance hierarchy, `getPersistentClass()` is the declared type, not
the subclass, so equality would need revisiting.

**Read-only foreign-key field.** `Order.userId` mirrors `Order.user`; only the relation writes the
column (two writable mappings of one column is a Hibernate startup error). *Why no setter:*
`setUserId(5L)` would look like it worked and write nothing. *Cost:* users coming from Prisma must
learn `setUser(userRepository.getReferenceById(5L))`, so the getter's Javadoc says so.

**Generation Gap pattern.** *Why:* Java has no partial classes, and *"this is the single most common
way code generators die."* *Cost:* every entity is two classes, and the scaffolded one is
hand-maintained forever.

**PostgreSQL only in v1.** Also out of scope: other databases, Kotlin, a custom migration runner,
replacing any part of Hibernate's runtime, a full IntelliJ plugin. *Why:* each of those multiplies
the differ and renderer surface. The stated rule is *"SDL feature creep — every new construct costs
parser + validator + codegen + differ + SQL + docs; say no by default."*

---

## 7. Rubber Duck Walkthrough

*Following one mistake through the code that exists.*

"I write `userId Int` where `User.id` is `Long`, and I misspell `Cascade` in the same `@relation`.
Then `mvn compile`.

The Maven plugin doesn't have its own logic — it calls `Workflow.generate`, the same method
`vantix generate` calls. So whatever I see here, I'd see from the CLI too.

The lexer runs first and is happy: identifiers, brackets, a colon. It would have complained if I'd
written `@default(010)`, because that becomes the Java literal `010`, which is octal — eight.

The parser builds the AST. Every node keeps its `SourcePosition`, which is what makes a caret
possible later. Nothing is resolved yet: `Int` is just a name.

The analyzer does two passes. First it resolves every scalar field in every entity, so that when it
reaches a `@relation` it can look up both ends. Then it resolves owning relations: `fields:
[userId]` points at `Order.userId`, `references: [id]` at `User.id`. The types differ, so that's an
error — and I put the caret on the `userId Int` *declaration*, because that's the line I'll edit,
with the `@relation` shown as a secondary location. `Cascde` isn't an `onDelete` action; the
did-you-mean engine, an edit distance that counts a swapped pair of letters as one edit, offers
`Cascade`. Both errors come out of one run. Nothing stops at the first problem.

There are errors, so there's no model and nothing is written — `target/` doesn't change, and the
build fails with the same rendered text the CLI prints.

I fix both and build again. Now the model exists and codegen runs. `Order` gets `@ManyToOne(fetch =
LAZY)` with a `@JoinColumn(name = "user_id")`, and `userId` becomes a read-only mirror whose getter
prefers `user.getId()` — correct before the insert is flushed, and `getId()` on a lazy proxy doesn't
load it. `User.orders` gets `@OneToMany(mappedBy = "user")`; the analyzer inferred that, because
exactly one owning relation points back.

`equals` is the part I'd defend hardest. It compares the proxy-aware class, then `getId()` —
never `other.id`, which is null on an uninitialized proxy. The method is `final`, so Hibernate's
proxy can't intercept it: even `proxy.equals(entity)` doesn't load the row. I don't trust that
because it reads well; the demo app compares real proxies against PostgreSQL, and when I changed the
generator to emit `other.id`, two of those tests failed. That's how I know they test the right
thing.

Last, `SourceWriter`. The files it would write are byte-identical to what's on disk, so it writes
none of them, their timestamps don't move, and javac says 'Nothing to compile'."

---

## 8. Prerequisite Concepts

**ORM / JPA / Hibernate.** An *object-relational mapper* maps database rows to objects. *JPA* is the
Java specification; *Hibernate* is the dominant implementation. Spring Data JPA layers repository
interfaces on top.

**Flyway.** A migration runner. You write versioned SQL files (`V17__add_column.sql`) and it applies
unapplied ones in order, tracking what it has run. Vantix *generates* those files; Flyway still runs
them.

**Declarative vs imperative schema.** *Declarative* describes the desired end state; *imperative*
describes the steps. Migrations are imperative; `schema.vx` is declarative; §5 is about the gap.

**Schema diffing.** Computing the changes needed to transform one schema into another. The
undecidable fragment is rename detection.

**Snapshot.** A committed file recording the last-migrated schema state, used as the diff baseline —
so migration generation is a pure function of two files rather than depending on a live database.

**DDL vs DML.** *Data Definition Language* changes structure (`CREATE TABLE`, `ALTER TABLE`); *Data
Manipulation Language* changes rows (`INSERT`, `UPDATE`). Migrations are mostly DDL, and destructive
DDL is irreversible.

**Lexer, parser, recursive descent.** A *lexer* turns characters into tokens; a *parser* turns tokens
into a tree. *Recursive descent* writes one function per grammar rule — verbose, and the standard
choice when error messages matter, because you control exactly what happens on failure.

**Error recovery.** Continuing after a syntax error to report further errors, rather than stopping at
the first. Requires collecting diagnostics instead of throwing — which is why the lexer's
`diagnostics()` list matters.

**Code generation and the Generation Gap pattern.** Generated code cannot be hand-edited, because
regeneration overwrites it. The *Generation Gap* pattern emits an abstract base class that is
regenerated freely, plus a concrete subclass scaffolded once and never touched — so users can add
behaviour safely.

**Static metamodel.** Generated constants naming each field (`UserFields.EMAIL` in Vantix, Phase 3),
so queries reference columns through the compiler rather than through strings. A typo becomes a
compile error.

**Hibernate proxy.** A generated subclass Hibernate hands out instead of loading a row
(`getReference`, lazy associations). Its fields are empty until something forces a load, which is
why generated code must go through getters — and why `final` methods run without loading.

**JPA Criteria API.** JPA's programmatic query-building API. Verbose to write by hand, which is why
Vantix compiles a fluent API down to it — Hibernate still produces the SQL.

**`LazyInitializationException`.** Hibernate's most notorious error: accessing an un-fetched
association after the session closes. The structural cure is deciding what to fetch up front, which
is what `.include(UserFields.ADDRESS)` will be for (Phase 3).

**JavaPoet.** A library for generating Java source programmatically, producing correctly-formatted
code with managed imports rather than string concatenation.

**ArchUnit.** A library for asserting architectural rules as unit tests — module A may not depend on
module B — so a layering violation fails the build.

---

## 9. Explain It To Others

### 30 seconds — a non-technical friend

"When you build an app with a database, the same information ends up written down in three separate
places: the code, the database setup scripts, and the actual database. Keeping all three in step is
manual, and when they drift you get bugs that only show up when a real user hits them. This is a
tool where you write it down *once*, in one readable file, and it generates the other two for you.
The interesting bit is when you change something — it works out what needs to happen to the existing
database, writes those instructions out for you to read, and refuses to delete anything without you
explicitly saying so."

### 2 minutes — a developer

"Prisma-style toolchain for Spring Boot. One `schema.vx` file generates JPA entities and Spring Data
repositories today; Flyway migrations and a typed query API are the next two phases. Built at compile time, so at runtime
your app is plain Spring Data JPA on Hibernate — 'on top of Hibernate, not instead of it,' and you
can delete Vantix and keep the generated code.

The interesting engineering is the migration engine. Your schema file is *declarative* — it says
what the database should look like. A migration is *imperative* — it says what to do. And a desired
end state doesn't determine the path, because the database has data in it you have to preserve.

So it diffs against a **committed snapshot** of the last migrated state, not against a live
database. That makes migration generation a pure function of two files in version control:
deterministic, offline, reviewable in a PR, and identical for two developers on the same commit.
Diffing a live database sounds more truthful but means the output depends on whose laptop ran it.

The genuinely hard part is that **rename detection is undecidable.** If `name` disappears and
`fullName` appears, no algorithm can tell whether that's a rename or a drop-and-add — the two
schemas are identical under both readings, and the distinguishing information only exists in the
developer's head. One preserves your data and one destroys it.

Vantix's answer is to ask, default to the safe interpretation, and **record the answer in the
snapshot so it never asks twice.** That last part is what makes it workable — the snapshot isn't
just schema state, it's an accumulating record of resolved ambiguities. Same principle for
destructive changes: `DROP COLUMN` is emitted *commented out* unless you pass `--allow-destructive`.
You see what would happen; acting on it is deliberate."

### 5 minutes — an interviewer who will push back

Lead with the 2-minute version, then:

"**Status, plainly:** pre-alpha, with Phases 0 and 1 done. The compiler front end, code
generation, CLI and Maven plugin exist and are tested — 213 tests, including a demo app that
boots on generated code against real PostgreSQL. Migrations, the query builder and Studio don't
exist yet.

**The idea I'd defend** is that the migration engine is a compiler for a language with an
undecidable fragment. Most of the translation is mechanical and should be automatic; a small part is
genuinely impossible to decide and has to be escalated to a human. The contribution isn't solving
the undecidable part — it's identifying precisely where the boundary sits and building the
escalation into the workflow, so the tool stays trustworthy on everything else. Any tool claiming to
auto-detect renames is guessing with your data.

**The decision I'd most want to talk about** is snapshot diffing versus live-DB diffing, because the
instinct runs the other way. Comparing against the real database feels more honest. It's worse: two
developers on the same commit get different migrations, it can't run in CI or offline, and the
output depends on the state of whichever machine happened to run it.

**And what I got wrong and fixed.** The plan said `Hibernate.getClass()` for proxy-safe equality;
that loads the proxy. The obvious replacement, `getClassLazy()`, throws on detached proxies — I read
its bytecode before using it. A review also caught that `@default(010)` would have generated an
octal literal, and that `@id @default(uuid())` would make every `save()` a merge. Each of those is
now a test, and the equality tests are proven by mutation: break the generator on purpose and they
fail."

---

## 10. Questions You'd Be Asked

**Q: Why not just use Prisma, or Hibernate's `hbm2ddl`?**
Prisma is TypeScript-only. Hibernate's `ddl-auto: update` is the thing this replaces — it's
non-deterministic, generates no reviewable artifact, and is explicitly unsafe in production. The gap
is a Java toolchain where one declarative file drives entities, migrations, and queries, and the
migrations are standard reviewable Flyway SQL rather than something a runtime does to your database
while you're not looking.

**Q: Why build on Hibernate rather than replacing it?**
Because Hibernate's persistence context, dirty checking, and caching encode two decades of edge
cases that a new project would rediscover painfully. Vantix is a compile-time toolchain with a
deliberately thin runtime — the generated code is plain Spring Data JPA, so you can delete Vantix
and keep working. Ejectability is treated as a design property, not a marketing line, and it
constrains generation permanently: nothing clever that only works while `vx-runtime` is present.

**Q: Why diff against a snapshot instead of the real database?**
Determinism. A live-DB diff depends on which database — your laptop, CI, staging — each with
different drift, so two developers on the same commit generate different migrations, and it can't
run offline. Snapshot diffing makes migration generation a pure function of two files that are both
in version control, so the output is reproducible and reviewable in a pull request. Drift against
reality still matters, so it's a separate explicit command: `migrate diff --against-db`.

**Q: How do you detect a rename?**
You don't — it's undecidable. If `name` disappears and `fullName` appears, the two schemas are
literally identical under "rename" and under "drop and add," and the information that distinguishes
them was never written down. One preserves data, one destroys it. So Vantix prompts, defaults to the
safe interpretation, and records the decision in the snapshot so it never asks again. That last part
is what makes prompting tolerable rather than infuriating.

**Q: What stops it dropping a column I still need?**
`DROP COLUMN` and `DROP TABLE` require `--allow-destructive`. Without it they're still *emitted* —
commented out — in the migration file. That's deliberate: you see exactly what the schema change
implies, and enabling it is a visible edit in the diff rather than an invisible flag someone forgot.

**Q: What happens when I need to hand-edit a generated entity?**
You don't edit it — generated sources live in `target/` and are overwritten every build. The
Generation Gap pattern is the answer: an `abstract UserBase` that's regenerated freely, plus a
`User extends UserBase` scaffolded once and never touched again, where your behaviour lives. Java has
no partial classes, so there's no merge option. This is decided up front because it's the single
most common way code generators die.

**Q: Why hand-write the parser instead of using ANTLR?**
Because the error messages are the product. Compiler-grade diagnostics — line, column, caret, "did
you mean," and multiple errors in one pass — need error *recovery*, which means controlling exactly
what happens on failure. The lexer already reflects this: it collects diagnostics into a list rather
than throwing, so the parser can keep going. That's a decision you can't retrofit.

**Q: Isn't a static metamodel a lot of generated files?**
Yes, and the alternative is worse. Lambda-based property references need reflection, which hurts IDE
support and breaks under GraalVM native image. `UserFields.EMAIL` is a constant the compiler checks,
so a renamed column becomes a compile error in every query that used it. It is deliberately not
`User_`: that's what Hibernate's `jpamodelgen` generates, and many Spring apps already run it.

**Q: How do you know the generated `equals` is right?**
Because the tests would catch it being wrong, and I checked that they do. The demo app compares
uninitialized proxies, loaded entities, detached and managed copies against real PostgreSQL, and
asserts both the answer *and* that nothing was loaded. Asserting "no load" alone passes on the
classic bug — reading `other.id`, which is null on a proxy. So I mutated the generator to emit
exactly that bug, and two tests failed.

**Q: "Incremental builds stay fast" — prove it.**
An integration test creates a blank Boot app, runs `vantix:init` and `compile`, then `compile`
again, and requires "0 written, 2 unchanged" and "Nothing to compile" the second time. What it
doesn't claim: when the schema does change, Maven still recompiles the whole module.

**Q: What's the biggest risk to the project?**
Scope, still. A DSL, a compiler, a differ, a query builder, a web studio, a Maven plugin and an IDE
story. Phase 1 made the first half demonstrable, but the differentiator — `migrate dev` — isn't built,
and until it is the demo runs on Hibernate-created tables, so Vantix's DDL has never met a database.
The design's defence is *"every new construct costs parser + validator + codegen + differ + SQL +
docs; say no by default."*

---

## 11. Weak Points

### Fixed since the Phase 0 review

- **Lexer gaps.** No float literal and no string escapes; both fixed before the parser was written.
  Numbers are now strict (leading zeros, `.5`, `1.`, exponents and suffixes are errors with the fix).
- **Documentation drift.** `CLAUDE.md` claimed there was no code; the README's roadmap had nothing
  ticked and showed a `User_` metamodel as Phase 1 behaviour. All updated to match the code.

### Open

- **The Boot 3.5 portability job has never run in CI.** It passed locally with the same command;
  enabled is not verified.
- **Vantix's DDL is untested against a real database.** Until Phase 2 emits migrations, the demo
  uses `ddl-auto=create`, so it tests Hibernate's table definitions. Switching the demo to Flyway +
  `ddl-auto=validate` is the Phase 2 exit criterion.
- **Inverse one-to-one fields cost N+1 queries.** Hibernate can't lazy-load them without bytecode
  enhancement (measured: 3 users, 4 queries). Vantix warns; whether to stop generating that side is
  an open decision before release.
- **Incremental builds are module-grained.** No-op builds are free; any real change recompiles the
  module. That's Maven, not Vantix, but the claim has to be scoped.
- **Generation Gap scaffolds can drift.** If a table is renamed, the user-owned `@Table` doesn't
  follow; Vantix only warns.
- **`getPersistentClass()` is the declared type.** Fine in v1, which has no entity inheritance;
  equality needs revisiting if inheritance is ever added.
- **One mutable `snapshot.json`** (Phase 2) will conflict across parallel branches — recorded as a
  known limitation (D1), with a shadow-database `--verify` as the planned fix (D13).

### What's genuinely strong

The **Design Notes & Known Hard Problems** section is still the best thing here: named failure modes
of code generators, each with a decided answer and a reason for deciding it early. The code now backs
several of them with evidence rather than prose — `equals` with mutation-tested proxy tests,
incremental builds with an end-to-end "Nothing to compile" check, and diagnostics with a 73-case
corpus pinned to the exact text a user sees.

---

## Appendix — the one thing to build next

**Phase 2's vertical slice:** snapshot store → `SchemaDiffer` producing a typed change list (pure, no
database) → `PostgresRenderer` → `vantix migrate dev` writing one timestamped Flyway file — then
switch the demo app to Flyway + `ddl-auto=validate`, so the first time Vantix's SQL meets PostgreSQL
it is a test, not a user.

Before starting it, do the Prisma study the plan asked for in Phase 0. It is too late to shape the
error text, but `prisma migrate dev`'s rename and destructive-change prompts are exactly what Phase 2
has to get right.
