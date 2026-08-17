# Repository instructions for coding agents

Use `AGENTS.md` as the canonical repository guide. Before making changes, read the root file and the nearest nested `AGENTS.md` for the project being edited (`intellij-plugin-verifier`, `intellij-plugin-structure`, `intellij-feature-extractor`, `ide-diff-builder`, or `plugins-verifier-service`).

Key non-negotiable constraints:

- Keep changes in the module that owns the behavior; do not patch presentation code to hide parsing/resolution bugs.
- Production bytecode targets Java 11. Respect each project's pinned Kotlin language/API level (1.5 for verifier, 1.4 for structure/feature/ide-diff).
- Treat published structure/verifier APIs as compatibility-sensitive; Kotlin declarations are public by default.
- Preserve resolver order, report determinism, cache/resource lifetime and cross-platform filesystem behavior.
- Start with the smallest relevant JUnit 4 regression test, then run the affected project's `test` task when feasible.
- Do not modernize dependencies/toolchains/formatting or touch deprecated `plugins-verifier-service` product logic unless the task requires it.

The detailed architecture map, exact test commands and subsystem-specific invariants live in the applicable `AGENTS.md` files and should be followed rather than duplicated here.
