#!/usr/bin/env bash
# scripts/check-compose-credential-defaults.sh
#
# Guard: no credential literal, and no literal credential DEFAULT, in the
# compose files the production and staging overlays extend.
#
#   bash scripts/check-compose-credential-defaults.sh            # the real tree
#   bash scripts/check-compose-credential-defaults.sh FILE...    # these files
#
# Why: compose.prod.yml and compose.staging.yml are applied ON TOP of
# docker-compose.yml and override none of its credentials, so a
# `${VAR:-literal}` in the base file is the value a prod host starts with
# whenever the operator forgot to set VAR. trufflehog and gitleaks never
# match that shape (hugmesh-games#57). Postgres passwords got the same
# treatment in #49 (scripts/test_db_passwords.py); this guard covers every
# credential-looking name.
#
# Two rules, applied to every non-comment line (a full-line `#` comment is
# not reachable by compose; an inline ` # ...` tail is stripped first):
#
#   A. an interpolation default `${NAME:-value}` or `${NAME-value}` whose
#      NAME contains PASSWORD, PASSWD, _PASS, SECRET, TOKEN or _USER and
#      whose value is a non-empty literal is a failure. An EMPTY default
#      (`${NAME:-}`) is allowed: the Casdoor seeds use it to mean "let the
#      container generate a random one". A default that is itself an
#      interpolation (`${NAME:-${OTHER:?...}}`) is allowed: the value still
#      comes from the environment, never from this file.
#   B. an environment key ending in PASSWORD, PASSWD, _PASS, _SECRET or
#      _TOKEN whose value is not an interpolation at all (a bare literal,
#      quoted or not) is a failure. `_USER` is deliberately NOT in rule B:
#      `POSTGRES_USER: threewizards` is a role name, not a secret; rule A
#      still catches `${MINIO_ROOT_USER:-literal}`.
#
# A violation is reported as `file:line: NAME ...` and NEVER with the
# value, so a red CI log cannot leak the literal it just found. A scanned
# file that is missing is a failure too (fail closed). NUL bytes are
# stripped before matching: one 0x00 makes grep skip a whole file and
# report "clean".
#
# docker-compose.e2e.yml is not scanned: it is not on the prod/staging path
# and its `E2E_PLAYER_USERNAME: ${E2E_PLAYER_USERNAME:-player}` seed-account
# name would trip rule A. Its KEYSTORE_PASSWORD is `${KEYSTORE_PASSWORD:?}`,
# resolved per run by tests/stack/e2e-password.sh; the Makefile gate and
# scripts/gen-certs.sh enforce that.
#
# Exit: 0 clean, 1 violation(s), 2 missing file / usage error.
# Self-test + mutation battery: scripts/test-check-compose-credential-defaults.sh

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The base file, the replica overlay (applied on top of it on a host that
# runs the compose replica), and the two overlays themselves.
PROD_PATH_FILES=(
  docker-compose.yml
  docker-compose.replica.yml
  compose.prod.yml
  compose.staging.yml
)

if [ "$#" -gt 0 ]; then
  files=("$@")
else
  files=()
  for f in "${PROD_PATH_FILES[@]}"; do
    files+=("$root/$f")
  done
fi

NAME_RE='(PASSWORD|PASSWD|_PASS|SECRET|TOKEN|_USER)'
KEY_RE='(PASSWORD|PASSWD|_PASS|_SECRET|_TOKEN)$'
# ${NAME:-default} and ${NAME-default}; the default stops at the first `}`,
# so a nested `${NAME:-${OTHER}}` yields the default `${OTHER` — which
# starts with `$` and is therefore not a literal.
DEFAULT_RE='\$\{[A-Za-z_][A-Za-z0-9_]*:?-[^}]*\}'

violations=0
scanned=0
missing=0

report() { # report FILE LINENO MESSAGE  (never a value)
  printf '%s:%s: %s\n' "$1" "$2" "$3" >&2
  violations=$((violations + 1))
}

scan_file() {
  local file="$1"
  local lineno=0
  local line key value match name default
  # tr strips NUL so no line is silently skipped; the loop reads from the
  # cleaned copy, never from a pipeline whose status could be masked.
  local cleaned
  cleaned="$(mktemp "${TMPDIR:-/tmp}/compose-cred-guard.XXXXXX")"
  tr -d '\000' < "$file" > "$cleaned"
  while IFS= read -r line || [ -n "$line" ]; do
    lineno=$((lineno + 1))
    # Full-line comment: not reachable by compose.
    if printf '%s' "$line" | grep -E '^[[:space:]]*#' >/dev/null; then
      continue
    fi
    # Inline comment tail (YAML requires whitespace before an inline `#`).
    line="$(printf '%s' "$line" | sed -E 's/[[:space:]]#.*$//')"

    # Rule A: literal interpolation defaults on credential-looking names.
    # grep -o lists every match on the line; a line with none exits 1,
    # which `|| true` absorbs so set -e does not end the scan early.
    while IFS= read -r match; do
      [ -n "$match" ] || continue
      name="$(printf '%s' "$match" | sed -E 's/^\$\{([A-Za-z_][A-Za-z0-9_]*):?-.*$/\1/')"
      default="$(printf '%s' "$match" | sed -E 's/^\$\{[A-Za-z_][A-Za-z0-9_]*:?-(.*)\}$/\1/')"
      if printf '%s' "$name" | grep -E "$NAME_RE" >/dev/null; then
        if [ -n "$default" ] && [ "${default#\$}" = "$default" ]; then
          report "$file" "$lineno" "$name has a literal default (\${$name:-...}); use \${$name:?message} or an empty default"
        fi
      fi
    done < <(printf '%s\n' "$line" | grep -oE "$DEFAULT_RE" || true)

    # Rule B: a credential key whose value is a plain literal.
    #   KEY: value        (mapping form)
    #   - KEY=value       (list form)
    key=""
    value=""
    if printf '%s' "$line" | grep -E '^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*[[:space:]]*:[[:space:]]*[^[:space:]]' >/dev/null; then
      key="$(printf '%s' "$line" | sed -E 's/^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*:.*$/\1/')"
      value="$(printf '%s' "$line" | sed -E 's/^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*[[:space:]]*:[[:space:]]*//')"
    elif printf '%s' "$line" | grep -E '^[[:space:]]*-[[:space:]]*[A-Za-z_][A-Za-z0-9_]*=[^[:space:]]' >/dev/null; then
      key="$(printf '%s' "$line" | sed -E 's/^[[:space:]]*-[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)=.*$/\1/')"
      value="$(printf '%s' "$line" | sed -E 's/^[[:space:]]*-[[:space:]]*[A-Za-z_][A-Za-z0-9_]*=//')"
    fi
    if [ -n "$key" ] && printf '%s' "$key" | grep -E "$KEY_RE" >/dev/null; then
      # Strip one layer of quotes, then require an interpolation.
      value="$(printf '%s' "$value" | sed -E "s/^[\"'](.*)[\"'][[:space:]]*$/\1/")"
      case "$value" in
        '${'*) ;;
        *) report "$file" "$lineno" "$key is a plain literal; use \${$key:?message}" ;;
      esac
    fi
  done < "$cleaned"
  rm -f "$cleaned"
  scanned=$((scanned + 1))
}

for f in "${files[@]}"; do
  if [ ! -f "$f" ]; then
    printf '%s: missing (the guard scans it; a file that is not there is not clean)\n' "$f" >&2
    missing=$((missing + 1))
    continue
  fi
  scan_file "$f"
done

if [ "$missing" -gt 0 ]; then
  echo "compose credential guard: $missing scanned file(s) missing" >&2
  exit 2
fi
if [ "$violations" -gt 0 ]; then
  echo "compose credential guard: $violations credential literal(s) / literal default(s) in $scanned file(s)" >&2
  exit 1
fi
echo "compose credential guard: clean ($scanned file(s), no credential literal or literal default)"
