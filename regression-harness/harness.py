#!/usr/bin/env python3
"""Corpus-driven compatibility regression harness for IntelliJ Plugin Verifier.

Runs two implementations of the plugin parsing/verification stack
(structure-intellij builds identified by Maven coordinates, the local source
tree, or pre-resolved classpath files) against the same reproducible corpus of
plugin archives, normalizes the outputs, classifies the differences and emits
a Markdown + JSON summary.

See regression-harness/README.md for documented commands.
"""

import argparse
import difflib
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile
from pathlib import Path

HARNESS_DIR = Path(__file__).resolve().parent
REPO_ROOT = HARNESS_DIR.parent
DEFAULT_GROUP_ARTIFACT = "org.jetbrains.intellij.plugins:structure-intellij"
LOCAL_VERSION = "dev"
MAVEN_METADATA_URL = (
    "https://repo1.maven.org/maven2/org/jetbrains/intellij/plugins/"
    "structure-intellij/maven-metadata.xml"
)
UNSUPPORTED = "<UNSUPPORTED>"
CATEGORIES = [
    "parsing-failure",
    "dependency-resolution",
    "verification-result",
    "report-format-only",
    "identical",
]
# Archive entry timestamp for deterministic fixture packaging.
FIXED_ZIP_DATE = (2000, 1, 1, 0, 0, 0)


def log(msg):
    print(f"[harness] {msg}", flush=True)


# --------------------------------------------------------------------------
# Fixture corpus
# --------------------------------------------------------------------------

def load_manifest(manifest_path):
    with open(manifest_path, encoding="utf-8") as f:
        return json.load(f)


