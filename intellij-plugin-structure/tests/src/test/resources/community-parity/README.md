# Real Marketplace plugin descriptor fixtures

Reproducible inputs for the community-parser parity tests
(`com.jetbrains.plugin.structure.intellij.community.RealPluginDescriptorParityTest`).

Descriptors of real, open-source JetBrains Marketplace plugins, retrieved on 2026-07-06 from
`raw.githubusercontent.com` (unmodified; Apache 2.0 / LGPL licensed sources used here only as
test data):

* `sonarlint/` — SonarSource/sonarlint-intellij @ master,
  `src/main/resources/META-INF/plugin.xml` + optional dependency config files.
* `asciidoc/` — asciidoctor/asciidoctor-intellij-plugin @ main,
  `src/main/resources/META-INF/plugin.xml` + optional dependency config files.
* `detekt/` — detekt/detekt-intellij-plugin @ main, `src/main/resources/META-INF/plugin.xml`.

The tests assemble these directories into plugin JARs at runtime.
