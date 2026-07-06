# Design note: migrating plugin descriptor parsing to the intellij-community implementation

Status: **experimental vertical slice** — the community-based parser is integrated only for the
*main plugin descriptor parsing path* and is **disabled by default**. The legacy JDOM/JAXB path is
untouched and remains the default. Nothing in the public API of `structure-intellij` changed.

## 1. Entry points

### intellij-plugin-verifier (legacy path, unchanged)

| Stage | Class |
|---|---|
| Archive/dir dispatch | `com.jetbrains.plugin.structure.intellij.plugin.IdePluginManager` (`createPlugin` → `getPluginCreatorWithResult`) |
| ZIP extraction | `PluginArchiveManager` (+ `structure-base` zip utilities) |
| JAR/dir loading | `plugin.loaders.JarPluginLoader`, `PluginDirectoryLoader`, `LibDirectoryPluginLoader`, `JarOrDirectoryPluginLoader` |
| plugin.xml → JDOM | `PluginJar.getPluginDescriptor` + JDOM (`JDOMUtil.loadDocument`) |
| `<xi:include>` | `xinclude.XIncluder` (JDOM tree rewriting) |
| JDOM → bean | `extractor.PluginBeanExtractor` (JAXB) → `beans.PluginBean` |
| Validation | `PluginBeanValidator` + `ValidationContext` |
| bean → model | `PluginBeanToIdePluginConverter` → `IdePluginImpl` |
| Orchestration | `PluginCreator.resolveDocumentAndValidateBean` |
| optional depends / content modules | `OptionalDependencyResolver`, `loaders.ContentModuleLoader` (on top of the produced model, reuse the loaders recursively) |

### intellij-community (reference implementation)

Module `intellij.platform.pluginSystem.parser.impl`
(`platform/pluginSystem/parser/impl`, package `com.intellij.platform.pluginSystem.parser.impl`):

| Stage | Class |
|---|---|
| Public entry | `PluginXmlParser.kt`: `parsePluginXml(input, locationSource, readContext, xIncludeLoader)` |
| StAX consumer | `PluginDescriptorFromXmlStreamConsumer` + `XmlReader.kt` (`readModuleDescriptor`) |
| `<xi:include>` | inline during streaming, via the `XIncludeLoader` interface + `LoadPathUtil.toLoadPath` |
| Builder/model | `PluginDescriptorBuilder(-Impl)` → `RawPluginDescriptor` + `elements/*` |
| XML infra | `intellij.platform.util.xmlDom` (`createNonCoalescingXmlStreamReader` over Aalto, `readXmlAsModel` → `XmlElement`) |

In the IDE itself `RawPluginDescriptor` is then turned into `IdeaPluginDescriptorImpl` by
`platform/core-impl` (`PluginDescriptorLoader.kt`) — that layer is IDE-runtime-specific
(classloaders, `PathManager`, coroutines) and is *not* part of this slice.

## 2. How the community code is consumed

JetBrains does not currently publish `intellij.platform.pluginSystem.parser.impl` as a standalone
Maven artifact, so this module **vendors the sources verbatim** (Apache 2.0, same license as this
repository) from `JetBrains/intellij-community@master`, retrieved 2026-07-06. See `README.md`
for the exact file list, SHA-256 checksums of the unmodified upstream files
(`UPSTREAM_SHA256SUMS.txt`) and the full list of local patches (import-only, plus removal of the
`@Serializable` annotation from `XmlElement`). Platform utilities that the parser needs
(`Logger`, `Java11Shim`, `NlsSafe`) are provided as small shims in
`com.jetbrains.plugin.structure.ijc.shim`; the `xmlDom` infra (`XmlElement`, `StaxFactory`,
`xmlDom.kt`) is vendored from `platform/util/xmlDom`.

When JetBrains publishes the parser as an artifact, this module should be replaced by that
dependency; the adapter layer in `structure-intellij` is written against the upstream API surface
(`parsePluginXml`, `RawPluginDescriptor`, `XIncludeLoader`) to make that swap mechanical.

