#!/usr/bin/env python3
"""Version guard for hugmesh-games.

The repo builds five images (backend, frontend, rpg-builder, vn-builder,
wasm-fragment) that all carry ONE version, kept in the top-level VERSION file.
That version is also written into the three poms and the frontend package.json
and its lock, and nowhere else: a bump touches exactly those six files. This
script fails if any of them disagree with VERSION.

The jar names are version-free on purpose (each pom sets <build><finalName> to
its artifactId), so the three Java Dockerfiles and the Makefile's BACKEND_JAR
never change on a bump. The script also fails if a pom's finalName is not its
artifactId, or if a Dockerfile or BACKEND_JAR copies any other jar name: that
mismatch breaks the image build, and before this it was the step every bump
had to edit by hand.

VERSION may be any MAJOR.MINOR.PATCH, MAJOR.MINOR.PATCH-beta.N or
MAJOR.MINOR.PATCH-rc.N (a new major, minor or patch line needs no edit here).

With --ref (the release workflow passes $GITHUB_REF) it also decides what kind of
run this is:

  refs/tags/v<version>     kind=release  (the tag must equal "v" + VERSION)
  refs/heads/main          kind=main
  refs/heads/release/X.Y   kind=branch   (VERSION must be X.Y.*)
  anything else            refused

The release channel (beta from main, stable from release/X.Y), the branch
membership of a tag and the image tags are decided by scripts/release-channel.sh,
not here.

When GITHUB_OUTPUT is set it appends version=, kind= and prerelease= to it.

Usage:
  python3 scripts/check_version.py [--root DIR] [--ref REF]
"""

import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

# MAJOR.MINOR.PATCH, optionally -beta.N or -rc.N (N >= 1). No +build metadata:
# OCI tags reject "+". No SNAPSHOT: a published version is immutable.
VERSION_RE = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-(beta|rc)\.[1-9]\d*)?$")

POMS = ("backend/pom.xml", "rpg-builder/pom.xml", "vn-builder/pom.xml")
JAVA_DOCKERFILES = {
    "backend/pom.xml": "backend/Dockerfile",
    "rpg-builder/pom.xml": "rpg-builder/Dockerfile",
    "vn-builder/pom.xml": "vn-builder/Dockerfile",
}
MAVEN_NS = "{http://maven.apache.org/POM/4.0.0}"


class VersionError(Exception):
    pass


def _read(root, rel):
    path = os.path.join(root, rel)
    try:
        with open(path, encoding="utf-8") as fh:
            return fh.read()
    except OSError as exc:
        raise VersionError(f"{rel}: cannot read ({exc.strerror})") from exc


def read_version(root):
    raw = _read(root, "VERSION")
    version = raw.strip()
    if "SNAPSHOT" in version.upper():
        raise VersionError(f"VERSION is {version!r}: a published version never carries SNAPSHOT")
    if not VERSION_RE.match(version):
        raise VersionError(
            f"VERSION is {version!r}: expected MAJOR.MINOR.PATCH, MAJOR.MINOR.PATCH-beta.N "
            f"or MAJOR.MINOR.PATCH-rc.N"
        )
    return version


def _pom_tree(root, rel):
    # The backend pom's site descriptor uses HTML entities (&middot;) that Maven
    # tolerates but a strict XML parser does not; drop named entities other than
    # the five XML ones before parsing.
    text = re.sub(r"&(?!(?:amp|lt|gt|quot|apos);|#)[A-Za-z][A-Za-z0-9]*;", "", _read(root, rel))
    try:
        return ET.fromstring(text)
    except ET.ParseError as exc:
        raise VersionError(f"{rel}: not valid XML ({exc})") from exc


def pom_coordinates(root, rel):
    """Return (artifactId, version) of the <project> itself (not its parent)."""
    tree = _pom_tree(root, rel)
    artifact = tree.find(f"{MAVEN_NS}artifactId")
    version = tree.find(f"{MAVEN_NS}version")
    if artifact is None or version is None:
        raise VersionError(f"{rel}: no <project><artifactId>/<version>")
    return artifact.text.strip(), version.text.strip()


def pom_final_name(root, rel):
    """Return <project><build><finalName> (profiles are not read), or None."""
    node = _pom_tree(root, rel).find(f"{MAVEN_NS}build/{MAVEN_NS}finalName")
    return node.text.strip() if node is not None and node.text else None


