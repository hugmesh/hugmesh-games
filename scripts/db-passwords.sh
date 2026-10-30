#!/usr/bin/env bash
# scripts/db-passwords.sh
#
# Defines db_passwords_ensure() — the one place that resolves (and, when
# nothing else provides one, GENERATES) the three Postgres role passwords the
# compose stack needs. This is a sourceable library, not a script:
#
#   . scripts/db-passwords.sh
#   db_passwords_ensure
#   # GAMES_DB_PASS, RPG_BUILDER_DB_PASS and VN_BUILDER_DB_PASS are now set
#   # + exported, and every one of them is persisted in the env file.
#
# Why it exists: docker-compose.yml used to hard-code each role's password as
# a literal, and compose.prod.yml / compose.staging.yml inherited those
# literals unchanged, so a production bring-up ran on a password that is in
# the public history. docker-compose.yml now REQUIRES all three
# (`${VAR:?...}`), with no literal default anywhere. This function is how a
# dev box gets values without anyone typing one.
#
# Precedence, per variable (same shape as tests/stack/e2e-password.sh):
#
#   1. an already-exported, non-empty value — left alone. This is how prod /
#      staging supply the managed secret, and how an operator pins the value
#      an EXISTING Postgres volume was initialised with (Postgres only reads
#      POSTGRES_PASSWORD on first init; a new value does not change the role).
#   2. the env file ($DB_PASSWORDS_FILE, default <repo-root>/.env — the
#      gitignored file `docker compose` auto-loads) — reused, so every compose
#      invocation, every make recipe and every later run agree on ONE value.
#   3. generate 48 hex chars from a CSPRNG, persist it to the env file, export.
#      Hex on purpose: the value is embedded in a libpq DSN (Casdoor's
#      dataSourceName) and could be embedded in a URL; hex needs no escaping
#      in either.
#
# Existing lines in the env file (CASDOOR_* etc.) are preserved; only a key
# this function generates is (re)written. A file it writes is left mode 0600.

DB_PASSWORD_VARS="GAMES_DB_PASS RPG_BUILDER_DB_PASS VN_BUILDER_DB_PASS"

_db_password_generate() {
  local out=""
  if command -v openssl >/dev/null 2>&1; then
    out="$(openssl rand -hex 24 2>/dev/null | tr -d '\n')"
  fi
  if [ -z "${out}" ]; then
    out="$(head -c 24 /dev/urandom 2>/dev/null | od -A n -t x1 | tr -d ' \n')"
  fi
  # 24 random bytes -> exactly 48 hex chars. Anything else is a failed draw.
  if [ "${#out}" -ne 48 ]; then
    return 1
  fi
  printf '%s' "${out}"
}

db_passwords_ensure() {
  local repo_root
  repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  local env_file
  env_file="${DB_PASSWORDS_FILE:-${repo_root}/.env}"

  # PRESENT BUT UNREADABLE IS NOT "ABSENT" — regenerating would silently hand
  # the stack a password the existing Postgres volume does not know.
  if [ -e "${env_file}" ] && [ ! -r "${env_file}" ]; then
    echo "db_passwords_ensure: ${env_file} exists but is not readable by $(id -un)." >&2
    echo "  Fix its ownership/mode, or export the DB passwords yourself." >&2
    return 1
  fi

  local var
  local value
  local generated
  for var in ${DB_PASSWORD_VARS}; do
    # 1. Already exported.
    value="${!var:-}"
    if [ -n "${value}" ]; then
      export "${var}"
      continue
    fi

    # 2. Persisted in the env file.
    if [ -f "${env_file}" ]; then
      value="$(sed -n "s/^${var}=//p" "${env_file}" | tail -n1)"
      if [ -n "${value}" ]; then
        printf -v "${var}" '%s' "${value}"
        export "${var}"
        continue
      fi
    fi

    # 3. Generate, persist (write -> read back), then export.
    generated="$(_db_password_generate)" || {
      echo "db_passwords_ensure: could not generate ${var} (no openssl, no /dev/urandom)" >&2
      return 1
    }
    if ! ( umask 077
           tmp="$(mktemp "${env_file}.XXXXXX")" || exit 1
           if [ -f "${env_file}" ]; then
             grep -v "^${var}=" "${env_file}" > "${tmp}" 2>/dev/null || true
           fi
           # The file now holds DB passwords: 0600 whether it is new or was
           # an operator's looser .env (mktemp already creates 0600; this
           # states it rather than relying on one mktemp implementation).
           chmod 600 "${tmp}" || { rm -f "${tmp}"; exit 1; }
           printf '%s=%s\n' "${var}" "${generated}" >> "${tmp}" || { rm -f "${tmp}"; exit 1; }
           mv -f "${tmp}" "${env_file}" || { rm -f "${tmp}"; exit 1; }
         ); then
      echo "db_passwords_ensure: generated ${var} but could NOT write it to ${env_file}." >&2
      echo "  Set DB_PASSWORDS_FILE to a writable path, or export ${var} yourself." >&2
      return 1
    fi
    value="$(sed -n "s/^${var}=//p" "${env_file}" | tail -n1)"
    if [ "${value}" != "${generated}" ]; then
      echo "db_passwords_ensure: wrote ${var} to ${env_file} but read back a different value." >&2
      return 1
    fi
    printf -v "${var}" '%s' "${generated}"
    export "${var}"
  done
}
