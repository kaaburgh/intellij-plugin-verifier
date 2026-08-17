# IDE Diff Builder agent guide

Read the repository-root `AGENTS.md` first. This file adds guidance for `ide-diff-builder/**`.

## Scope

`ide-diff-builder` compares API surfaces of IDE builds and builds external API-status annotations such as availability/removal information. It consumes local `dev` verifier/structure artifacts and has `mock-old-ide` / `mock-new-ide` projects for reproducible tests.

Do not move generic resolver or compatibility-verification behavior here. This project should orchestrate API extraction/diffing and serialize the resulting diff/annotations.

## Compatibility constraints

- JVM target: 11.
- Kotlin language/API level: 1.4.
- Do not use newer Kotlin syntax or change toolchains merely to make a local environment pass.

## Tests

The test task prepares mock IDE distributions automatically:

```bash
cd ide-diff-builder
./gradlew test --tests '*RelevantTest*'
./gradlew test
```

If the task also changes verifier/structure code, run via the repository-root composite build or otherwise ensure the `dev` artifacts consumed by this build are current.

## Diff/serialization invariants

- Compare APIs semantically; do not let filesystem traversal or hash iteration order make the result nondeterministic.
- Preserve stable external names/signatures and serialization unless the task intentionally changes the format.
- A change that affects which members are considered API should include a focused old-IDE/new-IDE fixture demonstrating the boundary.
- Keep mock IDE fixtures minimal: add only the classes/members required to express the regression.
