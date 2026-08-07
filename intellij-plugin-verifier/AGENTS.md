# IntelliJ Plugin Verifier agent guide

Read the repository-root `AGENTS.md` first. This file adds guidance for `intellij-plugin-verifier/**`.

## Verification pipeline

When debugging a verifier result, trace the pipeline in this order rather than patching the final printer:

1. `structure-intellij` parses the checked plugin into `IdePlugin`.
2. `structure-ide` parses the target IDE into `Ide`.
3. `structure-*-classes` builds resolvers for plugin, dependency and IDE classes.
4. Dependency resolution builds the plugin dependency graph and the composite resolver that models runtime visibility.
5. `PluginVerifier` / `BytecodeVerifier` resolve JVM references and register compatibility problems and API usages in `PluginVerificationContext`.
6. Result assembly produces `PluginVerificationResult`.
7. CLI result printers render plain text, TeamCity, HTML/Markdown and other user-facing output.

Fix the earliest layer that owns the incorrect information. Do not compensate for bad parsing/resolution by filtering the final report unless filtering is the intended product behavior.

## Module responsibilities

### `verifier-core`

Generic bytecode verification and JVM-level reference resolution. Keep IntelliJ-specific policy out of this module.

Typical concerns:

- class/method/field resolution;
- JVM access and binary compatibility checks;
- generic verification primitives and compatibility problems;
- hot bytecode-scanning paths.

### `verifier-intellij`

IntelliJ Platform-specific orchestration and policy.

Typical concerns:

- `PluginVerifier` and `PluginVerificationContext`;
- dependency graph construction and class-resolver composition;
- deprecated/experimental/internal/non-extendable/override-only API usages;
- IntelliJ-specific warnings, analyzers and verdict assembly;
- plugin details and resolver lifecycle.

### `verifier-repository`

Artifact acquisition and verifier-side storage/caching.

Typical concerns:

- Marketplace plugin lookup/download;
- IDE repositories;
- local file caches and sweep policies;
- extracted archives and disk-space accounting.

Treat filesystem ownership, locking, cleanup and cache accounting as part of correctness, not merely an optimization.

### `verifier-cli`

CLI boundary and presentation.

Typical concerns:

- command/option parsing and task parameter builders;
- `PluginVerifierMain` orchestration;
- result printers and report files;
- TeamCity metrics/messages.

Keep verification policy out of printers. A printer should render already-decided results.

### `verifier-test`

Integration-style verifier regression tests. The module builds small before/after IDE and plugin fixtures so compatibility failures are known and reproducible.

Prefer extending these fixtures/tests for end-to-end compatibility behavior rather than building ad-hoc large external test inputs.

## Important invariants

### Resolver order is semantic

The combined resolver models plugin class loading. Before altering resolver composition, determine which source is expected to win when the same class/package is present in the checked plugin, its dependencies, bundled plugins and the IDE.

### There are multiple dependency paths

`DefaultClassResolverProvider` selects between dependency-resolution mechanisms depending on IDE/plugin metadata. Changes to what counts as a dependency, missing dependency, module or constraint may need corresponding handling in both the structure dependency tree and verifier-side `DependenciesGraphBuilder` path.

Add tests for the path(s) affected by the bug. If the report came from one descriptor style, check whether the other supported descriptor/dependency representation has the same semantics.

### Results are sets/maps, but rendered output must remain stable

Compatibility problems and API usages are aggregated before rendering. When optimizing aggregation or printing, preserve intentional sorting/deduplication and repeated-node behavior. Do not rely on hash iteration order for user-visible output.

### Closeable objects are real ownership boundaries

Plugin details, cache entries, resolver providers and extracted/archive-backed resources frequently participate in `use {}` chains. If a new resource escapes its current scope, make its owner explicit and make cleanup safe on error paths. Do not retain a resource in a process-wide cache unless its release policy is equally explicit.

### Hot-path allocation matters

Class verification is repeated over large classpaths. Before adding work to getters or per-instruction/per-member loops, ask whether it can be:

- avoided entirely on the common path;
- calculated lazily only when a problem is reported;
- indexed once instead of scanned repeatedly;
- cached with a key that correctly represents resolver/archive identity.

Follow existing thread-safety assumptions. `LazyThreadSafetyMode.NONE` is used deliberately for thread-confined objects; do not copy it into shared objects without proving confinement.

## Tests and commands

For a focused verifier regression test:

```bash
cd intellij-plugin-verifier
./gradlew :verifier-test:test --tests '*RelevantTest*'
```

For repository-specific tests, prefer that module's test task when the test is located there, for example:

```bash
./gradlew :verifier-repository:test --tests '*RelevantTest*'
```

For the full verifier build:

```bash
./gradlew test
```

A standalone verifier build needs current local `structure-*` `dev` artifacts. Mirror CI when necessary:

```bash
cd ../plugins-verifier-service
./gradlew :intellij-plugin-structure:publishToMavenLocal
cd ../intellij-plugin-verifier
./gradlew test
```

When a task changes structure and verifier together, rerun the structure publish step after the final structure edit before trusting verifier results.

## Choosing regression coverage

- Bytecode/reference-resolution bug: add the smallest fixture that produces the exact JVM construct plus a verifier assertion.
- Dependency bug: test graph contents/missing dependencies and, where relevant, final verifier verdict.
- API-usage classification bug: assert the typed usage/problem and its reported/ignored partition.
- Cache/lifecycle bug: assert counters/ownership/release behavior, not only that no exception was thrown.
- Printer/report bug: assert exact meaningful output, including deterministic ordering when ordering is part of the presentation.
- Performance change: add semantic regression coverage first; measure performance separately and avoid timing assertions in unit tests.

## PR notes specific to verifier changes

Call out any of the following explicitly when they apply:

- changed compatibility verdict or warning classification;
- changed dependency-resolution semantics;
- changed public API;
- changed report text/order/files;
- new cache or cache-key semantics;
- changed archive/resource lifetime;
- performance trade-off or new retained memory/disk usage.
