# structure-intellij-community

Vendored copy of the IntelliJ Platform plugin descriptor parser
(`intellij.platform.pluginSystem.parser.impl`) from
[JetBrains/intellij-community](https://github.com/JetBrains/intellij-community), plus the minimal
XML infrastructure it needs. Used (behind a default-off feature gate) by `structure-intellij` to
parse plugin descriptors with the exact semantics of the IDE runtime. See `DESIGN.md` for the
overall migration design.

**Do not edit the vendored sources** except to refresh them from upstream or to extend the
documented patch list below.

## Provenance

* Upstream: `JetBrains/intellij-community`, branch `master`, retrieved **2026-07-06** via
  `raw.githubusercontent.com`.
* Vendored directories:
  * `platform/pluginSystem/parser/impl/src/com/intellij/platform/pluginSystem/parser/impl/**`
    → `src/main/java/com/intellij/platform/pluginSystem/parser/impl/**`
  * `platform/util/xmlDom/src/com/intellij/util/xml/dom/{XmlElement,StaxFactory,xmlDom}.kt`
    → `src/main/java/com/intellij/util/xml/dom/`
* SHA-256 checksums of the **unmodified** upstream files as retrieved: `UPSTREAM_SHA256SUMS.txt`.
* License: Apache 2.0 (same as this repository); upstream copyright headers are preserved.

## Local patches (the complete list)

1. Import replacements — platform utilities are provided by shims in
   `com.jetbrains.plugin.structure.ijc.shim` (this module, `shim/` sources):
   * `com.intellij.openapi.diagnostic.Logger` / `.logger` → `…ijc.shim.Logger` / `.logger`
     (SLF4J-backed; unlike the platform logger, `error()` never throws)
   * `com.intellij.util.containers.Java11Shim` → `…ijc.shim.Java11Shim`
   * `com.intellij.openapi.util.NlsSafe` → `…ijc.shim.NlsSafe`
   Affected files: `XmlReader.kt`, `PluginDescriptorBuilderImpl.kt`,
   `ScopedElementsContainerBuilderMemoryOptimized.kt`, `RawPluginDescriptor.kt`.
2. `XmlElement.kt`: removed `import kotlinx.serialization.Serializable` and the `@Serializable`
   annotation (avoids a dependency on the kotlinx-serialization compiler plugin; the verifier
   never serializes descriptors).

Everything else is byte-identical to upstream (`diff` against files fetched from the pinned
upstream revision, modulo the import lines above).

## Refreshing

Re-download the files listed in `UPSTREAM_SHA256SUMS.txt`, regenerate the checksum file,
re-apply the patch list above, and run the parity test suite
(`com.jetbrains.plugin.structure.intellij.community.*` in `tests`).