## 3. Adapter (the vertical slice)

New code in `structure-intellij`, package `com.jetbrains.plugin.structure.intellij.plugin.community`:

* `CommunityParserFeature` — feature gate. Off by default; enabled with the system property
  `intellij.plugin.structure.community.parser=true` or programmatically (used by parity tests).
* `ResourceResolverXIncludeLoader` — implements the community `XIncludeLoader` on top of the
  verifier's `ResourceResolver`, resolving the *root-relative load path* produced by
  `LoadPathUtil.toLoadPath` against the plugin's resource root (the parent of `META-INF`, or the
  descriptor's directory for root-level module descriptors).
* `CommunityDescriptorParser` — serializes the not-yet-XIncluded JDOM document back to bytes and
  runs `parsePluginXml`; maps parser failures to the same problem types the legacy path registers
  (`XIncludeResolutionErrors`, `UnableToReadDescriptor`).
* `RawDescriptorMapper` — maps `RawPluginDescriptor` to:
  * a `PluginBean` (so that the unchanged `PluginBeanValidator` performs the same validations), and
  * the `IdePluginImpl` model (mirroring `PluginBeanToIdePluginConverter` semantics), converting
    retained `XmlElement` subtrees to JDOM `Element`s where the public model requires JDOM
    (`extensions`, `actions`).

Integration point: `PluginCreator.resolveDocumentAndValidateBean` branches to the adapter when the
feature is enabled. Everything upstream (archive handling, loaders, optional-dependency and
content-module resolution, theme loading, `validatePlugin`, problem resolution) is reused as-is —
optional `<depends>` config files and content module descriptors recursively flow through the same
adapter branch.

The descriptor is fed to the community parser from the already-loaded JDOM tree
(serialize → re-parse). This keeps the integration point minimal (no loader changes) at the cost of
double XML parsing on the experimental path; if the migration proceeds, loaders should hand raw
bytes to the parser directly, which also removes JDOM's own error handling from the pipeline.

## 4. Entity/field mapping (RawPluginDescriptor ↔ verifier model)

One-to-one mappings (same source XML element/attribute, directly transferable):

| RawPluginDescriptor | PluginBean | IdePluginImpl |
|---|---|---|
| `id` | `id` | `pluginId` (fallback to name, as legacy) |
| `name` | `name` | `pluginName` |
| `version` | `pluginVersion` | `pluginVersion` |
| `description`, `changeNotes`, `category`, `url` | same | same |
| `vendor`, `vendorEmail`, `vendorUrl` | `vendor.*` | same |
| `sinceBuild` / `untilBuild` | `ideaVersion.sinceBuild/untilBuild` | `sinceBuild` / `untilBuild` (`IdeVersion`, `.*`→`MAX_INT` expansion preserved) |
| `productCode`, `releaseDate`, `releaseVersion` | `productDescriptor.*` | `ProductDescriptor` |
| `package` | `packageName` | `hasPackagePrefix` |
| `isUseIdeaClassLoader`, `isImplementationDetail` | attrs | `useIdeClassLoader`, `isImplementationDetail` |
| `moduleVisibility` | `visibility` | `moduleVisibility` |
| `pluginAliases` (`<module value=.../>`) | `pluginAliases` | `pluginAliases` |
| `depends` (`DependsElement`) | `dependencies` | `dependsList` + legacy `dependencies` (`PluginV1Dependency`) |
| `dependencies` (`DependenciesElement.ModuleDependency/PluginDependency`) | `dependenciesV2` | `contentModuleDependencies` / `pluginMainModuleDependencies` + `ModuleV2Dependency`/`PluginV2Dependency` |
| `contentModules` (`ContentModuleElement`: name, loadingRule, namespace, requiredIfAvailable) | `pluginContent` | `contentModules` (`Module`) |
| `incompatibleWith` | `incompatibleWith` | `incompatibleWith` |
| `appElementsContainer` / `projectElementsContainer` / `moduleElementsContainer` (services, components, listeners, extension points) | — (legacy reads them from the JDOM document) | `appContainerDescriptor` / `projectContainerDescriptor` / `moduleContainerDescriptor` |
| `extensions` (EP FQN → `ExtensionElement`) | — (legacy reads from document) | `extensions` (EP FQN → JDOM `Element`s) |
| `actions` (`ActionElement` + `XmlElement` subtree) | — | `actions` |
| `resourceBundleBaseName` | `resourceBundle` | — (not on the model) |

