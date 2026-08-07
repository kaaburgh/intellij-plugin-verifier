# IntelliJ Feature Extractor agent guide

Read the repository-root `AGENTS.md` first. This file adds guidance for `intellij-feature-extractor/**`.

## Scope

This project extracts additional plugin features (for example file types and configuration/module/facet-related capabilities) from plugin and IDE class/configuration data. It depends on local `dev` versions of `structure-intellij-classes`, `structure-ide-classes` and `verifier-core`.

Keep extraction logic here. Parsing plugin packaging/descriptors belongs in `intellij-plugin-structure`; generic bytecode verification belongs in verifier modules.

## Compatibility constraints

- JVM target: 11.
- Kotlin language/API level: 1.4.
- Do not introduce newer Kotlin syntax or bump language/toolchain versions to satisfy a local environment.

## Tests

Focused tests live under this project and use small fixture classes from `test-classes`.

```bash
cd intellij-feature-extractor
./gradlew test --tests '*RelevantTest*'
./gradlew test
```

Because dependencies use version `dev`, use the repository-root composite build when sibling projects are changing in the same task, or otherwise make sure the corresponding local artifacts are current before trusting standalone results.

Prefer adding a compact fixture to `test-classes` and asserting the extracted feature model over mocking the underlying structure/verifier APIs.

## Change discipline

- Keep feature identifiers/serialization stable unless the task explicitly changes the external contract.
- Preserve deterministic extraction: the same plugin/IDE input should produce the same feature set regardless of iteration order.
- Avoid repeatedly resolving or parsing the same class/configuration in per-feature loops; extraction can run over large plugin corpora.
