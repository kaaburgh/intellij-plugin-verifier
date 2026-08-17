# Compatibility regression harness

A corpus-driven harness that compares two builds of the Plugin Verifier
parsing stack (`structure-intellij`) against the same reproducible set of
plugin archives and surfaces meaningful behavior differences.

Typical uses:

- compare the **local source tree** against the **latest published release**
  before cutting a release,
- compare **two published releases** to document behavior drift,
- run a **locally supplied corpus** of real plugin archives through both
  implementations.

No binary corpus is committed to git. The corpus is described by
[`manifest.json`](manifest.json) and consists of:

- **builtin** fixtures — small purpose-built, text-only source trees under
  [`fixtures/`](fixtures) that are packaged into JAR/ZIP archives
  deterministically at run time (directories named `*.jar`/`*.zip` become
  nested archives, entry timestamps are fixed);
- **url** fixtures — public artifacts downloaded on demand and verified
  against a pinned `sha256` (only used with `--remote`);
- **local** fixtures — artifacts supplied on the local filesystem
  (never committed), plus ad-hoc archives via `--corpus-dir`.

## Requirements

- JDK 11+ (`java`, `javac` on `PATH`)
- Python 3.8+
- network access to Maven Central (to resolve published baselines); the
  repository's Gradle wrapper is used, or set `GRADLE_BIN` to a local Gradle
  installation if the wrapper cannot download its distribution

## Usage

From the repository root:

```bash
# Compare the local source tree against the latest published release:
python3 regression-harness/harness.py --baseline latest --candidate local

# Compare two published releases:
python3 regression-harness/harness.py --baseline 3.330 --candidate 3.331

# Run only selected fixtures, include an external corpus, gate CI on
# semantic differences:
python3 regression-harness/harness.py \
  --baseline latest --candidate local \
  --fixtures flat-jar,jar-v2-content-modules \
  --corpus-dir /path/to/plugin/archives \
  --fail-on semantic
```

Implementation specs accepted by `--baseline` / `--candidate`:

| Spec | Meaning |
|---|---|
| `local` | current source tree, published to Maven Local as version `dev` |
| `latest` | newest `structure-intellij` release on Maven Central |
| `3.331` | that published version |
| `group:artifact:version` | arbitrary Maven coordinates |
| `cp:/path/to/classpath.txt` | pre-resolved classpath file, one entry per line (escape hatch for comparing arbitrary builds) |

Outputs land in `regression-harness/work/<baseline>-vs-<candidate>/`:
`report.md`, `report.json`, and the raw normalized dumps under `dumps/`.

Exit code is controlled by `--fail-on {none,semantic,any}` (default `none`);
`semantic` fails the run when any parsing/dependency/verification difference
is found, ignoring `report-format-only` noise.

## How it works

1. **Corpus build** — builtin fixtures are packaged deterministically; url
   fixtures are downloaded and checksum-verified; local artifacts are used
   in place.
2. **Implementation resolution** — each side is resolved to a full runtime
   classpath by the tiny standalone Gradle build in [`resolver/`](resolver)
   (Maven Local + Maven Central). `local` first runs
   `publishBasePublicationToMavenLocal publishIntellijPublicationToMavenLocal`
   in `intellij-plugin-structure/`.
3. **Dump** — [`tools/StructureDump.java`](tools/StructureDump.java) is
   compiled once (it has **no compile-time dependency** on the structure
   libraries; all access is reflective) and executed once per fixture per
   side. It emits a normalized JSON dump of the `PluginCreationResult`:
   plugin identity, dependencies, modules, extensions, themes, product
   descriptor, and all plugin problems.
4. **Normalization** — absolute paths are replaced with placeholders
   (`<FIXTURE_DIR>`, `<EXTRACT_DIR>`, `<TMP>`, `<RANDOM>`), unordered
   collections are sorted, telemetry/timings are excluded, and accessors
   missing in one library version are reported as `<UNSUPPORTED>` and
   excluded from comparison (listed in the report instead).
5. **Classification** — per fixture, the first differing projection wins:

   | Category | Trigger |
   |---|---|
   | `parsing-failure` | outcome (success/fail/crash) differs, ERROR-level problem types differ, or parsed plugin identity (id, version, since/until build) differs |
   | `dependency-resolution` | declared dependencies, defined modules, plugin aliases, content modules, or optional descriptor resolution differ |
   | `verification-result` | WARNING / UNACCEPTABLE_WARNING diagnostic types differ |
   | `report-format-only` | only message wording or representation differs in the full normalized dump |

## Fixtures

Seventeen builtin fixtures cover unusual plugin layouts; see
[`manifest.json`](manifest.json) for descriptions: flat JAR, classic
`lib/` ZIP, extra descriptor-less library JARs, multiple conflicting
descriptors, missing descriptor, malformed XML, `xi:include`-assembled
descriptors, optional `<depends>` (config present and missing), v2 content
modules, suspicious since/until ranges, theme plugins, paid-plugin product
descriptors, a JAR at the ZIP root, UTF-8 BOM descriptors, exploded-directory
plugins, and multiple `<dependencies>` blocks (MP-8208).

`jar-multiple-dependencies-blocks` doubles as a self-test of the harness:
it is a known behavior change between structure-intellij 3.330 and 3.331
(PR #1525), so `--baseline 3.330 --candidate 3.331` must classify exactly
that fixture as `dependency-resolution`.

## CI

`.github/workflows/compat-regression.yml` runs the harness (latest release
vs. the PR's source tree) on pull requests touching
`intellij-plugin-structure/` and on manual dispatch, publishes `report.md`
to the job summary and uploads the full report as an artifact. Differences
do not fail the build by default — they are surfaced for review; tighten
with `--fail-on semantic` once a comparison is expected to be clean.