Community-only fields with **no verifier counterpart** (dropped by the adapter, available for
future use): `strictUntilBuild`, `isSeparateJar`, `isBundledUpdateAllowed`, `isRestartRequired`,
`isLicenseOptional`, `isIndependentFromCoreClassLoader`, `firstNamespaceOfContentTag`,
service `open`/`preload=notLightEdit` nuances retained but see below, EP `hasAttributes`,
content module `embeddedDescriptorContent` (inline content-module descriptors; the verifier has
its own inline-module support via `PluginModuleResolver`).

Verifier-only bean fields with no community counterpart: `formatVersion` (`<idea-plugin version=`),
`isInternal`, `helpSets`, `locale` (community skips these elements deliberately).

## 5. Behavioral differences

### Intentional in intellij-community (runtime is the reference; adapter follows community behavior)

1. **`<xi:include>` strictness.** Community resolves includes to the *classloader load path*
   (`LoadPathUtil.toLoadPath`): relative hrefs live under `META-INF/` (or the current include
   base), absolute (`/…`) and `intellij.*`/`fleet.*`/`kotlin.*` hrefs live at the resource root.
   The legacy `XIncluder` additionally probes the parent directory and `META-INF` of any base
   (`InParentPathResourceResolver`, `MetaInfResourceResolver`) — *more lenient than the IDE
   runtime*, i.e. the legacy path can accept a plugin whose includes would fail to resolve in the
   IDE. The adapter follows community semantics; a plugin relying on the legacy leniency parses
   differently (this is arguably a legacy bug, not a community regression).
2. **`xpointer` support removed.** Community only accepts the default `xpointer(/idea-plugin/*)`
   (and `xpointer(/idea-plugin/extensionPoints/*)` inside `<extensionPoints>`), throwing on
   anything else; legacy implements generic element selection. Intentional (IJPL deprecation).
3. **`includeIf`/`includeUnless` disabled.** Community logs a warning and *skips the include
   element entirely* (IJPL-215563); legacy resolves them against system properties. Intentional.
4. **Non-root `<xi:include>`** (inside `<extensions>`, `<actions>`): community logs an error and
   skips (for `<extensionPoints>` it resolves but keeps *only* extension points — upstream TODO
   notes misc extensions are not copied); legacy resolves includes anywhere in the tree.
5. **`id`/`version` redefinition rules.** Community ignores re-definition for known Kotlin plugin
   ids and `com.intellij`, logs a warning otherwise; legacy JAXB semantics: last `<id>` element
   wins silently.
6. **`<depends>` without text is dropped** (`getNullifiedContent … ?: return`); legacy keeps a
   bean with a null/blank id and reports `InvalidDependencyId`. Related: community trims all
   element content/attributes (`getNullifiedContent`), legacy trims selectively — e.g. a
   whitespace-only `<version> </version>` is `null` in community but `" "` in legacy.
7. **Tri-state `optional` lost.** `DependsElement.isOptional` is boolean; the bean distinguishes
   explicit `optional="false"` (→ `SuperfluousNonOptionalDependencyDeclaration` warning). The
   adapter cannot reproduce that warning. Same for `<vendor>`' attributes `logo` etc. (not read by
   community parser → `vendor.logo` unavailable; not used by verifier validation).