def check_jar_names(root):
    """The jar names must be version-free and agree; return [problem, ...]."""
    problems = []
    final_names = {}
    for pom, dockerfile in JAVA_DOCKERFILES.items():
        try:
            artifact, _ = pom_coordinates(root, pom)
            final = pom_final_name(root, pom)
        except VersionError as exc:
            problems.append(str(exc))
            continue
        if final != artifact:
            problems.append(
                f"{pom}: <build><finalName> is {final}, expected {artifact} "
                f"(a version-free jar name, so a bump never edits {dockerfile})"
            )
            continue
        final_names[pom] = final
        try:
            text = _read(root, dockerfile)
        except VersionError as exc:
            problems.append(str(exc))
            continue
        want = f"target/{final}.jar"
        jar_refs = re.findall(rf"target/{re.escape(artifact)}[^\s/]*\.jar", text)
        if not jar_refs:
            problems.append(f"{dockerfile}: copies no {want}")
        for ref in jar_refs:
            if ref != want:
                problems.append(f"{dockerfile}: copies {ref}, expected {want} (the pom's finalName)")

    backend = final_names.get("backend/pom.xml")
    if backend:
        try:
            makefile = _read(root, "Makefile")
            line = next((l for l in makefile.splitlines() if re.match(r"^BACKEND_JAR\s*=", l)), None)
            if line is None:
                problems.append("Makefile: no BACKEND_JAR line")
            elif not line.rstrip().endswith(f"/target/{backend}.jar"):
                problems.append(f"Makefile: {line.strip()!r} does not name target/{backend}.jar")
        except VersionError as exc:
            problems.append(str(exc))
    return problems


def collect_problems(root):
    """Return (version, [problem, ...]). Raises VersionError if VERSION itself is bad."""
    version = read_version(root)
    problems = []

    for pom in POMS:
        try:
            _, pom_version = pom_coordinates(root, pom)
        except VersionError as exc:
            problems.append(str(exc))
            continue
        if pom_version != version:
            problems.append(f"{pom}: <version> is {pom_version}, VERSION is {version}")

    try:
        pkg = json.loads(_read(root, "frontend/package.json"))
        if pkg.get("version") != version:
            problems.append(f"frontend/package.json: version is {pkg.get('version')}, VERSION is {version}")
    except (VersionError, ValueError) as exc:
        problems.append(f"frontend/package.json: {exc}")

    try:
        lock = json.loads(_read(root, "frontend/package-lock.json"))
        if lock.get("version") != version:
            problems.append(f"frontend/package-lock.json: version is {lock.get('version')}, VERSION is {version}")
        root_pkg = lock.get("packages", {}).get("", {})
        if root_pkg.get("version") != version:
            problems.append(
                f"frontend/package-lock.json: packages[\"\"].version is {root_pkg.get('version')}, VERSION is {version}"
            )
    except (VersionError, ValueError) as exc:
        problems.append(f"frontend/package-lock.json: {exc}")

    problems.extend(check_jar_names(root))
    return version, problems


RELEASE_BRANCH_RE = re.compile(r"^refs/heads/release/(0|[1-9]\d*)\.(0|[1-9]\d*)$")


def classify_ref(ref, version):
    """Return 'release', 'main' or 'branch' for a ref the release workflow may publish from."""
    if ref == "refs/heads/main":
        return "main"
    branch = RELEASE_BRANCH_RE.match(ref)
    if branch:
        line = f"{branch.group(1)}.{branch.group(2)}"
        if not version.startswith(line + "."):
            raise VersionError(
                f"{ref[len('refs/heads/'):]} carries VERSION {version}: a release/X.Y branch "
                f"must carry an X.Y.* version"
            )
        return "branch"
    if ref.startswith("refs/tags/v"):
        tag_version = ref[len("refs/tags/v"):]
        if tag_version != version:
            raise VersionError(
                f"tag v{tag_version} does not match VERSION {version}: "
                f"bump VERSION (and run this script) before tagging, or tag v{version}"
            )
        return "release"
    raise VersionError(
        f"ref {ref!r} cannot publish: only refs/heads/main, refs/heads/release/X.Y "
        f"and refs/tags/v<VERSION> can"
    )


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    parser.add_argument("--ref", help="git ref of the run, e.g. $GITHUB_REF")
    args = parser.parse_args(argv)
    root = os.path.abspath(args.root)

    try:
        version, problems = collect_problems(root)
    except VersionError as exc:
        print(f"version check FAILED: {exc}", file=sys.stderr)
        return 1
    if problems:
        print(f"version check FAILED: {len(problems)} file(s) disagree with VERSION {version}:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    kind = None
    if args.ref is not None:
        try:
            kind = classify_ref(args.ref, version)
        except VersionError as exc:
            print(f"version check FAILED: {exc}", file=sys.stderr)
            return 1

    prerelease = "true" if "-" in version else "false"
    print(f"version {version} consistent across VERSION, 3 poms, frontend package.json + lock; "
          f"jar names version-free in 3 Dockerfiles + Makefile")
    if kind:
        print(f"ref {args.ref}: kind={kind} prerelease={prerelease}")
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as fh:
            fh.write(f"version={version}\n")
            fh.write(f"prerelease={prerelease}\n")
            if kind:
                fh.write(f"kind={kind}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
