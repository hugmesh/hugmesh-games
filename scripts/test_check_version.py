"""Tests for scripts/check_version.py.

Run: python3 -m unittest scripts/test_check_version.py   (from the repo root)

Every mutation test copies the REAL version-bearing files into a temp tree,
breaks exactly one of them, and expects the guard to fail. The first test checks
the real tree, so a bump that misses a file goes red here too.
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

FILES = (
    "VERSION",
    "Makefile",
    "backend/pom.xml",
    "backend/Dockerfile",
    "rpg-builder/pom.xml",
    "rpg-builder/Dockerfile",
    "vn-builder/pom.xml",
    "vn-builder/Dockerfile",
    "frontend/package.json",
    "frontend/package-lock.json",
)


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

    def test_real_version_is_a_beta_not_a_snapshot(self):
        version = check_version.read_version(REPO)
        self.assertRegex(version, r"^\d+\.\d+\.\d+-beta\.[1-9]\d*$")


class MutationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="games-version-")
        for rel in FILES:
            dst = os.path.join(self.tmp, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(os.path.join(REPO, rel), dst)
        self.version = check_version.read_version(self.tmp)

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
                self.edit(pom, f"<version>{self.version}</version>", "<version>0.1.0-SNAPSHOT</version>")
                self.assert_fails_naming(pom)

    def test_each_dockerfile_jar_name_is_checked(self):
        for pom, dockerfile in check_version.JAVA_DOCKERFILES.items():
            with self.subTest(dockerfile=dockerfile):
                self.tearDown()
                self.setUp()
                self.edit(dockerfile, f"-{self.version}.jar", "-0.1.0-SNAPSHOT.jar")
                self.assert_fails_naming(dockerfile)

    def test_dockerfile_without_jar_copy_fails(self):
        artifact, _ = check_version.pom_coordinates(self.tmp, "backend/pom.xml")
        self.edit("backend/Dockerfile", f"/app/target/{artifact}-{self.version}.jar", "/app/target/app.jar")
        self.assert_fails_naming("backend/Dockerfile")

    def test_package_json_is_checked(self):
        self.edit("frontend/package.json", f'"version": "{self.version}"', '"version": "0.1.0"')
        self.assert_fails_naming("frontend/package.json")

    def test_lock_top_version_is_checked(self):
        self.edit("frontend/package-lock.json", f'"version": "{self.version}"', '"version": "0.1.0"', 1)
        self.assert_fails_naming("frontend/package-lock.json")

    def test_lock_root_package_version_is_checked(self):
        path = os.path.join(self.tmp, "frontend/package-lock.json")
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        first = text.index(f'"version": "{self.version}"')
        second = text.index(f'"version": "{self.version}"', first + 1)
        text = text[:second] + '"version": "0.1.0"' + text[second + len(f'"version": "{self.version}"'):]
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text)
        code, _, err = run_guard("--root", self.tmp)
        self.assertEqual(code, 1)
        self.assertIn('packages[""].version', err)

    def test_makefile_backend_jar_is_checked(self):
        self.edit("Makefile", f"hugmesh-gaming-bff-{self.version}.jar", "hugmesh-gaming-bff-0.1.0-SNAPSHOT.jar")
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
        self.assertIn("prerelease=true\n", written)

    def test_mismatched_tag_refused(self):
        code, _, err = run_guard("--root", self.tmp, "--ref", "refs/tags/v9.9.9-beta.1")
        self.assertEqual(code, 1)
        self.assertIn("does not match VERSION", err)

    def test_main_is_a_main_build(self):
        out_file = os.path.join(self.tmp, "gh-output")
        code, _, err = run_guard("--root", self.tmp, "--ref", "refs/heads/main", env_output=out_file)
        self.assertEqual(code, 0, err)
        with open(out_file) as fh:
            self.assertIn("kind=main\n", fh.read())

    def test_other_refs_refused(self):
        for ref in ("refs/heads/feature-x", "refs/pull/7/merge", "refs/tags/studio-v0.2.0-beta.1",
                    f"refs/tags/{self.version}"):
            with self.subTest(ref=ref):
                code, _, err = run_guard("--root", self.tmp, "--ref", ref)
                self.assertEqual(code, 1, f"{ref} was accepted")
                self.assertIn("cannot publish", err)


class VersionPatternTest(unittest.TestCase):
    def test_accepts_and_rejects(self):
        good = ("0.2.0-beta.1", "0.2.0-beta.10", "1.0.0-rc.2", "0.2.0")
        bad = ("0.2.0-beta.0", "0.2.0-beta", "v0.2.0", "0.2", "0.2.0-SNAPSHOT", "0.2.0-beta.1+sha", "00.2.0")
        for v in good:
            self.assertRegex(v, check_version.VERSION_RE)
        for v in bad:
            self.assertIsNone(re.match(check_version.VERSION_RE, v), v)


if __name__ == "__main__":
    unittest.main()
