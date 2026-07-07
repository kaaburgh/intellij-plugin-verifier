#!/usr/bin/env python3
"""Benchmark runner for Plugin Verifier over the synthetic corpus.

Measures wall-clock time, user/sys CPU time and peak RSS of
`verifier-cli check-plugin @plugins.txt <ide>` runs, and captures a JFR
profile whose execution samples are attributed to verifier phases
(plugin parsing, classpath construction, bytecode scanning, report
generation, dependency resolution).

Subcommands:
  run      measure a verifier jar over the corpus       -> work/results-<label>.json
  compare  diff two results files as a markdown table
  parity   run two verifier jars, normalize the verification reports and
           assert they are byte-identical (behavior preservation check)

See bench/README.md for documented commands.
"""

import argparse
import json
import os
import re
import resource
import shutil
import statistics
import subprocess
import sys
import time
from pathlib import Path

BENCH_DIR = Path(__file__).resolve().parent
REPO_ROOT = BENCH_DIR.parent
WORK = BENCH_DIR / "work"
DEFAULT_JAR = REPO_ROOT / "intellij-plugin-verifier/verifier-cli/build/libs/verifier-cli-dev-all.jar"

# Phase attribution: the first frame (leaf to root) whose class name matches a
# pattern decides the bucket. Order matters.
PHASE_PATTERNS = [
    ("bytecode-scan", re.compile(
        r"^(org\.objectweb\.asm\.|com\.jetbrains\.pluginverifier\.verifiers\.|"
        r"com\.jetbrains\.pluginverifier\.usages\.|com\.jetbrains\.pluginverifier\.filtering\.)")),
    ("report-generation", re.compile(
        r"^(com\.jetbrains\.pluginverifier\.output\.|com\.jetbrains\.pluginverifier\.reporting\.|"
        r"com\.jetbrains\.pluginverifier\.tasks\..*ResultPrinter)")),
    ("classpath-construction", re.compile(
        r"^(com\.jetbrains\.plugin\.structure\.classes\.|com\.jetbrains\.plugin\.structure\.jar\.|"
        r"com\.jetbrains\.plugin\.structure\.ide\.classes\.|com\.jetbrains\.plugin\.structure\.intellij\.classes\.|"
        r"com\.jetbrains\.pluginverifier\.resolution\.|com\.jetbrains\.pluginverifier\.createIdeResolver|"
        r"com\.jetbrains\.pluginverifier\.jdk\.)")),
    ("plugin-parsing", re.compile(
        r"^(com\.jetbrains\.plugin\.structure\.intellij\.|com\.jetbrains\.plugin\.structure\.base\.|"
        r"com\.jetbrains\.plugin\.structure\.ide\.|org\.jdom2\.|org\.apache\.xerces\.|com\.sun\.org\.apache\.xerces\.)")),
    ("dependency-resolution", re.compile(
        r"^com\.jetbrains\.pluginverifier\.dependencies\.")),
    ("verifier-other", re.compile(r"^com\.jetbrains\.pluginverifier\.")),
]


def log(msg):
    print(f"[bench] {msg}", flush=True)


def java_cmd(jar, corpus, reports_dir, pv_home, jfr_file=None, xmx="4g"):
    cmd = ["java", f"-Xmx{xmx}",
           f"-Dplugin.verifier.home.dir={pv_home}"]
    if jfr_file:
        # high-frequency sampling: the verification run is short, so the default
        # 10-20ms period yields too few samples for stable attribution
        cmd.append("-XX:StartFlightRecording=settings=profile,"
                   f"jdk.ExecutionSample#period=1ms,filename={jfr_file}")
    cmd += ["-jar", str(jar),
            "check-plugin", f"@{corpus}/plugins.txt", f"{corpus}/ide",
            "-offline",
            "-verification-reports-dir", str(reports_dir),
            "-verification-reports-formats", "plain,html,markdown"]
    return cmd


def measure_once(jar, corpus, out_dir: Path, jfr_file=None):
    reports = out_dir / "reports"
    pv_home = out_dir / "pv-home"
    for d in (reports, pv_home):
        if d.exists():
            shutil.rmtree(d)
    out_dir.mkdir(parents=True, exist_ok=True)

    before = resource.getrusage(resource.RUSAGE_CHILDREN)
    t0 = time.monotonic()
    proc = subprocess.run(
        java_cmd(jar, corpus, reports, pv_home, jfr_file),
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, cwd=REPO_ROOT)
    wall = time.monotonic() - t0
    after = resource.getrusage(resource.RUSAGE_CHILDREN)
    if proc.returncode != 0:
        sys.stderr.write(proc.stdout[-4000:])
        raise SystemExit(f"verifier exited with {proc.returncode}")

    (out_dir / "stdout.log").write_text(proc.stdout, encoding="utf-8")
    return {
        "wall_s": round(wall, 3),
        "cpu_user_s": round(after.ru_utime - before.ru_utime, 3),
        "cpu_sys_s": round(after.ru_stime - before.ru_stime, 3),
        # ru_maxrss is the max over all children; with one java child per
        # measurement process this is the verifier's peak RSS (KiB on Linux).
        "peak_rss_mb": round(after.ru_maxrss / 1024, 1),
        "verification_ms": parse_verification_ms(proc.stdout),
    }


