#!/usr/bin/env bash
# tests/stack/e2e-password.sh
#
# Defines e2e_password_ensure() — the one place that resolves (and, when
# nothing else provides one, GENERATES) the shared E2E_PASSWORD used to seed
# every Casdoor e2e/BDD role. This is a sourceable library, not a script:
#
#   . tests/stack/e2e-password.sh
#   e2e_password_ensure
#   # $E2E_PASSWORD is now set + exported, and the credentials file exists.
#
# Precedence (deliberately GENERATE-when-unset, never fail-when-unset — a
# hard requirement here would redden every e2e/BDD lane on the very first
# run in every repo that doesn't already export E2E_PASSWORD):
#
#   1. a per-role $E2E_<ROLE>_PASSWORD — untouched by this function. The
#      Makefile / docker-compose.e2e.yml apply those on top and they always
#      win; this function only ever resolves the SHARED default.
#   2. an already-exported $E2E_PASSWORD — left alone as-is. Lets an
#      operator pin a known value, and makes repeated calls within the same
#      `make e2e` invocation idempotent (second call is a no-op).
#   3. the run-state credentials file ($E2E_CREDENTIALS_FILE, default
#      <repo-root>/.e2e-credentials) — whoever generated first in THIS run
#      wrote it there; every other process/container in the same run reads
#      it back instead of minting its own, different, random value.
#   4. generate a fresh 32-char CSPRNG value, persist it, export it.
#
# The literal "e2e-pass" must never appear here or anywhere downstream as a
# default — see CONTRACT.md rule 1.
#
# Format written to the credentials file: plain KEY=value lines, no quotes,
# no `export` keyword, so it works both with `set -a; . file; set +a` and
# with `docker compose --env-file`.

e2e_password_ensure() {
  # 2. Already exported — operator override, or a second call in the same
  # run. Nothing to do.
  if [ -n "${E2E_PASSWORD:-}" ]; then
    export E2E_PASSWORD
    return 0
  fi

  local repo_root
  repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  local creds_file="${E2E_CREDENTIALS_FILE:-${repo_root}/.e2e-credentials}"

  # 3. Someone else already generated for this run — reuse it rather than
  # minting a second, inconsistent password (every seeded Casdoor user and
  # every Playwright process in the run must agree on ONE value).
  if [ -f "${creds_file}" ]; then
    # PRESENT BUT UNREADABLE IS NOT "ABSENT". A sudo-driven run writes this file
    # root-owned 0600; every later non-root run can stat it but not read it. Left
    # alone, sed exits 2, pipefail propagates it through `| tail -n1`, and a
    # caller under `set -e` dies at the assignment with no useful message.
    # Regenerating would be wrong — the value other processes already hold is in
    # that file — so say what is wrong and how to fix it.
    if [ ! -r "${creds_file}" ]; then
      echo "e2e_password_ensure: ${creds_file} exists but is not readable by" >&2
      echo "  $(id -un) (owner $(stat -c '%U' "${creds_file}" 2>/dev/null || echo '?'), mode $(stat -c '%a' "${creds_file}" 2>/dev/null || echo '?'))." >&2
      echo "  Fix: sudo chown $(id -un) ${creds_file}   (or delete it and re-run)." >&2
      return 1
    fi
    local existing
    existing="$(sed -n 's/^E2E_PASSWORD=//p' "${creds_file}" | tail -n1)"
    if [ -n "${existing}" ]; then
      E2E_PASSWORD="${existing}"
      export E2E_PASSWORD
      return 0
    fi
  fi

  # 4. Nothing to reuse — generate. openssl first; /dev/urandom + tr as the
  # fallback for a box without openssl. Never $RANDOM, never date, never a
  # PID — all guessable / low-entropy.
  local generated=""
  if command -v openssl >/dev/null 2>&1; then
    generated="$(openssl rand -base64 24 | tr -d '\n/+=')"
  fi
  if [ -z "${generated}" ]; then
    # FILTER THEN TRUNCATE, not the reverse. `head -c 32` first takes 32 raw
    # random BYTES; tr -dc then discards everything non-alphanumeric, and only
    # ~62 of 256 byte values survive — so that ordering yields 8-11 characters,
    # not 32, and can return an empty string on an unlucky draw. Measured:
    # lengths 9,10,10,11,11 across five runs.
    generated="$(tr -dc 'A-Za-z0-9' < /dev/urandom 2>/dev/null | head -c 32)"
  fi
  if [ -z "${generated}" ]; then
    echo "e2e_password_ensure: failed to generate a password (no openssl, no /dev/urandom)" >&2
    return 1
  fi

  # PERSIST FIRST, EXPORT AFTER, AND TREAT A FAILED WRITE AS FATAL.
  #
  # Every other process and container in this run resolves through step 3 by
  # reading this file. If the write fails and we export anyway, the next
  # process finds no value, generates a DIFFERENT one, and seeds Casdoor users
  # that the first process cannot log in as — with no error anywhere. The run
  # then fails much later, somewhere unrelated. So: write, prove it, then
  # export.
  #
  # Rewrite-via-tempfile rather than `>>`: appending leaves stale
  # E2E_PASSWORD= lines behind, and only a fresh file gets umask 077 — an
  # existing one keeps whatever mode it already had.
  if ! ( umask 077
         tmp="$(mktemp "${creds_file}.XXXXXX")" || exit 1
         if [ -f "${creds_file}" ]; then
           grep -v '^E2E_PASSWORD=' "${creds_file}" > "${tmp}" 2>/dev/null || true
         fi
         printf 'E2E_PASSWORD=%s\n' "${generated}" >> "${tmp}" || { rm -f "${tmp}"; exit 1; }
         chmod 600 "${tmp}" || { rm -f "${tmp}"; exit 1; }
         mv -f "${tmp}" "${creds_file}" || { rm -f "${tmp}"; exit 1; }
       ); then
    echo "e2e_password_ensure: generated a password but could NOT write it to" >&2
    echo "  ${creds_file}" >&2
    echo "  Every other process in this run resolves from that file, so continuing" >&2
    echo "  would hand them a different password. Set E2E_CREDENTIALS_FILE to a" >&2
    echo "  writable path, or export E2E_PASSWORD yourself to skip generation." >&2
    return 1
  fi

  # POSITIVE POST-CONDITION: read it back. The writer reporting on its own
  # work is not evidence that a later process will resolve the same value.
  local readback
  readback="$(sed -n 's/^E2E_PASSWORD=//p' "${creds_file}" | tail -n1)"
  if [ "${readback}" != "${generated}" ]; then
    echo "e2e_password_ensure: wrote E2E_PASSWORD to ${creds_file} but read back a" >&2
    echo "  different value. Refusing to continue with a password the rest of this" >&2
    echo "  run will not resolve to." >&2
    return 1
  fi

  E2E_PASSWORD="${generated}"
  export E2E_PASSWORD
}
