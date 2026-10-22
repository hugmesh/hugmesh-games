#!/usr/bin/env python3
"""Version guard for hugmesh-games.

The repo builds five images (backend, frontend, rpg-builder, vn-builder,
wasm-fragment) that all carry ONE version, kept in the top-level VERSION file.
That version is also written into three poms, the frontend package.json and its
lock, the jar names the three Java Dockerfiles copy, and the Makefile's backend
jar path. This script fails if any of them disagree with VERSION.

With --ref (the release workflow passes $GITHUB_REF) it also decides what kind of
run this is:

  refs/tags/v<version>  kind=release  (the tag must equal "v" + VERSION)
  refs/heads/main       kind=main
  anything else         refused

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
            f"VERSION is {version!r}: expected MAJOR.MINOR.PATCH or MAJOR.MINOR.PATCH-beta.N"
        )
    return version


def pom_coordinates(root, rel):
    # The backend pom's site descriptor uses HTML entities (&middot;) that Maven
    # tolerates but a strict XML parser does not; drop named entities other than
    # the five XML ones before parsing. Only <project> coordinates are read.
    text = re.sub(r"&(?!(?:amp|lt|gt|quot|apos);|#)[A-Za-z][A-Za-z0-9]*;", "", _read(root, rel))
    try:
        tree = ET.fromstring(text)
    except ET.ParseError as exc:
        raise VersionError(f"{rel}: not valid XML ({exc})") from exc
    artifact = tree.find(f"{MAVEN_NS}artifactId")
    version = tree.find(f"{MAVEN_NS}version")
    if artifact is None or version is None:
        raise VersionError(f"{rel}: no <project><artifactId>/<version>")
    return artifact.text.strip(), version.text.strip()


def collect_problems(root):
    """Return (version, [problem, ...]). Raises VersionError if VERSION itself is bad."""
    version = read_version(root)
    problems = []

    for pom in POMS:
        try:
            artifact, pom_version = pom_coordinates(root, pom)
        except VersionError as exc:
            problems.append(str(exc))
            continue
        if pom_version != version:
            problems.append(f"{pom}: <version> is {pom_version}, VERSION is {version}")
        dockerfile = JAVA_DOCKERFILES[pom]
        try:
            text = _read(root, dockerfile)
        except VersionError as exc:
            problems.append(str(exc))
            continue
        want = f"/app/target/{artifact}-{version}.jar"
        jar_refs = re.findall(rf"target/{re.escape(artifact)}-[^\s/]+\.jar", text)
        if not jar_refs:
            problems.append(f"{dockerfile}: copies no target/{artifact}-<version>.jar")
        for ref in jar_refs:
            if f"/app/{ref}" != want:
                problems.append(f"{dockerfile}: copies {ref}, expected {want.removeprefix('/app/')}")

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

    try:
        makefile = _read(root, "Makefile")
        line = next((l for l in makefile.splitlines() if re.match(r"^BACKEND_JAR\s*=", l)), None)
        if line is None:
            problems.append("Makefile: no BACKEND_JAR line")
        elif not line.rstrip().endswith(f"/hugmesh-gaming-bff-{version}.jar"):
            problems.append(f"Makefile: {line.strip()!r} does not name hugmesh-gaming-bff-{version}.jar")
    except VersionError as exc:
        problems.append(str(exc))

    return version, problems


def classify_ref(ref, version):
    """Return 'release' or 'main' for a ref the release workflow may publish from."""
    if ref == "refs/heads/main":
        return "main"
    if ref.startswith("refs/tags/v"):
        tag_version = ref[len("refs/tags/v"):]
        if tag_version != version:
            raise VersionError(
                f"tag v{tag_version} does not match VERSION {version}: "
                f"bump VERSION (and run this script) before tagging, or tag v{version}"
            )
        return "release"
    raise VersionError(
        f"ref {ref!r} cannot publish: only refs/heads/main and refs/tags/v<VERSION> can"
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
    print(f"version {version} consistent across VERSION, 3 poms, 3 Dockerfiles, frontend package.json + lock, Makefile")
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
