# IntelliJ Plugin Structure agent guide

Read the repository-root `AGENTS.md` first. This file adds guidance for `intellij-plugin-structure/**`.

## What this project owns

`intellij-plugin-structure` is a family of published libraries for reading plugin/IDE distributions, parsing descriptors, validating structure and exposing class/resource resolvers. It is used by Plugin Verifier, Marketplace-related code and external consumers, so behavior and public API are compatibility-sensitive.

For IntelliJ plugins, the normal flow starts at `IdePluginManager.createManager().createPlugin(...)` and produces an `IdePlugin` plus typed `PluginProblem`s.

## Module map

- `structure-base` — shared plugin abstractions, problems, telemetry, archive/filesystem utilities and common helpers.
- `structure-classes` — generic class-file/resource abstractions and resolvers.
- `structure-intellij` — IntelliJ plugin descriptor parsing, dependency model and structural validation.
- `structure-intellij-classes` — IntelliJ plugin class/resource locations and resolvers.
- `structure-ide` — IntelliJ IDE distribution/model, product metadata and bundled-plugin discovery.
- `structure-ide-classes` — class/resource resolution for IDEs and bundled plugins.
- `structure-ide-jps` — JPS-related IDE structure support.
- `structure-teamcity`, `structure-dotnet`, `structure-hub`, `structure-edu`, `structure-fleet`, `structure-toolbox`, `structure-youtrack`, `structure-teamcity-recipes` — product-specific parsers/models.
- `tests` — central cross-module test suite and shared fixtures/helpers.
- `tests-jps` — JPS-specific tests.

Put generic behavior in the lowest reusable layer that actually owns it. Do not add IntelliJ descriptor semantics to `structure-base`, and do not duplicate generic ZIP/filesystem behavior in a product-specific module.

## Public API discipline

Most structure modules are published. Kotlin's default visibility makes accidental API growth easy.

- Prefer `private` or `internal` for implementation helpers.
- Before changing an existing public signature/model, search consumers inside this repository and consider external compatibility as well.
- Adding a public convenience property/function is still an API change; mention it in the PR.
- Preserve deprecated APIs unless the task explicitly includes a compatible migration/removal plan.
- Avoid exposing a verifier-specific concept from structure modules just to simplify one caller.

## Parser and validation behavior

Plugin inputs are untrusted and often malformed. Expected invalid input should normally become a typed structure problem/error rather than an unrelated runtime exception.

When changing descriptor parsing or validation:

- identify all supported packaging forms involved (single JAR, ZIP distribution, extracted directory, nested library JARs);
- preserve descriptor/source path information in problems when available;
- consider legacy and newer dependency/content-module descriptor representations;
- distinguish malformed/unreadable input from a valid plugin that has validation problems;
- preserve exact duplicate-vs-distinct semantics when a descriptor can legally repeat elements;
- do not silently discard additional XML blocks/elements unless that is the defined behavior.

Prefer extending existing descriptor builders/fixtures and parser tests rather than hand-constructing partial domain objects that bypass parsing.

## Dependency-tree changes

`structure-intellij` owns the newer plugin dependency tree and resolution model used by verifier and IDE class resolution.

When changing it:

- keep declared plugin dependencies, modules, product modules and optional dependencies distinct;
- preserve graph identity/deduplication semantics;
- verify missing/optional dependency behavior, not only the happy path;
- remember verifier also has a legacy dependency path; a cross-project task may require a matching change under `verifier-intellij`.

## Archive and filesystem code

Archive handling is especially sensitive because plugins/IDEs are large and the code runs on Windows, macOS/Linux and tests using non-default filesystems.

- Work with `Path`/its provider rather than assuming the default filesystem.
- Do not assume an open archive can be deleted on every OS.
- Make ownership of `ZipFile`, ZIP filesystem, streams and extracted directories explicit.
- Preserve exception translation (`MalformedZipArchiveException`, archive I/O errors, etc.) so callers receive domain-relevant failures.
- Caches/references used to retain archive handles must not leak files indefinitely; cleanup must remain safe if the backing path disappears.
- For concurrency/cache changes, distinguish synchronous state changes from asynchronous removal listeners.

## Performance-sensitive areas

Class/resource resolution, IDE bundled-plugin lookup, descriptor/archive scanning and ZIP access can execute thousands of times per verification.

Existing accepted patterns include:

- pre-indexing repeated ID/module lookups instead of O(n) scans;
- memoizing derived class metadata;
- lazily materializing expensive locations/method data;
- caching repeated resolver/annotation lookups with bounded or weak ownership where appropriate.

When adding a cache, define key equality, mutation/invalidation assumptions, lifetime and thread-safety. Do not cache mutable results without proving they are immutable for the cache lifetime.

## Tests and commands

Most cross-module tests are in `tests`:

```bash
cd intellij-plugin-structure
./gradlew :tests:test --tests '*RelevantTest*'
./gradlew test
```

Some focused tests live in the implementation module itself; run the nearest matching test task when present.

The central test suite already contains helpers for:

- mock plugin/IDE distributions;
- descriptor/XML builders;
- in-memory and filesystem-aware temporary folders;
- archive/JAR construction;
- dependency-tree/resolver scenarios.

Reuse those before introducing a new fixture framework.

### Good regression tests

- Parser bug: a minimal real descriptor/archive that failed before, plus typed problem/model assertions.
- Dependency bug: assert graph vertices/edges/missing dependencies and relevant flags, not only string output.
- Filesystem bug: exercise the actual provider/lifecycle; include non-default filesystem behavior when relevant.
- Cache/performance bug: assert that observable behavior remains equivalent and that invalidation/lifetime is correct; benchmark separately.

Avoid sleeps, timing thresholds and reliance on GC scheduling unless the behavior under test is specifically about reachability/cleanup and the test has a deterministic hook.

## Compatibility with verifier

If a structure change affects models, dependencies, IDE/plugin class resolution or archive lifecycle used by Plugin Verifier, run verifier tests against the changed local structure artifacts. From repository root the composite build can align included builds; for a standalone verifier test mirror CI's Maven Local publish step described in the root `AGENTS.md`.
