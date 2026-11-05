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
#   A. an interpolation default `${NAME:-value}` or `${NAME-value}` (or an
#      alternate `${NAME:+value}` / `${NAME+value}`) whose NAME contains
#      PASSWORD, PASSWD, _PASS, SECRET, TOKEN or _USER is a failure when the
#      value can render ANY literal text. An EMPTY default (`${NAME:-}`) is
#      allowed: the Casdoor seeds use it to mean "let the container generate
#      a random one". A default made only of interpolations that cannot
#      themselves fall back to a literal (`${NAME:-${OTHER:?...}}`,
#      `${NAME:-${OTHER}}`, `${NAME:-$OTHER}`) is allowed: the value still
#      comes from the environment, never from this file. Nesting is followed
#      to any depth, in both directions: `${NAME:-${OTHER:-literal}}` is red
#      for NAME (that literal is NAME's value when both are unset), and
#      `${ANY:-${OTHER_PASSWORD:-literal}}` is red for OTHER_PASSWORD
#      whatever ANY is called. A `$$`-escaped `$${NAME:-literal}` is scanned
#      too: compose passes it through and the container's shell applies the
#      same default.
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
# Rule A is a small recursive scanner, not one regex: a regex that stops at
# the first `}` sees `${NAME:-${OTHER:-literal}}` as the default `${OTHER:-literal`
# (starts with `$`, "not a literal") and never looks inside it — the
# fail-open this replaces. These patterns are its tokens.
LIT_RE='^[^$}]+'                                       # a run of plain text
OPEN_RE='^[$][{]([A-Za-z_][A-Za-z0-9_]*)(:?[-?+])?'     # ${NAME and its operator
VAR_RE='^[$][A-Za-z_][A-Za-z0-9_]*'                     # bare $NAME

violations=0
scanned=0
missing=0

report() { # report FILE LINENO MESSAGE  (never a value)
  printf '%s:%s: %s\n' "$1" "$2" "$3" >&2
  violations=$((violations + 1))
}

# _interp TEXT START DEPTH
#   Walks TEXT from index START. At DEPTH 0 it walks to the end; at DEPTH > 0
#   it stops at the `}` that closes the enclosing `${...`. Every
#   `${NAME<op>body}` met on the way is walked recursively, so nothing nested
#   is skipped, and reported (rule A) when NAME is credential-looking, <op>
#   is a default (`-`, `:-`) or an alternate (`+`, `:+`), and the body can
#   render literal text. A `${NAME:?message}` body is an error message, not a
#   value: it renders nothing (though interpolations inside it are still
#   walked). Results in globals (bash functions cannot return strings
#   cheaply): _end = index of the closing `}` (or the length, unterminated),
#   _lit = 1 when the walked span can render literal text.
#   Needs $file and $lineno from scan_file for the report.
_interp() {
  local s="$1"
  local i="$2"
  local depth="$3"
  local n="${#s}"
  local lit=0
  local tail name op body_lit
  while [ "$i" -lt "$n" ]; do
    tail="${s:i}"
    if [[ "$tail" =~ $LIT_RE ]]; then
      lit=1
      i=$((i + ${#BASH_REMATCH[0]}))
      continue
    fi
    case "$tail" in
      '}'*)
        if [ "$depth" -gt 0 ]; then
          break
        fi
        lit=1
        i=$((i + 1))
        ;;
      '$$'*)
        # Compose's escape for a literal `$`. Consume ONE `$` only, so a
        # following `${NAME:-x}` is walked like any other: the container's
        # shell applies that default at run time.
        lit=1
        i=$((i + 1))
        ;;
      '${'*)
        if [[ "$tail" =~ $OPEN_RE ]]; then
          name="${BASH_REMATCH[1]}"
          op="${BASH_REMATCH[2]}"
          i=$((i + ${#BASH_REMATCH[0]}))
          if [ -z "$op" ]; then
            if [ "${s:i:1}" = '}' ]; then
              i=$((i + 1))   # plain ${NAME}: the environment's value, no literal
              continue
            fi
            op='-'           # an operator compose does not know: fail closed, treat the rest as a default
          fi
          _interp "$s" "$i" "$((depth + 1))"
          body_lit="$_lit"
          i="$_end"
          if [ "$i" -lt "$n" ]; then
            i=$((i + 1))     # the closing }
          fi
          case "$op" in
            *'?') ;;         # required: the body is the error message
            *)
              if [ "$body_lit" -eq 1 ]; then
                lit=1
                if [[ "$name" =~ $NAME_RE ]]; then
                  report "$file" "$lineno" "$name has a literal default (\${$name:-...}); use \${$name:?message} or an empty default"
                fi
              fi
              ;;
          esac
        else
          lit=1              # `${` that is not an interpolation compose accepts
          i=$((i + 1))
        fi
        ;;
      '$'*)
        if [[ "$tail" =~ $VAR_RE ]]; then
          i=$((i + ${#BASH_REMATCH[0]}))   # bare $NAME: no literal
        else
          lit=1
          i=$((i + 1))
        fi
        ;;
    esac
  done
  _end="$i"
  _lit="$lit"
}

scan_file() {
  local file="$1"
  local lineno=0
  local line key value
  # tr strips NUL so no line is silently skipped; the loop reads from the
  # cleaned copy, never from a pipeline whose status could be masked. All
  # matching below is bash's own regex engine ([[ =~ ]]): no per-line
  # subprocesses, so the four files scan in well under a second.
  local cleaned
  cleaned="$(mktemp "${TMPDIR:-/tmp}/compose-cred-guard.XXXXXX")"
  tr -d '\000' < "$file" > "$cleaned"
  while IFS= read -r line || [ -n "$line" ]; do
    lineno=$((lineno + 1))
    # Full-line comment: not reachable by compose.
    if [[ "$line" =~ ^[[:space:]]*# ]]; then
      continue
    fi
    # Inline comment tail (YAML requires whitespace before an inline `#`).
    # Greedy (.*) stops at the LAST " #"; looping strips back to the first.
    while [[ "$line" =~ ^(.*)[[:space:]]#.*$ ]]; do
      line="${BASH_REMATCH[1]}"
    done

    # Rule A: literal interpolation defaults on credential-looking names,
    # at any nesting depth (see _interp). Lines with no `$` cannot carry one.
    case "$line" in
      *'$'*) _interp "$line" 0 0 ;;
    esac

    # Rule B: a credential key whose value is a plain literal.
    #   KEY: value        (mapping form)
    #   - KEY=value       (list form)
    key=""
    value=""
    if [[ "$line" =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)[[:space:]]*:[[:space:]]*([^[:space:]].*)$ ]]; then
      key="${BASH_REMATCH[1]}"
      value="${BASH_REMATCH[2]}"
    elif [[ "$line" =~ ^[[:space:]]*-[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)=([^[:space:]].*)$ ]]; then
      key="${BASH_REMATCH[1]}"
      value="${BASH_REMATCH[2]}"
    fi
    if [ -n "$key" ] && [[ "$key" =~ $KEY_RE ]]; then
      # Strip one layer of quotes, then require an interpolation.
      if [[ "$value" =~ ^[\"\'](.*)[\"\'][[:space:]]*$ ]]; then
        value="${BASH_REMATCH[1]}"
      fi
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
