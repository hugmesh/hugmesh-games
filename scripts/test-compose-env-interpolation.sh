#!/usr/bin/env bash
# scripts/test-compose-env-interpolation.sh
#
# The pentest compose profile (tests/pentest/env/compose.env, sourced by
# `tests/pentest/run-pentest.sh --env compose` in the GitLab dast:baseline,
# dast:full and load:k6 jobs) runs `docker compose -f docker-compose.yml
# -f docker-compose.e2e.yml ...` DIRECTLY, not through make, so the Makefile
# gates that export the required credentials never run for it. compose
# interpolates every `${VAR:?}` on every subcommand (up, down, config), so a
# variable the profile does not resolve kills spin-up AND teardown before a
# container starts. That happened once per new required variable (#49, then
# KEYSTORE_PASSWORD in #57); this test makes the next one a named failure.
#
#   bash scripts/test-compose-env-interpolation.sh            # the real profile
#   bash scripts/test-compose-env-interpolation.sh PROFILE    # a profile copy
#
# What it does, offline and without touching the checkout:
#   1. sources PROFILE in a clean environment (env -i: nothing ambient can
#      mask a missing export) under `set -euo pipefail` like run-pentest.sh,
#      with the credential files the resolvers write redirected to a temp dir;
#   2. reads COMPOSE_FILES from the profile itself (so an overlay added there
#      is covered automatically) and lists every `${NAME:?...}` / `${NAME?...}`
#      in those files, comments skipped;
#   3. fails, naming the variable (never a value), for each one left empty;
#   4. when `docker compose` is on PATH, also runs `docker compose
#      <COMPOSE_FILES> config -q` in that environment with an EMPTY env-file
#      (a developer's gitignored .env must not stand in for an export the CI
#      runner will not have). No daemon is needed for `config`.
# With no argument it then mutates copies of the profile (each resolver call
# removed in turn) and requires each copy to go red, so this test cannot
# pass vacuously.
#
# Exit: 0 every required variable resolved, 1 otherwise.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"
profile="${1:-$root/tests/pentest/env/compose.env}"

work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/compose-env-interp.XXXXXX")"
trap 'rm -rf "$work"' EXIT

# check_profile PROFILE -> prints `missing NAME` lines / a compose verdict;
# rc 0 when every required variable resolved (and compose config passed).
check_profile() {
  local prof="$1"
  local run
  run="$(mktemp -d "$work/run.XXXXXX")"
  : > "$run/empty.env"
  # The child sources the profile exactly as run-pentest.sh does, then
  # reports only NAMES. Its stdout is the list of compose files followed by
  # a separator and the names of required variables that are empty.
  env -i \
    PATH="$PATH" HOME="${HOME:-$run}" TMPDIR="$run" \
    GAMES_DIR="$root" \
    E2E_CREDENTIALS_FILE="$run/e2e-credentials" \
    DB_PASSWORDS_FILE="$run/db.env" \
    PENTEST_ENV=compose PENTEST_SCAN=baseline PENTEST_PIPELINE=games \
    PENTEST_REPORTS="$run/reports" \
    CHECK_EMPTY_ENV="$run/empty.env" \
    CHECK_PROFILE="$prof" \
    bash -c '
      set -euo pipefail
      # shellcheck disable=SC1090
      . "$CHECK_PROFILE"
      req_re="[\$][{]([A-Za-z_][A-Za-z0-9_]*)[:]?[?]"
      read -r -a parts <<< "$COMPOSE_FILES"
      files=()
      for ((k = 0; k < ${#parts[@]}; k++)); do
        if [ "${parts[k]}" = "-f" ]; then
          files+=("${parts[k + 1]}")
        fi
      done
      if [ "${#files[@]}" -eq 0 ]; then
        echo "no compose files in COMPOSE_FILES"
        exit 3
      fi
      declare -A seen=()
      missing=0
      required=0
      for f in "${files[@]}"; do
        if [ ! -f "$f" ]; then
          echo "compose file missing: $f"
          exit 3
        fi
        while IFS= read -r line || [ -n "$line" ]; do
          [[ "$line" =~ ^[[:space:]]*# ]] && continue
          rest="$line"
          while [[ "$rest" =~ $req_re ]]; do
            name="${BASH_REMATCH[1]}"
            rest="${rest#*"${BASH_REMATCH[0]}"}"
            [ -n "${seen[$name]:-}" ] && continue
            seen[$name]=1
            required=$((required + 1))
            if [ -z "${!name:-}" ]; then
              echo "missing $name"
              missing=$((missing + 1))
            fi
          done
        done < <(tr -d "\000" < "$f")
      done
      if [ "$required" -eq 0 ]; then
        echo "found no required variables in: ${files[*]}"
        exit 3
      fi
      echo "required $required"
      if docker compose version >/dev/null 2>&1; then
        # shellcheck disable=SC2086
        if docker compose --env-file "$CHECK_EMPTY_ENV" $COMPOSE_FILES config -q >/dev/null 2>"$TMPDIR/compose.err"; then
          echo "compose config ok"
        else
          # compose names the variable in its error; it prints no value.
          echo "compose config failed: $(head -n 3 "$TMPDIR/compose.err" | tr "\n" " ")"
          missing=$((missing + 1))
        fi
      else
        echo "compose config not run (no docker compose on PATH)"
      fi
      [ "$missing" -eq 0 ]
    '
}

pass=0
fail=0
ok() { pass=$((pass + 1)); echo "ok   $*"; }
bad() { fail=$((fail + 1)); echo "FAIL $*"; }

# --- 1. the profile under test -------------------------------------------
set +e
out="$(check_profile "$profile" 2>&1)"
rc=$?
set -e
printf '%s\n' "$out" | sed 's/^/     /'
if [ "$rc" -eq 0 ]; then
  ok "profile resolves every required variable: ${profile#"$root"/}"
else
  bad "profile leaves required variable(s) unresolved (rc=$rc): ${profile#"$root"/}"
fi

# --- 2. mutations (only for the real profile) ----------------------------
# Remove ONE resolver call at a time; the copy must go red naming a
# variable that resolver owns. Without this, a parser that found no
# variables (or a check that always printed nothing) would pass.
if [ "$#" -eq 0 ]; then
  mutate_red() { # mutate_red RESOLVER_LINE EXPECTED_NAME
    local resolver="$1" want="$2"
    local copy="$work/compose.env.no-$resolver"
    sed "/^${resolver}\$/d" "$profile" > "$copy"
    if cmp -s "$copy" "$profile"; then
      bad "mutation did not change the profile: $resolver"
      return
    fi
    set +e
    out="$(check_profile "$copy" 2>&1)"
    rc=$?
    set -e
    if [ "$rc" -eq 0 ]; then
      bad "mutation: without $resolver the profile still passes"
    elif printf '%s\n' "$out" | grep -Fx "missing $want" >/dev/null; then
      ok "mutation: without $resolver the profile is red (missing $want)"
    else
      bad "mutation: without $resolver red for the wrong reason -- $out"
    fi
  }
  mutate_red e2e_keystore_password_ensure KEYSTORE_PASSWORD
  mutate_red e2e_password_ensure E2E_PASSWORD
  mutate_red db_passwords_ensure GAMES_DB_PASS
fi

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
