"""Tests for scripts/db-passwords.sh and the no-literal-DB-password rule.

Run: python3 -m unittest scripts/test_db_passwords.py   (from the repo root)

Two halves:

* The resolver (db_passwords_ensure) is driven through bash against a temp
  env file: it must generate, persist, reuse, defer to an exported value, keep
  unrelated .env lines, and refuse an unreadable file rather than regenerate.
* The committed config must not carry a DB password literal again: every
  compose password key is a REQUIRED interpolation (`${VAR:?...}`), and the
  builders' Spring config has an EMPTY DB_PASS default. compose.prod.yml and
  compose.staging.yml inherit the base file, so a literal there is a
  production default.

Generated values are never printed by these tests.
"""

import os
import re
import stat
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
LIB = os.path.join(HERE, "db-passwords.sh")
VARS = ("GAMES_DB_PASS", "RPG_BUILDER_DB_PASS", "VN_BUILDER_DB_PASS")
HEX48 = re.compile(r"^[0-9a-f]{48}$")


def _clean_env(**extra):
    env = {k: v for k, v in os.environ.items() if k not in VARS}
    env.pop("DB_PASSWORDS_FILE", None)
    env.update(extra)
    return env


def run_ensure(env_file, **exported):
    """Source the lib, run db_passwords_ensure, return (rc, {var: value})."""
    script = (
        '. "$1" && db_passwords_ensure || exit $?\n'
        'for v in GAMES_DB_PASS RPG_BUILDER_DB_PASS VN_BUILDER_DB_PASS; do\n'
        '  # printenv shows only EXPORTED vars: what a child process (compose) sees\n'
        '  printf "%s=%s\\n" "$v" "$(printenv "$v")"\n'
        "done\n"
    )
    proc = subprocess.run(
        ["bash", "-c", script, "_", LIB],
        env=_clean_env(DB_PASSWORDS_FILE=env_file, **exported),
        capture_output=True,
        text=True,
    )
    values = {}
    for line in proc.stdout.splitlines():
        key, _, val = line.partition("=")
        values[key] = val
    run_ensure.stderr = proc.stderr
    return proc.returncode, values


def read_env(path):
    out = {}
    with open(path) as fh:
        for line in fh:
            key, sep, val = line.rstrip("\n").partition("=")
            if sep:
                out[key] = val
    return out


class ResolverTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.env_file = os.path.join(self.tmp.name, ".env")

    def tearDown(self):
        self.tmp.cleanup()

    def test_generates_persists_and_exports_on_fresh_checkout(self):
        rc, values = run_ensure(self.env_file)
        self.assertEqual(rc, 0)
        for var in VARS:
            self.assertRegex(values[var], HEX48, f"{var} not a 48-hex value")
        self.assertEqual(len({values[v] for v in VARS}), 3, "roles share a password")
        self.assertEqual({k: read_env(self.env_file)[k] for k in VARS}, values)
        mode = stat.S_IMODE(os.stat(self.env_file).st_mode)
        self.assertEqual(mode & 0o077, 0, f"new .env is group/world readable ({oct(mode)})")

    def test_second_run_reuses_the_persisted_values(self):
        _, first = run_ensure(self.env_file)
        rc, second = run_ensure(self.env_file)
        self.assertEqual(rc, 0)
        self.assertEqual(first, second)

    def test_exported_value_wins_and_is_not_overwritten(self):
        _, first = run_ensure(self.env_file)
        pinned = "operator-pinned-value"
        rc, values = run_ensure(self.env_file, GAMES_DB_PASS=pinned)
        self.assertEqual(rc, 0)
        self.assertEqual(values["GAMES_DB_PASS"], pinned)
        self.assertEqual(read_env(self.env_file)["GAMES_DB_PASS"], first["GAMES_DB_PASS"])

    def test_unrelated_env_lines_survive(self):
        with open(self.env_file, "w") as fh:
            fh.write("CASDOOR_CLIENT_ID=twclient001\nCASDOOR_ADMIN_PASS=keepme\n")
        rc, _ = run_ensure(self.env_file)
        self.assertEqual(rc, 0)
        env = read_env(self.env_file)
        self.assertEqual(env["CASDOOR_CLIENT_ID"], "twclient001")
        self.assertEqual(env["CASDOOR_ADMIN_PASS"], "keepme")
        for var in VARS:
            self.assertRegex(env[var], HEX48)

    def test_partial_file_keeps_existing_and_fills_the_rest(self):
        with open(self.env_file, "w") as fh:
            fh.write("RPG_BUILDER_DB_PASS=already-here\n")
        rc, values = run_ensure(self.env_file)
        self.assertEqual(rc, 0)
        self.assertEqual(values["RPG_BUILDER_DB_PASS"], "already-here")
        self.assertRegex(values["GAMES_DB_PASS"], HEX48)
        self.assertRegex(values["VN_BUILDER_DB_PASS"], HEX48)

    @unittest.skipIf(os.geteuid() == 0, "root can read a 000 file")
    def test_unreadable_env_file_is_an_error_not_a_regenerate(self):
        with open(self.env_file, "w") as fh:
            fh.write("GAMES_DB_PASS=existing\n")
        os.chmod(self.env_file, 0)
        try:
            rc, values = run_ensure(self.env_file)
        finally:
            os.chmod(self.env_file, 0o600)
        self.assertNotEqual(rc, 0)
        self.assertIn("not readable", run_ensure.stderr)
        self.assertEqual(read_env(self.env_file), {"GAMES_DB_PASS": "existing"})

    def test_a_looser_existing_env_file_is_tightened(self):
        with open(self.env_file, "w") as fh:
            fh.write("CASDOOR_CLIENT_ID=twclient001\n")
        os.chmod(self.env_file, 0o644)
        rc, _ = run_ensure(self.env_file)
        self.assertEqual(rc, 0)
        mode = stat.S_IMODE(os.stat(self.env_file).st_mode)
        self.assertEqual(mode, 0o600, oct(mode))


