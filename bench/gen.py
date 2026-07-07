#!/usr/bin/env python3
"""Deterministic synthetic corpus generator for the Plugin Verifier benchmark.

Generates (no binaries in git; everything is derived from this script):

  <out>/ide/                    a minimal IDE distribution: build.txt + lib/idea-core.jar
                                (IDEA CORE descriptor + generated API classes)
  <out>/plugins/plugin<K>.zip   plugin archives (plugin<K>/lib/plugin<K>.jar) whose
                                classes heavily reference the generated IDE API
  <out>/plugins.txt             plugin list file for `check-plugin @plugins.txt <ide>`

Design notes:
- Plugins are compiled against the IDE API *plus* an `extra` API package that is
  absent from the packaged IDE, so a small fraction of classes produce genuine
  "missing class" problems and exercise the problem-registration paths.
- A fraction of API methods are @Deprecated and called by plugins to exercise
  deprecated-usage collection.
- All randomness is seeded; jar/zip entries use fixed timestamps, so the corpus
  is bit-reproducible for a given JDK.
"""

import argparse
import random
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

FIXED_ZIP_DATE = (2000, 1, 1, 0, 0, 0)

IDE_BUILD = "IU-231.9999"

CORE_PLUGIN_XML = """<idea-plugin version="2">
  <id>com.intellij</id>
  <name>IDEA CORE</name>
  <version>1.0</version>
  <vendor url="https://example.com">Bench</vendor>
  <description><![CDATA[Synthetic IDE core used by the Plugin Verifier benchmark harness.]]></description>
  <idea-version since-build="131"/>
  <module value="com.intellij.modules.platform"/>
  <module value="com.intellij.modules.lang"/>
</idea-plugin>
"""

PLUGIN_XML_TEMPLATE = """<idea-plugin>
  <id>org.bench.plugin{k}</id>
  <name>Bench Corpus Sample {k}</name>
  <version>1.0.{k}</version>
  <vendor email="bench@example.com" url="https://example.com">Bench Vendor</vendor>
  <description><![CDATA[Synthetic plugin number {k} generated for the Plugin Verifier benchmark harness corpus.]]></description>
  <idea-version since-build="231" until-build="231.*"/>
  <depends>com.intellij.modules.platform</depends>
</idea-plugin>
"""