def parse_verification_ms(stdout: str):
    """'Total time spent in plugin verification: 4 s 883 ms' -> 4883"""
    m = re.search(r"Total time spent in plugin verification: (?:(\d+) min )?(?:(\d+) s )?(\d+) ms", stdout)
    if not m:
        return None
    minutes, s, ms = (int(g) if g else 0 for g in m.groups())
    return minutes * 60000 + s * 1000 + ms


_FRAME_RE = re.compile(r"^\s{4,}([\w.$/<>\[\]]+)\(.*\) line: -?\d+")


def iter_stacks(jfr_file: Path):
    """Yield lists of frames ('pkg.Class.method') for each jdk.ExecutionSample,
    leaf frame first."""
    proc = subprocess.Popen(
        ["jfr", "print", "--events", "jdk.ExecutionSample", "--stack-depth", "128", str(jfr_file)],
        stdout=subprocess.PIPE, text=True)
    frames = []
    for line in proc.stdout:
        if line.startswith("jdk.ExecutionSample"):
            if frames:
                yield frames
            frames = []
            continue
        m = _FRAME_RE.match(line)
        if m:
            frames.append(m.group(1))
    if frames:
        yield frames
    proc.wait()


def profile_phases(jfr_file: Path):
    """Bucket jdk.ExecutionSample stacks into verifier phases."""
    buckets = {}
    total = 0
    for frames in iter_stacks(jfr_file):
        total += 1
        phase = classify_stack(f.rsplit(".", 1)[0] for f in frames)
        buckets[phase] = buckets.get(phase, 0) + 1
    return {
        "total_samples": total,
        "phases": {k: {"samples": v, "share": round(v / total, 4) if total else 0}
                   for k, v in sorted(buckets.items(), key=lambda e: -e[1])},
    }


def classify_stack(frames):
    for frame in frames:  # leaf first
        for phase, pattern in PHASE_PATTERNS:
            if pattern.match(frame):
                return phase
    return "jvm-other"


def hot_frames(jfr_file: Path, phase=None, top=25):
    """Top frames, optionally restricted to stacks of a given phase. Each stack
    is attributed to its first project frame (leaf first), else to the leaf."""
    counts = {}
    for frames in iter_stacks(jfr_file):
        classes = [f.rsplit(".", 1)[0] for f in frames]
        if phase is not None and classify_stack(classes) != phase:
            continue
        attributed = frames[0]
        for f, cls in zip(frames, classes):
            if cls.startswith(("com.jetbrains.", "org.objectweb.asm.")):
                attributed = f
                break
        counts[attributed] = counts.get(attributed, 0) + 1
    return sorted(counts.items(), key=lambda e: -e[1])[:top]


def allocation_summary(jfr_file: Path):
    """Approximate allocation pressure from jdk.ObjectAllocationSample."""
    proc = subprocess.run(
        ["jfr", "summary", str(jfr_file)], stdout=subprocess.PIPE, text=True, check=True)
    return proc.stdout


def cmd_run(args):
    jar = Path(args.jar).resolve()
    corpus = Path(args.corpus).resolve()
    if not (corpus / "plugins.txt").exists():
        raise SystemExit(f"corpus not found at {corpus}; run: python3 bench/gen.py --out {corpus}")
    out_root = WORK / f"run-{args.label}"
    if out_root.exists():
        shutil.rmtree(out_root)

    runs = []
    for i in range(args.runs):
        r = measure_once(jar, corpus, out_root / f"r{i}")
        log(f"run {i}: wall={r['wall_s']}s cpu={r['cpu_user_s']}+{r['cpu_sys_s']}s "
            f"rss={r['peak_rss_mb']}MB verification={r['verification_ms']}ms")
        runs.append(r)

    profile = None
    if not args.no_profile:
        jfr_file = out_root / "profile.jfr"
        log("profiling run (JFR)...")
        jfr_run = measure_once(jar, corpus, out_root / "profiled", jfr_file=jfr_file)
        profile = profile_phases(jfr_file)
        profile["profiled_run"] = jfr_run
        log("phase attribution: " + ", ".join(
            f"{k}={v['share']:.1%}" for k, v in profile["phases"].items()))

    def med(key):
        vals = [r[key] for r in runs if r[key] is not None]
        return round(statistics.median(vals), 3) if vals else None

    result = {
        "label": args.label,
        "jar": str(jar),
        "corpus": str(corpus),
        "runs": runs,
        "median": {k: med(k) for k in
                   ("wall_s", "cpu_user_s", "cpu_sys_s", "peak_rss_mb", "verification_ms")},
        "profile": profile,
    }
    out_file = WORK / f"results-{args.label}.json"
    out_file.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    log(f"median: {result['median']}")
    log(f"results written to {out_file}")


