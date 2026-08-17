# Observed differences — compatibility regression harness

This report documents actual harness runs performed on 2026-07-06 with the
corpus of 17 builtin fixtures from [`manifest.json`](manifest.json).
Commands are reproducible; see [README.md](README.md). "local" below is the
source tree at commit `35b2c79` (branch base), published to Maven Local as
`structure-intellij:dev`.

## Runs

| # | Baseline | Candidate | identical | report-format-only | verification-result | dependency-resolution | parsing-failure |
|---|---|---|---|---|---|---|---|
| 1 | 3.331 (latest release) | local | **17** | 0 | 0 | 0 | 0 |
| 2 | 3.330 | 3.331 | 16 | 0 | 0 | **1** | 0 |
| 3 | 3.310 | local | 6 | 0 | 0 | **10** | **1** |

Run 1 (`--baseline latest --candidate local`) found the local tree
behaviorally identical to the latest release across the whole corpus — the
current HEAD sits immediately after the 1.408 / structure 3.331 release, so
this is the expected result, and it doubles as a determinism check (paths,
extraction directories and archive packaging do not leak into the dumps).

Run 2 (`--baseline 3.330 --candidate 3.331`) flagged exactly one fixture,
`jar-multiple-dependencies-blocks`, as `dependency-resolution` — the known
behavior change shipped in 3.331 (MP-8208, PR #1525). This run acts as the
harness's self-test: a deliberately planted, dated behavior change is
detected, and nothing else on the corpus moved between those two releases.

Run 3 (`--baseline 3.310 --candidate local`) covers roughly a year of
releases and surfaced three distinct behavior changes, detailed below.
Follow-up probes against intermediate published versions (3.315–3.329)
narrowed down when each landed.

## Difference 1 — UTF-8 BOM descriptors: parse failure → success (parsing-failure)

Fixture: `jar-utf8-bom` (plugin.xml prefixed with a UTF-8 byte-order mark).

- **3.310 through 3.319** fail with:
  `ERROR:UnableToReadDescriptor — "Unable to read the plugin descriptor:
  Error on line 1: Content is not allowed in prolog."`
- **3.320 and later** (including local) parse the descriptor successfully,
  including the non-ASCII vendor string.

A plugin archive that was rejected outright by older verifier builds is now
accepted. No explicit changelog entry mentions BOM handling; the tolerance
appears to have arrived as part of descriptor-reading rework in the
3.319→3.320 window.

## Difference 2 — `<depends>` dependency model change (dependency-resolution)

Fixtures: every successfully-parsed fixture using `<depends>` (9 of the 10
`dependency-resolution` hits in run 3: `flat-jar`, `zip-classic-lib`,
`zip-extra-library-jar`, `jar-xinclude`, `zip-optional-depends`,
`jar-missing-optional-config`, `jar-theme`, `jar-product-descriptor`,
`dir-plugin`).

For `<depends>com.intellij.modules.platform</depends>`,
`IdePlugin.getDependencies()` returns:

- **3.310–3.320**: `PluginDependencyImpl` with `isModule() == true`
  (module-ness inferred from the `com.intellij.modules.` id prefix);
- **3.321 and later**: `DependsPluginDependency` subtypes
  (`Mandatory` / `Optional`) with `isModule() == false`.

This matches the API refactor shipped in verifier 1.396 / structure ~3.321
(changelog: new `ContentModuleDependency` / `DependsPluginDependency` /
`PluginMainModuleDependency` classes; `dependencies` deprecated in favor of
`dependsList` et al.). The class rename alone would be representational, but
the `isModule` flip is a semantic change for any consumer of the deprecated
`getDependencies()` API that branches on module-vs-plugin dependencies, so
the harness classifies it as `dependency-resolution` rather than
`report-format-only`. Optional-descriptor resolution itself
(`config-file` mapping) is unchanged across the window.

## Difference 3 — multiple `<dependencies>` blocks: last-wins → merged (dependency-resolution)

Fixture: `jar-multiple-dependencies-blocks` (v2 descriptor with two
`<dependencies>` blocks).

- **up to 3.330**: only the **last** block is retained — the plugin
  dependency `com.intellij.modules.lang` declared in the first block is
  silently dropped;
- **3.331 and later** (MP-8208, PR #1525): blocks are merged; all three
  declared dependencies are reported.

For a real plugin shaped like this fixture, older verifier builds would
miss a mandatory dependency during dependency resolution.

## Categories not observed

No `verification-result` (WARNING / UNACCEPTABLE_WARNING drift) and no
`report-format-only` (message-wording-only) differences occurred on this
corpus across these version pairs. Both categories are implemented as part
of the classifier's ladder; the corpus keeps warning-producing fixtures
(`jar-missing-optional-config` yields
`OptionalDependencyDescriptorResolutionProblem` on both sides, and
`jar-since-until-wildcard` fails with `InvalidUntilBuildWithMagicNumber` on
both sides) precisely so that future drift in those diagnostics will be
caught. No fixture or expectation was adjusted to make any run green: run 1
being clean is a genuine result, and fixtures that fail to parse (by
design) fail identically on both sides.

## Reproduce

```bash
# Run 1
python3 regression-harness/harness.py --baseline latest --candidate local

# Run 2 (self-test: must flag exactly jar-multiple-dependencies-blocks)
python3 regression-harness/harness.py --baseline 3.330 --candidate 3.331

# Run 3
python3 regression-harness/harness.py --baseline 3.310 --candidate local

# Version probes used to date the changes
python3 regression-harness/harness.py --baseline 3.319 --candidate local \
  --fixtures flat-jar,jar-utf8-bom
python3 regression-harness/harness.py --baseline 3.321 --candidate local \
  --fixtures flat-jar,jar-utf8-bom
```

Full machine-readable results (per-fixture projections and normalized dump
diffs) are emitted next to each run's `report.md` as `report.json` under
`regression-harness/work/<baseline>-vs-<candidate>/`.