def write_zip(dest: Path, entries):
    """entries: list of (name, bytes). Deterministic order + timestamps."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, data in sorted(entries):
            info = zipfile.ZipInfo(name, date_time=FIXED_ZIP_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zf.writestr(info, data)


def zip_tree(dest: Path, root: Path, prefix=""):
    entries = []
    for path in sorted(root.rglob("*")):
        if path.is_file():
            entries.append((prefix + path.relative_to(root).as_posix(), path.read_bytes()))
    write_zip(dest, entries)


def api_class_name(rng, packages, classes_per_package):
    p = rng.randrange(packages)
    c = rng.randrange(classes_per_package)
    return f"com.intellij.gen.api.p{p}.Api{p}_{c}", p, c


def gen_ide_sources(src_dir: Path, packages: int, classes_per_package: int, rng):
    """API classes with cross-references, inheritance chains, interfaces and
    deprecated members."""
    for p in range(packages):
        pkg_dir = src_dir / "com" / "intellij" / "gen" / "api" / f"p{p}"
        pkg_dir.mkdir(parents=True, exist_ok=True)
        for c in range(classes_per_package):
            name = f"Api{p}_{c}"
            is_interface = c % 9 == 8
            lines = [f"package com.intellij.gen.api.p{p};", ""]
            if is_interface:
                lines.append(f"public interface {name} {{")
                lines.append("  int size();")
                lines.append("  default String label() { return \"api\" + size(); }")
                lines.append("}")
            else:
                extends = ""
                if c % 5 == 4 and c > 0 and (c - 1) % 9 != 8:
                    extends = f" extends Api{p}_{c - 1}"
                implements = ""
                if c % 9 == 0 and classes_per_package > 8:
                    iface = (c + 8) % classes_per_package
                    if iface % 9 == 8:
                        implements = f" implements Api{p}_{iface}"
                lines.append(f"public class {name}{extends}{implements} {{")
                lines.append(f"  public static final String CONSTANT = \"api-{p}-{c}\";")
                lines.append("  public int counter;")
                lines.append(f"  public static {name} create() {{ return new {name}(); }}")
                lines.append("  public String describe(int x) { return CONSTANT + x + counter; }")
                lines.append("  public int compute(int a, int b) { return a * 31 + b + counter; }")
                if implements:
                    lines.append("  public int size() { return counter; }")
                if c % 4 == 1:
                    lines.append("  @Deprecated")
                    lines.append("  public String legacy() { return describe(42); }")
                if c % 3 == 2:
                    other, _, _ = api_class_name(rng, packages, classes_per_package)
                    # only reference concrete classes (interfaces have no create())
                    if not other.endswith("_8") or True:
                        pass
                    other_c = int(other.rsplit("_", 1)[1])
                    if other_c % 9 != 8:
                        lines.append(f"  public void accept({other} o) {{ counter += o.compute(1, 2); }}")
                lines.append("}")
            (pkg_dir / f"{name}.java").write_text("\n".join(lines) + "\n", encoding="utf-8")


def gen_extra_sources(src_dir: Path, count: int):
    """Compile-only API that the packaged IDE does NOT contain."""
    pkg_dir = src_dir / "com" / "intellij" / "gen" / "extra"
    pkg_dir.mkdir(parents=True, exist_ok=True)
    for i in range(count):
        (pkg_dir / f"Extra{i}.java").write_text(
            "package com.intellij.gen.extra;\n\n"
            f"public class Extra{i} {{\n"
            f"  public String value() {{ return \"extra-{i}\"; }}\n"
            "}\n",
            encoding="utf-8",
        )


def gen_plugin_sources(src_dir: Path, k: int, class_count: int, packages: int,
                       classes_per_package: int, extra_count: int, rng):
    per_pkg = 50
    for i in range(class_count):
        p, c = i // per_pkg, i % per_pkg
        pkg_dir = src_dir / "org" / "bench" / f"plugin{k}" / f"p{p}"
        pkg_dir.mkdir(parents=True, exist_ok=True)
        name = f"C{p}_{c}"
        # pick concrete API classes to reference
        def concrete_api():
            while True:
                fqn, _, cc = api_class_name(rng, packages, classes_per_package)
                if cc % 9 != 8:
                    return fqn, cc

        base_fqn, base_c = concrete_api()
        extends = f" extends {base_fqn}" if i % 3 == 0 else ""
        lines = [f"package org.bench.plugin{k}.p{p};", "", f"public class {name}{extends} {{"]
        body_calls = []
        for j in range(6):
            fqn, cc = concrete_api()
            var = f"v{j}"
            body_calls.append(f"    {fqn} {var} = {fqn}.create();")
            body_calls.append(f"    acc += {var}.compute(x + {j}, {j}) + {var}.describe(x).length();")
            body_calls.append(f"    {var}.counter = acc;")
        lines.append("  public int run(int x) {")
        lines.append("    int acc = 0;")
        lines.extend(body_calls)
        if extends:
            lines.append("    acc += compute(x, 3) + CONSTANT.length();")
        lines.append("    return acc;")
        lines.append("  }")
        if extends:
            lines.append("  @Override public int compute(int a, int b) { return super.compute(a, b) + 1; }")
        if i % 20 == 5:
            # deprecated API usage
            fqn = None
            while fqn is None:
                cand, _, cc = api_class_name(rng, packages, classes_per_package)
                if cc % 4 == 1 and cc % 9 != 8:
                    fqn = cand
            lines.append(f"  public String old() {{ return new {fqn}().legacy(); }}")
        if i % 25 == 7:
            # reference to compile-only API: a genuine missing class at verification time
            e = rng.randrange(extra_count)
            lines.append(f"  public Object missing() {{ return new com.intellij.gen.extra.Extra{e}().value(); }}")
        lines.append("}")
        (pkg_dir / f"{name}.java").write_text("\n".join(lines) + "\n", encoding="utf-8")


def javac(sources_dir: Path, out_dir: Path, classpath=None):
    out_dir.mkdir(parents=True, exist_ok=True)
    sources = [str(p) for p in sorted(sources_dir.rglob("*.java"))]
    listing = out_dir.parent / (out_dir.name + "-sources.txt")
    listing.write_text("\n".join(sources), encoding="utf-8")
    cmd = ["javac", "--release", "11", "-nowarn", "-d", str(out_dir)]
    if classpath:
        cmd += ["-cp", str(classpath)]
    cmd.append(f"@{listing}")
    subprocess.run(cmd, check=True)


def classes_entries(classes_dir: Path):
    return [(p.relative_to(classes_dir).as_posix(), p.read_bytes())
            for p in sorted(classes_dir.rglob("*.class"))]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--ide-packages", type=int, default=32)
    parser.add_argument("--ide-classes-per-package", type=int, default=50)
    parser.add_argument("--extra-classes", type=int, default=60)
    parser.add_argument("--plugin-sizes", default="250,500,750,1000,1400,2100",
                        help="comma-separated class counts, one plugin per entry")
    args = parser.parse_args()

    out = Path(args.out)
    if out.exists():
        shutil.rmtree(out)
    build = out / "build"
    rng = random.Random(args.seed)

    print("[gen] generating IDE API sources")
    ide_src = build / "ide-src"
    gen_ide_sources(ide_src, args.ide_packages, args.ide_classes_per_package, rng)
    gen_extra_sources(build / "extra-src", args.extra_classes)

    print("[gen] compiling IDE API")
    javac(ide_src, build / "ide-classes")
    javac(build / "extra-src", build / "extra-classes", classpath=build / "ide-classes")

    print("[gen] packaging IDE")
    ide_dir = out / "ide"
    (ide_dir).mkdir(parents=True)
    (ide_dir / "build.txt").write_text(IDE_BUILD, encoding="utf-8")
    core_entries = classes_entries(build / "ide-classes")
    core_entries.append(("META-INF/plugin.xml", CORE_PLUGIN_XML.encode()))
    write_zip(ide_dir / "lib" / "idea-core.jar", core_entries)

    plugin_sizes = [int(s) for s in args.plugin_sizes.split(",") if s]
    compile_cp = f"{build / 'ide-classes'}:{build / 'extra-classes'}"
    plugin_paths = []
    for k, size in enumerate(plugin_sizes, start=1):
        print(f"[gen] plugin{k}: {size} classes")
        src = build / f"plugin{k}-src"
        gen_plugin_sources(src, k, size, args.ide_packages,
                           args.ide_classes_per_package, args.extra_classes, rng)
        classes = build / f"plugin{k}-classes"
        javac(src, classes, classpath=compile_cp)
        jar_entries = classes_entries(classes)
        jar_entries.append(("META-INF/plugin.xml", PLUGIN_XML_TEMPLATE.format(k=k).encode()))
        jar_entries.append((f"messages/plugin{k}.properties", f"name=plugin{k}\n".encode()))
        inner_jar = build / f"plugin{k}.jar"
        write_zip(inner_jar, jar_entries)
        plugin_zip = out / "plugins" / f"plugin{k}.zip"
        write_zip(plugin_zip, [(f"plugin{k}/lib/plugin{k}.jar", inner_jar.read_bytes())])
        plugin_paths.append(str(plugin_zip.resolve()))

    (out / "plugins.txt").write_text("\n".join(plugin_paths) + "\n", encoding="utf-8")
    shutil.rmtree(build)
    total = sum(plugin_sizes)
    print(f"[gen] done: IDE {args.ide_packages * args.ide_classes_per_package} classes, "
          f"{len(plugin_sizes)} plugins with {total} classes -> {out}")


if __name__ == "__main__":
    main()