def cmd_compare(args):
    a = json.loads(Path(args.a).read_text())
    b = json.loads(Path(args.b).read_text())
    rows = [
        ("wall clock (s)", "wall_s"),
        ("CPU user (s)", "cpu_user_s"),
        ("CPU sys (s)", "cpu_sys_s"),
        ("peak RSS (MB)", "peak_rss_mb"),
        ("verification time (ms)", "verification_ms"),
    ]
    print(f"| Metric (median of {len(a['runs'])} runs) | {a['label']} | {b['label']} | delta |")
    print("|---|---|---|---|")
    for title, key in rows:
        va, vb = a["median"].get(key), b["median"].get(key)
        delta = ""
        if va and vb:
            delta = f"{(vb - va) / va * 100:+.1f}%"
        print(f"| {title} | {va} | {vb} | {delta} |")


NORMALIZE_RES = [
    (re.compile(r"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(\.\d+)?"), "<TIMESTAMP>"),
    (re.compile(r"(in|took) \d+ (ms|s)"), r"\1 <DURATION>"),
    # telemetry.txt wall-clock timings (semantic fields such as plugin size and
    # verified class counts are intentionally NOT normalized)
    (re.compile(r"(?m)^(Descriptor parsed in:).*$"), r"\1 <DURATION>"),
    (re.compile(r"(?m)^(Descriptor parsed \(raw ms\):).*$"), r"\1 <DURATION>"),
    (re.compile(r"(?m)^(Verification time(?: \(raw ms\))?:).*$"), r"\1 <DURATION>"),
]


def normalized_report_tree(reports_dir: Path):
    tree = {}
    for path in sorted(reports_dir.rglob("*")):
        if not path.is_file():
            continue
        text = path.read_bytes()
        try:
            s = text.decode("utf-8")
            for rx, repl in NORMALIZE_RES:
                s = rx.sub(repl, s)
            text = s.encode("utf-8")
        except UnicodeDecodeError:
            pass
        tree[path.relative_to(reports_dir).as_posix()] = text
    return tree


def cmd_parity(args):
    corpus = Path(args.corpus).resolve()
    trees = []
    for side, jar in (("a", args.jar_a), ("b", args.jar_b)):
        out_dir = WORK / f"parity-{side}"
        if out_dir.exists():
            shutil.rmtree(out_dir)
        log(f"parity run {side}: {jar}")
        measure_once(Path(jar).resolve(), corpus, out_dir)
        trees.append(normalized_report_tree(out_dir / "reports"))
    a, b = trees
    if set(a) != set(b):
        only_a = sorted(set(a) - set(b))
        only_b = sorted(set(b) - set(a))
        raise SystemExit(f"PARITY FAILED: report file sets differ.\nOnly in A: {only_a}\nOnly in B: {only_b}")
    diffs = [name for name in sorted(a) if a[name] != b[name]]
    if diffs:
        raise SystemExit("PARITY FAILED: differing files: " + ", ".join(diffs))
    log(f"PARITY OK: {len(a)} report files identical after normalization")


def cmd_hot(args):
    for frame, count in hot_frames(Path(args.jfr), phase=args.phase, top=args.top):
        print(f"{count:6d}  {frame}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("run", help="measure a verifier jar over the corpus")
    p.add_argument("--jar", default=str(DEFAULT_JAR))
    p.add_argument("--corpus", default=str(WORK / "corpus"))
    p.add_argument("--label", required=True)
    p.add_argument("--runs", type=int, default=5)
    p.add_argument("--no-profile", action="store_true")
    p.set_defaults(func=cmd_run)

    p = sub.add_parser("compare", help="markdown diff of two results files")
    p.add_argument("a")
    p.add_argument("b")
    p.set_defaults(func=cmd_compare)

    p = sub.add_parser("parity", help="assert two jars produce identical normalized reports")
    p.add_argument("--jar-a", required=True)
    p.add_argument("--jar-b", required=True)
    p.add_argument("--corpus", default=str(WORK / "corpus"))
    p.set_defaults(func=cmd_parity)

    p = sub.add_parser("hot", help="top frames from a JFR file, optionally per phase")
    p.add_argument("jfr")
    p.add_argument("--phase", default=None)
    p.add_argument("--top", type=int, default=25)
    p.set_defaults(func=cmd_hot)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