def pack_dir_as_zip(src_dir: Path, dest_zip: Path, tmp_dir: Path):
    """Deterministically zip src_dir. Sub-directories whose names end in .jar
    or .zip are packed into nested archives first (text-only corpus in git)."""
    entries = []

    def collect(directory: Path, prefix: str):
        for child in sorted(directory.iterdir(), key=lambda p: p.name):
            name = prefix + child.name
            if child.is_dir():
                if child.name.endswith((".jar", ".zip")):
                    nested = tmp_dir / f"nested-{hashlib.sha1(name.encode()).hexdigest()}"
                    pack_dir_as_zip(child, nested, tmp_dir)
                    entries.append((name, nested.read_bytes()))
                else:
                    collect(child, name + "/")
            else:
                entries.append((name, child.read_bytes()))

    collect(src_dir, "")
    dest_zip.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(dest_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, data in sorted(entries):
            info = zipfile.ZipInfo(name, date_time=FIXED_ZIP_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zf.writestr(info, data)


def materialize_dir(src_dir: Path, dest_dir: Path, tmp_dir: Path):
    """Copy a fixture tree to dest_dir, packing *.jar/*.zip sub-dirs into archives."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    for child in sorted(src_dir.iterdir(), key=lambda p: p.name):
        target = dest_dir / child.name
        if child.is_dir():
            if child.name.endswith((".jar", ".zip")):
                pack_dir_as_zip(child, target, tmp_dir)
            else:
                materialize_dir(child, target, tmp_dir)
        else:
            shutil.copy2(child, target)


def sha256_of(path: Path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def build_corpus(manifest, work_dir: Path, include_remote: bool, fixture_filter):
    """Returns list of (fixture_id, artifact_path). Artifact is a file or directory."""
    corpus_dir = work_dir / "corpus"
    tmp_dir = work_dir / "pack-tmp"
    if corpus_dir.exists():
        shutil.rmtree(corpus_dir)
    if tmp_dir.exists():
        shutil.rmtree(tmp_dir)
    corpus_dir.mkdir(parents=True)
    tmp_dir.mkdir(parents=True)

    corpus = []
    for fixture in manifest["fixtures"]:
        fid = fixture["id"]
        if fixture_filter and fid not in fixture_filter:
            continue
        source = fixture["source"]
        stype = source["type"]
        if stype == "builtin":
            content = HARNESS_DIR / "fixtures" / fid / "content"
            if not content.is_dir():
                raise SystemExit(f"builtin fixture '{fid}' has no content directory: {content}")
            package = source["package"]
            if package in ("jar", "zip"):
                artifact = corpus_dir / f"{fid}.{package}"
                pack_dir_as_zip(content, artifact, tmp_dir)
            elif package == "dir":
                artifact = corpus_dir / fid
                materialize_dir(content, artifact, tmp_dir)
            else:
                raise SystemExit(f"fixture '{fid}': unknown package kind '{package}'")
        elif stype == "url":
            if not include_remote:
                log(f"skipping remote fixture '{fid}' (pass --remote to include)")
                continue
            artifact = corpus_dir / (fid + Path(source["url"]).suffix)
            log(f"downloading {source['url']}")
            urllib.request.urlretrieve(source["url"], artifact)
            digest = sha256_of(artifact)
            if digest != source["sha256"]:
                raise SystemExit(
                    f"fixture '{fid}': sha256 mismatch: expected {source['sha256']}, got {digest}"
                )
        elif stype == "local":
            path = Path(source["path"])
            if not path.is_absolute():
                path = REPO_ROOT / path
            if not path.exists():
                if fixture.get("optional"):
                    log(f"skipping optional local fixture '{fid}' (missing: {path})")
                    continue
                raise SystemExit(f"fixture '{fid}': local artifact not found: {path}")
            artifact = path
        else:
            raise SystemExit(f"fixture '{fid}': unknown source type '{stype}'")
        corpus.append((fid, artifact))

    shutil.rmtree(tmp_dir, ignore_errors=True)
    return corpus


def add_corpus_dir(corpus, corpus_dir: Path):
    for path in sorted(corpus_dir.iterdir()):
        if path.suffix in (".jar", ".zip") and path.is_file():
            corpus.append((f"external-{path.stem}", path))
    return corpus


# --------------------------------------------------------------------------
# Implementation resolution
# --------------------------------------------------------------------------

def gradlew():
    # GRADLE_BIN overrides the wrapper for environments where the wrapper
    # cannot download its distribution (e.g. sandboxed networks).
    override = os.environ.get("GRADLE_BIN")
    if override:
        return override
    return str(REPO_ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))


def run_cmd(cmd, **kwargs):
    log("$ " + " ".join(str(c) for c in cmd))
    subprocess.run(cmd, check=True, **kwargs)


def latest_published_version():
    with urllib.request.urlopen(MAVEN_METADATA_URL, timeout=60) as resp:
        body = resp.read().decode("utf-8")
    import re
    m = re.search(r"<release>([^<]+)</release>", body)
    if not m:
        raise SystemExit("could not determine latest published structure-intellij version")
    return m.group(1)


def publish_local_build(skip_build):
    if skip_build:
        log("skipping local publishToMavenLocal (--skip-local-build)")
        return
    log("publishing local structure-intellij (+ structure-base) to Maven Local as version 'dev'")
    run_cmd([
        gradlew(), "-p", str(REPO_ROOT / "intellij-plugin-structure"),
        # Publication names already end in "Publication" (see
        # intellij-plugin-structure/build.gradle.kts), hence the doubled suffix.
        "publishBasePublicationPublicationToMavenLocal",
        "publishIntellijPublicationPublicationToMavenLocal",
    ], cwd=REPO_ROOT)


def resolve_implementation(spec, side, work_dir: Path, skip_build):
    """Resolve an implementation spec to (display_name, classpath_entries).

    Spec forms:
      local            -> local source tree, published to Maven Local as 'dev'
      latest           -> latest structure-intellij release on Maven Central
      3.331            -> that published version
      g:a:v            -> arbitrary Maven coordinates
      cp:/path/file    -> pre-resolved classpath file (one entry per line)
    """
    if spec.startswith("cp:"):
        cp_file = Path(spec[3:])
        entries = [line for line in cp_file.read_text().splitlines() if line.strip()]
        return f"classpath-file:{cp_file}", entries

    if spec == "local":
        publish_local_build(skip_build)
        gav = f"{DEFAULT_GROUP_ARTIFACT}:{LOCAL_VERSION}"
        display = f"local source tree ({gav})"
    elif spec == "latest":
        version = latest_published_version()
        gav = f"{DEFAULT_GROUP_ARTIFACT}:{version}"
        display = f"latest release ({gav})"
    elif ":" in spec:
        gav = spec
        display = gav
    else:
        gav = f"{DEFAULT_GROUP_ARTIFACT}:{spec}"
        display = gav

    out_file = work_dir / "classpaths" / f"{side}.txt"
    run_cmd([
        gradlew(), "-p", str(HARNESS_DIR / "resolver"), "writeClasspath",
        f"-PimplGav={gav}", f"-PoutFile={out_file}",
        "--no-configuration-cache", "--quiet",
    ], cwd=REPO_ROOT)
    entries = [line for line in out_file.read_text().splitlines() if line.strip()]
    return display, entries


def compile_dump_tool(work_dir: Path):
    tool_classes = work_dir / "tool-classes"
    tool_classes.mkdir(parents=True, exist_ok=True)
    run_cmd([
        "javac", "--release", "11", "-d", str(tool_classes),
        str(HARNESS_DIR / "tools" / "StructureDump.java"),
    ])
    return tool_classes


# --------------------------------------------------------------------------
# Running the dumps
# --------------------------------------------------------------------------

def run_dump(tool_classes, classpath_entries, artifact: Path, out_json: Path,
             extract_dir: Path, timeout):
    classpath = os.pathsep.join([str(tool_classes)] + classpath_entries)
    cmd = ["java", "-cp", classpath, "StructureDump",
           str(artifact), str(out_json), str(extract_dir)]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return {"outcome": "harness-error", "error": f"timeout after {timeout}s"}
    if proc.returncode != 0 or not out_json.exists():
        return {
            "outcome": "harness-error",
            "error": f"exit code {proc.returncode}",
            "stderr": proc.stderr.strip().splitlines()[-15:],
        }
    with open(out_json, encoding="utf-8") as f:
        return json.load(f)


# --------------------------------------------------------------------------
# Normalization and classification
# --------------------------------------------------------------------------

def strip_unsupported(a, b):
    """Remove map keys where either side reports <UNSUPPORTED> (accessor missing
    in that library version). Returns (a', b', removed_keys)."""
    removed = set()

    def walk(x, y, path):
        if isinstance(x, dict) and isinstance(y, dict):
            out_x, out_y = {}, {}
            for key in sorted(set(x) | set(y)):
                vx, vy = x.get(key), y.get(key)
                if vx == UNSUPPORTED or vy == UNSUPPORTED:
                    removed.add(path + key)
                    continue
                rx, ry = walk(vx, vy, path + key + ".")
                out_x[key], out_y[key] = rx, ry
            return out_x, out_y
        return x, y

    ax, bx = walk(a, b, "")
    return ax, bx, removed


def problem_types(dump, levels):
    """Sorted problem 'LEVEL:Type' strings from a dump, filtered by level set."""
    problems = []
    if dump.get("outcome") == "fail":
        problems = dump.get("problems", [])
    elif dump.get("outcome") == "success":
        problems = list(dump.get("warnings") or []) + list(dump.get("unacceptableWarnings") or [])
    if problems == UNSUPPORTED:
        return []
    return sorted(
        f"{p.get('level')}:{p.get('type')}" for p in problems
        if p.get("level") in levels
    )


def projections(dump):
    plugin = dump.get("plugin") or {}
    return {
        "parsing": {
            "outcome": dump.get("outcome"),
            "exception": dump.get("exception"),
            "errors": problem_types(dump, {"ERROR"}),
            "identity": {
                key: plugin.get(key)
                for key in ("pluginId", "pluginVersion", "sinceBuild", "untilBuild")
            },
        },
        "dependency": {
            key: plugin.get(key)
            for key in (
                "dependencies", "definedModules", "pluginAliases",
                "contentModules", "moduleDescriptors", "optionalDescriptors",
            )
        },
        "verification": {
            "diagnostics": problem_types(dump, {"WARNING", "UNACCEPTABLE_WARNING"}),
        },
    }


def canonical(obj):
    return json.dumps(obj, indent=2, sort_keys=True, ensure_ascii=False)


def classify(baseline_dump, candidate_dump):
    """Returns (category, details). Categories per README."""
    base, cand, removed = strip_unsupported(baseline_dump, candidate_dump)
    base_proj, cand_proj = projections(base), projections(cand)

    details = {"unsupported_fields": sorted(removed)}
    category = "identical"
    for name, cat in (
        ("parsing", "parsing-failure"),
        ("dependency", "dependency-resolution"),
        ("verification", "verification-result"),
    ):
        if base_proj[name] != cand_proj[name]:
            category = cat
            details["projection"] = name
            details["baseline"] = base_proj[name]
            details["candidate"] = cand_proj[name]
            break
    else:
        if canonical(base) != canonical(cand):
            category = "report-format-only"

    if category != "identical":
        diff = list(difflib.unified_diff(
            canonical(base).splitlines(), canonical(cand).splitlines(),
            fromfile="baseline", tofile="candidate", lineterm="",
        ))
        details["diff"] = diff[:120]
        details["diff_truncated"] = len(diff) > 120
    return category, details


# --------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------

def write_reports(out_dir: Path, meta, results):
    counts = {c: 0 for c in CATEGORIES}
    for r in results:
        counts[r["category"]] += 1

    report = {"meta": meta, "summary": counts, "fixtures": results}
    (out_dir / "report.json").write_text(canonical(report) + "\n", encoding="utf-8")

    lines = []
    lines.append("# Plugin Verifier compatibility regression report")
    lines.append("")
    lines.append(f"- Baseline implementation: `{meta['baseline']}`")
    lines.append(f"- Candidate implementation: `{meta['candidate']}`")
    lines.append(f"- Fixtures compared: {meta['fixture_count']}")
    lines.append(f"- Generated: {meta['generated']}")
    lines.append("")
    lines.append("## Summary")
    lines.append("")
    lines.append("| Category | Count |")
    lines.append("|---|---|")
    for c in CATEGORIES:
        lines.append(f"| {c} | {counts[c]} |")
    lines.append("")
    lines.append("## Fixtures")
    lines.append("")
    lines.append("| Fixture | Category |")
    lines.append("|---|---|")
    for r in results:
        lines.append(f"| `{r['fixture']}` | {r['category']} |")
    lines.append("")

    differing = [r for r in results if r["category"] != "identical"]
    if differing:
        lines.append("## Differences")
        for r in differing:
            lines.append("")
            lines.append(f"### `{r['fixture']}` — {r['category']}")
            lines.append("")
            details = r["details"]
            if "projection" in details:
                lines.append(f"Differing projection: **{details['projection']}**")
                lines.append("")
                lines.append("Baseline:")
                lines.append("```json")
                lines.append(canonical(details["baseline"]))
                lines.append("```")
                lines.append("Candidate:")
                lines.append("```json")
                lines.append(canonical(details["candidate"]))
                lines.append("```")
            if details.get("diff"):
                lines.append("<details><summary>Normalized dump diff</summary>")
                lines.append("")
                lines.append("```diff")
                lines.extend(details["diff"])
                if details.get("diff_truncated"):
                    lines.append("... (truncated)")
                lines.append("```")
                lines.append("</details>")
    else:
        lines.append("No differences detected.")
    lines.append("")

    unsupported = sorted({f for r in results for f in r["details"].get("unsupported_fields", [])})
    if unsupported:
        lines.append("## Fields excluded from comparison (accessor missing in one implementation)")
        lines.append("")
        for f in unsupported:
            lines.append(f"- `{f}`")
        lines.append("")

    (out_dir / "report.md").write_text("\n".join(lines), encoding="utf-8")


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", default="latest",
                        help="baseline implementation: 'local', 'latest', a version, g:a:v, or cp:<file> (default: latest)")
    parser.add_argument("--candidate", default="local",
                        help="candidate implementation (default: local)")
    parser.add_argument("--fixtures", default=None,
                        help="comma-separated fixture ids to run (default: all)")
    parser.add_argument("--corpus-dir", default=None,
                        help="directory with extra locally-supplied .jar/.zip plugin archives")
    parser.add_argument("--remote", action="store_true",
                        help="include manifest fixtures with source type 'url'")
    parser.add_argument("--out", default=None,
                        help="output directory (default: regression-harness/work/<baseline>-vs-<candidate>)")
    parser.add_argument("--skip-local-build", action="store_true",
                        help="assume 'dev' artifacts are already in Maven Local")
    parser.add_argument("--timeout", type=int, default=180,
                        help="per-fixture timeout in seconds (default: 180)")
    parser.add_argument("--fail-on", default="none",
                        choices=["none", "semantic", "any"],
                        help="exit non-zero when differences of this severity are found "
                             "(semantic = everything except report-format-only)")
    args = parser.parse_args()

    def slug(s):
        return "".join(c if c.isalnum() or c in ".-_" else "_" for c in s)

    work_dir = HARNESS_DIR / "work"
    out_dir = Path(args.out) if args.out else work_dir / f"{slug(args.baseline)}-vs-{slug(args.candidate)}"
    out_dir.mkdir(parents=True, exist_ok=True)

    manifest = load_manifest(HARNESS_DIR / "manifest.json")
    fixture_filter = set(args.fixtures.split(",")) if args.fixtures else None
    corpus = build_corpus(manifest, work_dir, args.remote, fixture_filter)
    if args.corpus_dir:
        corpus = add_corpus_dir(corpus, Path(args.corpus_dir))
    if not corpus:
        raise SystemExit("empty corpus: nothing to compare")
    log(f"corpus: {len(corpus)} fixtures")

    baseline_name, baseline_cp = resolve_implementation(
        args.baseline, "baseline", work_dir, args.skip_local_build)
    candidate_name, candidate_cp = resolve_implementation(
        args.candidate, "candidate", work_dir, args.skip_local_build)
    tool_classes = compile_dump_tool(work_dir)

    results = []
    for fid, artifact in corpus:
        dumps = {}
        for side, classpath in (("baseline", baseline_cp), ("candidate", candidate_cp)):
            out_json = out_dir / "dumps" / side / f"{fid}.json"
            out_json.parent.mkdir(parents=True, exist_ok=True)
            extract_dir = work_dir / "extract" / side / fid
            if extract_dir.exists():
                shutil.rmtree(extract_dir)
            dumps[side] = run_dump(tool_classes, classpath, artifact, out_json,
                                   extract_dir, args.timeout)
        category, details = classify(dumps["baseline"], dumps["candidate"])
        log(f"{fid}: {category}")
        results.append({"fixture": fid, "category": category, "details": details})

    meta = {
        "baseline": baseline_name,
        "candidate": candidate_name,
        "fixture_count": len(corpus),
        "generated": time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime()),
    }
    write_reports(out_dir, meta, results)
    log(f"reports written to {out_dir}/report.md and {out_dir}/report.json")

    counts = {c: 0 for c in CATEGORIES}
    for r in results:
        counts[r["category"]] += 1
    log("summary: " + ", ".join(f"{c}={n}" for c, n in counts.items() if n))

    semantic = sum(counts[c] for c in
                   ("parsing-failure", "dependency-resolution", "verification-result"))
    if args.fail_on == "any" and semantic + counts["report-format-only"] > 0:
        sys.exit(1)
    if args.fail_on == "semantic" and semantic > 0:
        sys.exit(1)


if __name__ == "__main__":
    main()
