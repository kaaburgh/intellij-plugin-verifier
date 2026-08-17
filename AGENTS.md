# Agent instructions

These instructions apply to the whole repository. A nested `AGENTS.md` adds more specific guidance for files under its directory; prefer the closest applicable instructions when they differ.

## Start here

Before changing code:

1. Read `README_DEV.md` and the README for the active subproject.
2. Identify the owning Gradle build/module before editing. This is a multi-project repository, not one monolithic application.
3. Inspect nearby production code and tests and follow their conventions rather than introducing a new pattern.
4. Keep the patch scoped to the requested behavior. Do not opportunistically modernize language levels, dependencies, formatting, or unrelated code.

## Repository map

The root Gradle build is a composite of four active projects:

- `intellij-plugin-structure` — parsing and validation of JetBrains plugin/IDE distributions, plugin models, class/resource resolution.
- `intellij-plugin-verifier` — binary compatibility verification, IntelliJ-specific API-usage checks, dependency resolution, repositories/caches, CLI and reports.
- `intellij-feature-extractor` — extraction of plugin features such as supported file types and configuration types.
- `ide-diff-builder` — API differences between IDE builds and generation of API-status annotations.

`plugins-verifier-service` is deprecated. Do not add new product logic there unless the task explicitly targets the remaining `FeatureExtractorService` use or its composite-build wiring.

### Put changes in the right layer

- Plugin descriptor parsing or structural validation belongs in `intellij-plugin-structure`, normally `structure-intellij` for IntelliJ plugins.
- Generic class-file/resource resolution belongs in `structure-classes` / the appropriate `*-classes` module.
- Generic JVM bytecode verification belongs in `intellij-plugin-verifier/verifier-core`.
- IntelliJ Platform-specific verification, dependency graphs, API usages and verdict assembly belong in `verifier-intellij`.
- Marketplace/IDE downloading, local artifact repositories and verifier-side file caches belong in `verifier-repository`.
- CLI option parsing, task orchestration and human/TeamCity/HTML/Markdown output belong in `verifier-cli`.
- Do not solve a verifier problem by teaching the parser about verification policy, or a parser problem by special-casing it in the CLI.

## Language and runtime constraints

These constraints are intentional compatibility requirements:

- CI runs Gradle with JDK 17, while production bytecode targets Java 11.
- `intellij-plugin-verifier`: Kotlin language/API level 1.5, JVM target 11.
- `intellij-plugin-structure`: Kotlin language/API level 1.4, JVM target 11.
- `intellij-feature-extractor`: Kotlin language/API level 1.4, JVM target 11.
- `ide-diff-builder`: Kotlin language/API level 1.4, JVM target 11.

Do not use newer Kotlin language features in a project whose language level forbids them. Do not bump Java/Kotlin/toolchain versions merely because the local environment lacks the configured toolchain. Install/provision the required JDK or report the limitation instead.

Kotlin sources intentionally live in both `src/main/kotlin` and `src/main/java` (and likewise under tests). Do not move files solely to normalize source-directory naming.

## Public API and compatibility

Several modules are published libraries. Kotlin declarations are public by default, so seemingly small changes can alter API/ABI.

- Prefer private/internal implementation details when no public API is required.
- Treat public classes, methods, properties, constructors and signatures in `intellij-plugin-structure` and published verifier modules as compatibility-sensitive.
- If a task intentionally changes public API, state that explicitly in the PR and make the smallest API change that solves the problem.
- Pay attention to API-compatibility checks/review feedback; do not silence compatibility failures without understanding them.

## Performance and lifecycle-sensitive code

This repository processes large IDEs, plugin archives and class graphs. Hot paths and resource lifetime matter.

