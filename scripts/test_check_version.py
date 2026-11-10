"""Tests for scripts/check_version.py.

Run: python3 -m unittest scripts/test_check_version.py   (from the repo root)

Every mutation test copies the REAL version-bearing files into a temp tree,
breaks exactly one of them, and expects the guard to fail. The first test checks
the real tree, so a bump that misses a file goes red here too.

No test names a version: each one derives what it needs from the VERSION file
(the tree's own version, the next minor/major/patch, a tag for either), so a
bump never edits this file.
"""

import contextlib
import io
import os
import re
import shutil
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import check_version  # noqa: E402

# The files a bump edits: VERSION, the three poms, frontend/package.json + lock.
VERSION_FILES = (
    "VERSION",
    "backend/pom.xml",
    "rpg-builder/pom.xml",
    "vn-builder/pom.xml",
    "frontend/package.json",
    "frontend/package-lock.json",
)
# Files the guard reads that a bump must NOT edit: they name version-free jars.
JAR_FILES = (
    "Makefile",
    "backend/Dockerfile",
    "rpg-builder/Dockerfile",
    "vn-builder/Dockerfile",
)
FILES = VERSION_FILES + JAR_FILES


def parts(version):
    major, minor, patch = (int(x) for x in version.split("-")[0].split("."))
    return major, minor, patch


def next_versions(version):
    """Versions a release from `version` can move to, derived, never literal:
    the next beta/rc of this line, its stable, a patch, a new minor and a new major."""
    major, minor, patch = parts(version)
    return (
        f"{major}.{minor}.{patch}",                # stable of this line
        f"{major}.{minor}.{patch}-rc.1",           # release candidate
        f"{major}.{minor}.{patch + 1}",            # patch
        f"{major}.{minor + 1}.0-beta.1",           # new minor (beta from main)
        f"{major}.{minor + 1}.0",                  # new minor (stable)
        f"{major + 1}.0.0-beta.1",                 # new major
    )


def other_version(version):
    """A valid version on a different MINOR line than `version`."""
    major, minor, _ = parts(version)
    return f"{major}.{minor + 1}.0-beta.1"


def run_guard(*argv, env_output=None):
    err, out = io.StringIO(), io.StringIO()
    old = os.environ.pop("GITHUB_OUTPUT", None)
    if env_output:
        os.environ["GITHUB_OUTPUT"] = env_output
    try:
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(out):
            code = check_version.main(list(argv))
    finally:
        os.environ.pop("GITHUB_OUTPUT", None)
        if old is not None:
            os.environ["GITHUB_OUTPUT"] = old
    return code, out.getvalue(), err.getvalue()


class RealTreeTest(unittest.TestCase):
    def test_real_tree_is_consistent(self):
        code, out, err = run_guard("--root", REPO)
        self.assertEqual(code, 0, err)

    def test_real_version_is_a_release_version_not_a_snapshot(self):
        # main carries X.Y.Z-beta.N; a release/X.Y branch carries X.Y.Z-rc.N or a
        # stable X.Y.Z.
        version = check_version.read_version(REPO)
        self.assertRegex(version, r"^\d+\.\d+\.\d+(-(beta|rc)\.[1-9]\d*)?$")

    def test_real_jar_names_carry_no_version(self):
        # The point of the version-free jar names: no file a bump does not edit
        # may carry the version, or the next bump needs a hand edit again.
        version = check_version.read_version(REPO)
        for rel in JAR_FILES:
            with self.subTest(file=rel), open(os.path.join(REPO, rel), encoding="utf-8") as fh:
                self.assertNotIn(version, fh.read())


class MutationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="games-version-")
        for rel in FILES:
            dst = os.path.join(self.tmp, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(os.path.join(REPO, rel), dst)
        self.version = check_version.read_version(self.tmp)
        self.other = other_version(self.version)

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def edit(self, rel, old, new, count=1):
        path = os.path.join(self.tmp, rel)
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        self.assertIn(old, text, f"fixture drift: {old!r} not in {rel}")
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text.replace(old, new, count))

    def assert_fails_naming(self, rel, *argv):
        code, out, err = run_guard("--root", self.tmp, *argv)
        self.assertEqual(code, 1, f"guard passed but {rel} was broken:\n{out}")
        self.assertIn(rel, err)

    def test_copy_is_consistent(self):
        code, _, err = run_guard("--root", self.tmp)
        self.assertEqual(code, 0, err)

    def test_each_pom_version_is_checked(self):
        for pom in check_version.POMS:
            with self.subTest(pom=pom):
                self.tearDown()
                self.setUp()
                self.edit(pom, f"<version>{self.version}</version>", f"<version>{self.other}</version>")
                self.assert_fails_naming(pom)

    def test_each_pom_final_name_is_checked(self):
        # A finalName that carries the version (the old default) or another name
        # than the artifactId, or no finalName at all, brings back the hand edit.
        for pom in check_version.POMS:
            artifact, _ = check_version.pom_coordinates(self.tmp, pom)
            for bad in (f"{artifact}-${{project.version}}", f"{artifact}-{self.version}", "app", None):
                with self.subTest(pom=pom, final_name=bad):
                    self.tearDown()
                    self.setUp()
                    new = "" if bad is None else f"<finalName>{bad}</finalName>"
                    self.edit(pom, f"<finalName>{artifact}</finalName>", new)
                    self.assert_fails_naming(pom)

    def test_each_dockerfile_jar_name_is_checked(self):
        for pom, dockerfile in check_version.JAVA_DOCKERFILES.items():
            artifact, _ = check_version.pom_coordinates(self.tmp, pom)
            for bad in (f"{artifact}-{self.version}.jar", f"{artifact}-{self.other}.jar", f"{artifact}-app.jar"):
                with self.subTest(dockerfile=dockerfile, jar=bad):
                    self.tearDown()
                    self.setUp()
                    self.edit(dockerfile, f"target/{artifact}.jar", f"target/{bad}")
                    self.assert_fails_naming(dockerfile)

    def test_dockerfile_without_jar_copy_fails(self):
        artifact, _ = check_version.pom_coordinates(self.tmp, "backend/pom.xml")
        self.edit("backend/Dockerfile", f"/app/target/{artifact}.jar", "/app/target/app.jar")
        self.assert_fails_naming("backend/Dockerfile")

    def test_package_json_is_checked(self):
        self.edit("frontend/package.json", f'"version": "{self.version}"', f'"version": "{self.other}"')
        self.assert_fails_naming("frontend/package.json")

    def test_lock_top_version_is_checked(self):
        self.edit("frontend/package-lock.json", f'"version": "{self.version}"', f'"version": "{self.other}"', 1)
        self.assert_fails_naming("frontend/package-lock.json")

    def test_lock_root_package_version_is_checked(self):
        path = os.path.join(self.tmp, "frontend/package-lock.json")
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        first = text.index(f'"version": "{self.version}"')
        second = text.index(f'"version": "{self.version}"', first + 1)
        text = text[:second] + f'"version": "{self.other}"' + text[second + len(f'"version": "{self.version}"'):]
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text)
        code, _, err = run_guard("--root", self.tmp)
        self.assertEqual(code, 1)
        self.assertIn('packages[""].version', err)

    def test_makefile_backend_jar_is_checked(self):
        artifact, _ = check_version.pom_coordinates(self.tmp, "backend/pom.xml")
        for bad in (f"{artifact}-{self.version}.jar", f"{artifact}-{self.other}.jar"):
            with self.subTest(jar=bad):
                self.tearDown()
                self.setUp()
                self.edit("Makefile", f"target/{artifact}.jar", f"target/{bad}")
                self.assert_fails_naming("Makefile")

    def test_snapshot_version_refused(self):
        with open(os.path.join(self.tmp, "VERSION"), "w") as fh:
            fh.write("0.2.0-SNAPSHOT\n")
        code, _, err = run_guard("--root", self.tmp)
        self.assertEqual(code, 1)
        self.assertIn("SNAPSHOT", err)

    def test_build_metadata_refused(self):
        with open(os.path.join(self.tmp, "VERSION"), "w") as fh:
            fh.write("0.2.0-beta.1+abc\n")
        code, _, err = run_guard("--root", self.tmp)
        self.assertEqual(code, 1)
        self.assertIn("VERSION", err)

    def test_matching_tag_is_a_release(self):
        out_file = os.path.join(self.tmp, "gh-output")
        code, _, err = run_guard("--root", self.tmp, "--ref", f"refs/tags/v{self.version}", env_output=out_file)
        self.assertEqual(code, 0, err)
        with open(out_file) as fh:
            written = fh.read()
        self.assertIn(f"version={self.version}\n", written)
        self.assertIn("kind=release\n", written)
        # main carries a beta (prerelease), a release/X.Y branch a stable: the
        # flag follows the VERSION this tree carries, so the self-test passes on both.
        expected = "true" if "-" in self.version else "false"
        self.assertIn(f"prerelease={expected}\n", written)

    def rebump(self, new):
        # Simulate a version bump in the temp tree: ONLY the files a bump edits
        # move from the real VERSION to `new`. The Dockerfiles and the Makefile are
        # left alone, so the guard going green proves they need no hand edit.
        for rel in VERSION_FILES:
            path = os.path.join(self.tmp, rel)
            with open(path, encoding="utf-8") as fh:
                text = fh.read()
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(text.replace(self.version, new))

    def test_any_next_version_needs_only_the_version_files(self):
        # Stable, rc, patch, new minor (beta and stable) and new major, all derived
        # from VERSION: bumping the six version files is the whole bump.
        for new in next_versions(self.version):
            with self.subTest(version=new):
                self.tearDown()
                self.setUp()
                self.rebump(new)
                code, out, err = run_guard("--root", self.tmp)
                self.assertEqual(code, 0, err)
                self.assertIn(f"version {new} consistent", out)
                line = ".".join(new.split(".")[:2])
                self.assertEqual(check_version.classify_ref(f"refs/heads/release/{line}", new), "branch")

    def test_a_bump_that_misses_a_version_file_fails(self):
        # The converse: leave any one of the five version-bearing files behind and
        # the bumped tree goes red, naming that file.
        new = other_version(self.version)
        for rel in VERSION_FILES[1:]:
            with self.subTest(left_behind=rel):
                self.tearDown()
                self.setUp()
                saved = os.path.join(self.tmp, rel + ".orig")
                shutil.copyfile(os.path.join(self.tmp, rel), saved)
                self.rebump(new)
                shutil.move(saved, os.path.join(self.tmp, rel))
                self.assert_fails_naming(rel)

    def test_prerelease_follows_the_version_on_both_channels(self):
        # Whatever the real tree carries, every channel is exercised: a stable
        # X.Y.Z reports prerelease=false, an X.Y.Z-beta.N or -rc.N prerelease=true.
        base = self.version.split("-")[0]
        for new, expected in ((base, "false"), (f"{base}-beta.1", "true"), (f"{base}-rc.1", "true")):
            with self.subTest(version=new):
                self.tearDown()
                self.setUp()
                self.rebump(new)
                out_file = os.path.join(self.tmp, "gh-output")
                code, _, err = run_guard("--root", self.tmp, "--ref", f"refs/tags/v{new}", env_output=out_file)
                self.assertEqual(code, 0, err)
                with open(out_file) as fh:
                    written = fh.read()
                self.assertIn(f"version={new}\n", written)
                self.assertIn("kind=release\n", written)
                self.assertIn(f"prerelease={expected}\n", written)

    def test_mismatched_tag_refused(self):
        code, _, err = run_guard("--root", self.tmp, "--ref", f"refs/tags/v{self.other}")
        self.assertEqual(code, 1)
        self.assertIn("does not match VERSION", err)

    def test_main_is_a_main_build(self):
        out_file = os.path.join(self.tmp, "gh-output")
        code, _, err = run_guard("--root", self.tmp, "--ref", "refs/heads/main", env_output=out_file)
        self.assertEqual(code, 0, err)
        with open(out_file) as fh:
            self.assertIn("kind=main\n", fh.read())

    def test_release_branch_of_the_version_line_is_a_branch_build(self):
        line = ".".join(self.version.split(".")[:2])
        out_file = os.path.join(self.tmp, "gh-output")
        code, _, err = run_guard("--root", self.tmp, "--ref", f"refs/heads/release/{line}", env_output=out_file)
        self.assertEqual(code, 0, err)
        with open(out_file) as fh:
            self.assertIn("kind=branch\n", fh.read())

    def test_release_branch_of_another_line_refused(self):
        other_line = ".".join(self.other.split(".")[:2])
        code, _, err = run_guard("--root", self.tmp, "--ref", f"refs/heads/release/{other_line}")
        self.assertEqual(code, 1)
        self.assertIn("must carry an X.Y.* version", err)

    def test_line_prefix_is_not_a_string_prefix(self):
        # release/0.2 must not accept VERSION 0.20.0 (or 10.2.0).
        for version, ref in (("0.20.0", "refs/heads/release/0.2"), ("10.2.0", "refs/heads/release/0.2")):
            with self.subTest(version=version):
                with self.assertRaises(check_version.VersionError):
                    check_version.classify_ref(ref, version)
        self.assertEqual(check_version.classify_ref("refs/heads/release/0.2", "0.2.1"), "branch")
        self.assertEqual(check_version.classify_ref("refs/heads/release/0.10", "0.10.0"), "branch")

    def test_other_refs_refused(self):
        for ref in ("refs/heads/feature-x", "refs/pull/7/merge", "refs/tags/studio-v0.2.0-beta.1",
                    f"refs/tags/{self.version}", "refs/heads/release/attest-something",
                    "refs/heads/release/0", "refs/heads/release/0.2.0", "refs/heads/release/v0.2",
                    "refs/heads/release/00.2", "refs/heads/release/0.2/x", "refs/heads/xrelease/0.2"):
            with self.subTest(ref=ref):
                code, _, err = run_guard("--root", self.tmp, "--ref", ref)
                self.assertEqual(code, 1, f"{ref} was accepted")
                self.assertIn("cannot publish", err)


class VersionPatternTest(unittest.TestCase):
    def test_accepts_and_rejects(self):
        good = ("0.2.0-beta.1", "0.2.0-beta.10", "1.0.0-rc.2", "0.2.0", "0.10.0-beta.1", "10.0.0", "1.2.3-rc.10")
        bad = ("0.2.0-beta.0", "0.2.0-beta", "v0.2.0", "0.2", "0.2.0-SNAPSHOT", "0.2.0-beta.1+sha", "00.2.0")
        for v in good:
            self.assertRegex(v, check_version.VERSION_RE)
        for v in bad:
            self.assertIsNone(re.match(check_version.VERSION_RE, v), v)


if __name__ == "__main__":
    unittest.main()