class MakeGateTest(unittest.TestCase):
    def test_make_db_env_writes_the_file_and_never_echoes_a_value(self):
        with tempfile.TemporaryDirectory() as tmp:
            env_file = os.path.join(tmp, ".env")
            proc = subprocess.run(
                ["make", "-s", "-C", REPO, "db-env"],
                env=_clean_env(DB_PASSWORDS_FILE=env_file),
                capture_output=True,
                text=True,
            )
            self.assertEqual(proc.returncode, 0, proc.stderr)
            env = read_env(env_file)
            for var in VARS:
                self.assertRegex(env[var], HEX48)
                self.assertNotIn(env[var], proc.stdout + proc.stderr)


# ── Committed-config guard ──────────────────────────────────────────────────
PASSWORD_KEYS = (
    "POSTGRES_PASSWORD",
    "DB_PASS",
    "LIQUIBASE_COMMAND_PASSWORD",
    "APP_R2DBC_REPLICA_PASS",
)
REQUIRED = re.compile(r"^\$\{(GAMES|RPG_BUILDER|VN_BUILDER)_DB_PASS:\?[^}]+\}$")
COMPOSE_FILES = (
    "docker-compose.yml",
    "docker-compose.native.yml",
    "docker-compose.replica.yml",
    "docker-compose.e2e.yml",
    "docker-compose.godot.yml",
    "docker-compose.tls.yml",
    "compose.prod.yml",
    "compose.staging.yml",
)


def compose_password_lines(text):
    pat = re.compile(r"^\s*(%s)\s*:\s*(.+?)\s*$" % "|".join(PASSWORD_KEYS))
    for lineno, line in enumerate(text.splitlines(), 1):
        if line.lstrip().startswith("#"):
            continue
        m = pat.match(line)
        if m:
            yield lineno, m.group(1), m.group(2).strip("'\"")


def check_compose(text):
    bad = []
    for lineno, key, value in compose_password_lines(text):
        if not REQUIRED.match(value):
            bad.append(f"line {lineno}: {key} is not a required ${{..._DB_PASS:?}}")
    for lineno, line in enumerate(text.splitlines(), 1):
        if re.match(r"^\s*dataSourceName\s*:", line) and not re.search(
            r"password=\$\{GAMES_DB_PASS:\?", line
        ):
            bad.append(f"line {lineno}: Casdoor dataSourceName password is not GAMES_DB_PASS:?")
    return bad


def check_spring(text):
    bad = []
    for lineno, line in enumerate(text.splitlines(), 1):
        m = re.search(r"\$\{DB_PASS:([^}]*)\}", line)
        if m and m.group(1) != "":
            bad.append(f"line {lineno}: DB_PASS has a non-empty default")
    return bad


class CommittedConfigTest(unittest.TestCase):
    def test_compose_files_require_every_db_password(self):
        seen = 0
        for rel in COMPOSE_FILES:
            with open(os.path.join(REPO, rel)) as fh:
                text = fh.read()
            seen += len(list(compose_password_lines(text)))
            self.assertEqual(check_compose(text), [], rel)
        # 3 postgres + 3 services + native liquibase + replica (2 + 1)
        self.assertGreaterEqual(seen, 10, "guard matched too few lines to mean anything")

    def test_spring_configs_have_no_db_pass_default(self):
        for rel in (
            "backend/src/main/resources/application.yml",
            "rpg-builder/src/main/resources/application.yml",
            "vn-builder/src/main/resources/application.yml",
        ):
            with open(os.path.join(REPO, rel)) as fh:
                self.assertEqual(check_spring(fh.read()), [], rel)

    def test_casdoor_app_conf_carries_no_password(self):
        with open(os.path.join(REPO, "casdoor/conf/app.conf")) as fh:
            for line in fh:
                if line.startswith("dataSourceName"):
                    self.assertNotIn("password=", line)

    def test_migration_script_has_no_literal_pgpassword_default(self):
        with open(os.path.join(REPO, "scripts/migrate-uploads-to-s3.sh")) as fh:
            text = fh.read()
        self.assertEqual(re.findall(r"PGPASSWORD:[=-](?!\$\{)[^}]+\}", text), [])
        self.assertIn('if [ -z "${PGPASSWORD}" ]', text)

    # The guard must be able to fail: feed it the shapes it exists to catch.
    def test_guard_rejects_literals_and_soft_defaults(self):
        for value in ("hunter2", '"hunter2"', "${GAMES_DB_PASS:-hunter2}",
                      "${GAMES_DB_PASS}", "${GAMES_DB_PASS:-}"):
            self.assertTrue(check_compose(f"      DB_PASS: {value}\n"), value)
        self.assertTrue(check_compose(
            '      dataSourceName: "user=u password=hunter2 host=postgres"\n'))
        self.assertTrue(check_spring("    password: ${DB_PASS:hunter2}\n"))
        self.assertEqual(check_spring("    password: ${DB_PASS:}\n"), [])


if __name__ == "__main__":
    unittest.main()