8. **`postStartupActivity` extension bodies are discarded** and extension elements without any
   attribute/child are stored as `null` bodies; the EP name and count are preserved. The adapter
   reconstructs placeholder JDOM elements; *original tag names of extensions declared with
   `point="…"` are not retained* by the community model — the adapter uses the EP name's last
   segment. Consumers that match on exact extension tag names would observe a difference.
9. **Unknown root elements / unknown `<dependencies>` children.** Community logs errors (root) or
   throws (`Unknown content item type`); JAXB silently ignores unknown elements. The adapter maps
   these throws to `UnableToReadDescriptor`.
10. **Malformed XML**: community uses Aalto (non-coalescing StAX), legacy uses JDOM/SAX. Error
    *messages* differ; in the current slice the JDOM parse still happens first (loaders), so
    malformed archives/XML are rejected identically by both modes.

### Differences that look like bugs/regressions (flagged, need an explicit decision)

* Legacy drops `<extensions xmlns="…">` blocks entirely (JDOM `getChildren("extensions")` is
  namespace-sensitive), while the community parser matches by local name and registers such
  extensions like the IDE does — found by the differential tests; legacy behavior looks like a bug.
* Legacy `XIncluder` leniency (see #1) — likely a legacy bug, but changing it silently would
  reject previously-accepted Marketplace uploads. Needs a product decision + Marketplace corpus
  scan before flipping the default.
* Community `readContent` throws on any non-`<module>` child of `<content>` and on a missing
  module `name`, tearing down the whole parse; legacy reports a structured problem and continues.
  For a *verifier* (as opposed to the IDE) hard-fail on a single bad element loses diagnostics
  quality. The adapter surfaces these as `UnableToReadDescriptor`.
* Upstream TODO (`XmlReader.kt`): `<extensionPoints>` include copies only extension points, not
  other elements — acknowledged as a possible bug upstream.
* `LoadPathUtil.getChildBaseDir` is marked `FIXME … bugged when relative path is an absolute path
  outside /META-INF` upstream — vendored as-is for fidelity.

## 6. Dependencies, licensing, build impact

* New Gradle module `structure-intellij-community` (published like the other structure artifacts).
  `structure-intellij` gains an `implementation` dependency on it.
* New third-party dependencies (both Maven Central, both Apache 2.0):
  * `com.fasterxml:aalto-xml:1.3.3` (the StAX implementation the IDE itself uses; brings
    `org.codehaus.woodstox:stax2-api` transitively).
* Vendored sources are Apache 2.0 (intellij-community), same license as this repository; copyright
  headers preserved. No license change required, but the vendoring must be documented in the
  release notes / NOTICE if the artifact is published.
* The vendored code keeps upstream packages (`com.intellij.platform.pluginSystem.parser.impl`,
  `com.intellij.util.xml.dom`). **Risk:** classpath clash if a consumer also has an IntelliJ
  platform artifact containing the same packages (`util` contains `com.intellij.util.xml.dom`
  in recent versions; the parser package is new and unpublished). Shims live in a repo-owned
  package to avoid clashing with `platform-util` (used by `structure-ide-jps`). If this slice
  graduates, either relocate packages or (preferably) consume the future upstream artifact.
* The module requires Kotlin language level 2.0 + `-Xwhen-guards` (the rest of the repo compiles
  with language level 1.4); this is configured per-module and does not affect other modules.

## 7. Not in this slice (kept on the legacy path even when the flag is on)

* Descriptor *discovery* (archives, lib dirs, classpath JARs), ZIP/JAR validation, icon/theme
  loading, third-party-dependency files.
* `IdePlugin.underlyingDocument` is the pre-XInclude JDOM document in adapter mode (legacy mode
  stores the XIncluded one). Parity tests exclude it.
* Problem/validation parity is kept by reusing `PluginBeanValidator` over the mapped bean; the
  handful of problems that depend on information the community model does not retain
  (`SuperfluousNonOptionalDependencyDeclaration`, `InvalidDependencyId` for blank `<depends>`)
  are documented above.