- Avoid repeated archive scans, repeated resolver walks, avoidable collection copies and eager construction of locations/metadata on class-scanning paths.
- Existing code deliberately uses indexes, memoization and lazy values in hot paths. Preserve cache invalidation and identity semantics when changing them.
- Do not add wall-clock thresholds to unit tests. Prove performance changes with behavior-preserving tests plus profiling/allocation/benchmark evidence when relevant.
- Preserve deterministic ordering in reports and pretty printers when replacing an implementation with a faster one.
- File/archive resources are cross-platform sensitive. Windows file locking differs from Unix deletion semantics; non-default `FileSystem` implementations are used by tests. Never assume every `Path` is on the default filesystem.
- Preserve `AutoCloseable` / `use {}` lifetimes. When adding caches or extracted artifacts, define who owns them, when they are released, and whether close/release operations must be idempotent.
- Caffeine removal callbacks may be asynchronous. Tests must not accidentally depend on listener timing unless they explicitly synchronize it.

## Dependency-resolution changes

Dependency resolution has more than one path. Before changing dependency semantics, trace `DefaultClassResolverProvider` and search for both the newer dependency-tree/product-info path and the legacy verifier dependency-graph path. A fix that updates only one can work for some IDE/plugin combinations and fail for others.

Also preserve resolver ordering: the composite resolver is intended to model how a plugin sees its own classes, dependencies and IDE classes at runtime. Reordering resolvers is a semantic change.

## Tests

Use the smallest relevant test first, then broaden based on the changed surface.

### Whole repository

From the repository root:

```bash
./gradlew test
```

This exercises the active included builds and is the broadest local check.

### IntelliJ Plugin Structure

```bash
cd intellij-plugin-structure
./gradlew :tests:test --tests '*RelevantTest*'
./gradlew test
```

Most cross-module structure tests live in the central `tests` module; some modules also keep focused tests next to the implementation. Reuse existing fixture builders, mock distributions and filesystem-aware test helpers.

### IntelliJ Plugin Verifier

The verifier consumes `structure-*` artifacts with version `dev` during local development. For a standalone verifier build, mirror CI setup first:

```bash
cd plugins-verifier-service
./gradlew :intellij-plugin-structure:publishToMavenLocal
cd ../intellij-plugin-verifier
./gradlew :verifier-test:test --tests '*RelevantTest*'
./gradlew test
```

If the task changes both structure and verifier code, make sure verifier tests run against the locally changed structure artifacts, not an older `dev` artifact left in Maven Local. Running from the root composite build is another way to keep included builds aligned.

### Feature extractor / IDE diff builder

Run their focused tests from their own directories, and use the root composite build when local `dev` dependencies from sibling projects are involved.

## Test style

- The project predominantly uses JUnit 4. Follow the assertion style already used in the surrounding test (`kotlin.test`, JUnit assertions or AssertJ) instead of converting a file wholesale.
- Prefer a regression test that fails on the old behavior and passes after the fix.
- Test externally meaningful behavior/invariants, not private implementation details.
- For parser and archive behavior, prefer realistic small fixtures and existing fixture helpers over mocks of the parser itself.
- For filesystem/resource bugs, cover the relevant lifecycle and filesystem semantics; use existing in-memory/filesystem-aware helpers where suitable.
- Do not hide flaky behavior with sleeps or retries. Make asynchronous state transitions observable or deterministic in the test.

## Output and error behavior

Verifier and structure problems are consumed by humans and integrations.

- Keep problem/verdict meaning stable unless the task explicitly changes it.
- Avoid incidental wording, grouping, ordering or filename changes in reports.
- Preserve the distinction between plugin-structure problems, compatibility problems/warnings and API-usage findings.
- Prefer the established typed problem/result model over throwing generic exceptions for expected invalid plugin input.

## Before opening a PR

- Review the complete diff for accidental generated files, local paths, binaries, benchmark output and unrelated cleanup.
- Run the narrow regression test(s) and the relevant project-level `test` task when feasible.
- In the PR description include: problem/motivation, implementation approach, tests run, and any API/behavior/performance/resource-lifecycle impact.
- If validation could not be run because of environment/toolchain/network constraints, say exactly what was and was not verified; do not change build configuration just to make the environment pass.
