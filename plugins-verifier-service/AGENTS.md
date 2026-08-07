# Plugins Verifier Service agent guide

Read the repository-root `AGENTS.md` first. This project is deprecated.

## Do not extend this service by default

The service has been superseded by another implementation. The repository documentation identifies `org.jetbrains.plugins.verifier.service.service.features.FeatureExtractorService` as the remaining used feature.

Unless the task explicitly targets that remaining integration or build wiring:

- do not add new verifier product behavior here;
- do not move fixes from active projects into this service to avoid changing their proper owner;
- prefer `intellij-plugin-structure`, `intellij-plugin-verifier` or `intellij-feature-extractor` as appropriate.

## Composite-build role

This Gradle build includes `intellij-feature-extractor`, `intellij-plugin-verifier` and `intellij-plugin-structure`. CI also uses its wrapper to publish the local `intellij-plugin-structure` `dev` artifacts before running standalone verifier tests:

```bash
./gradlew :intellij-plugin-structure:publishToMavenLocal
```

Treat this as build/test wiring, not evidence that new product logic belongs in the deprecated service.

If a requested change truly targets the service, keep it narrowly compatible with the remaining consumer and run the nearest service tests plus tests in any active project whose API was changed.
