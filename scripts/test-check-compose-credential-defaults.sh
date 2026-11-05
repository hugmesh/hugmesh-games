#!/usr/bin/env bash
# Tests for scripts/check-compose-credential-defaults.sh. Needs bash, grep,
# sed, tr, mktemp; no network, no docker. Run by the release workflow's
# version job and the GitLab test stage before the guard itself; locally:
#   bash scripts/test-check-compose-credential-defaults.sh
#
# Three parts:
#   1. shapes — fixture lines the guard must accept and lines it must reject,
#      one at a time, so a rule that stops matching is a named failure;
#   2. the real tree — the four prod-path files must be clean;
#   3. mutations — a copy of the real tree with ONE fake credential literal
#      restored must be red (and the fake value must not be printed), a copy
#      with a scanned file deleted must be red, a NUL byte must not blind it.
#
# Every fixture value is obviously fake; nothing here is or was a real secret.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"
guard="$here/check-compose-credential-defaults.sh"

work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/compose-cred-guard-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

pass=0
fail=0
ok() { pass=$((pass + 1)); echo "ok   $*"; }
bad() { fail=$((fail + 1)); echo "FAIL $*"; }

# run_guard FILE... -> sets rc and out (stdout+stderr), never exits the test
run_guard() {
  set +e
  out="$(bash "$guard" "$@" 2>&1)"
  rc=$?
  set -e
}

expect_green() { # expect_green LABEL FILE...
  local label="$1"; shift
  run_guard "$@"
  if [ "$rc" -eq 0 ]; then ok "green: $label"; else bad "green: $label (rc=$rc) -- $out"; fi
}

expect_red() { # expect_red LABEL EXPECTED_NAME FILE...
  local label="$1" name="$2"; shift 2
  run_guard "$@"
  if [ "$rc" -ne 1 ]; then
    bad "red: $label (rc=$rc, want 1) -- $out"
    return
  fi
  if printf '%s' "$out" | grep -E ":[0-9]+: $name " >/dev/null; then
    ok "red: $label (names $name)"
  else
    bad "red: $label (does not name $name) -- $out"
  fi
}

fixture() { # fixture NAME <<'EOF' ... EOF  -> prints the path
  local path="$work/$1.yml"
  cat > "$path"
  printf '%s' "$path"
}

# --- 1. shapes: accepted -------------------------------------------------
green="$(fixture green <<'EOF'
services:
  db:
    environment:
      POSTGRES_USER: threewizards
      POSTGRES_PASSWORD: ${GAMES_DB_PASS:?GAMES_DB_PASS must be set}
      CLICKHOUSE_PASSWORD: ${CLICKHOUSE_PASSWORD:?required}
      CASDOOR_ADMIN_PASS: ${CASDOOR_ADMIN_PASS:-}
      CASDOOR_CLIENT_SECRET: ${CASDOOR_CLIENT_SECRET:-}
      E2E_PLAYER_PASSWORD: ${E2E_PLAYER_PASSWORD:-${E2E_PASSWORD:?run make e2e}}
      APP_SECURITY_REQUIRE_MANAGED_SECRETS: "true"
      HUGMESH_TAG: ${HUGMESH_TAG:-dev}
      CASDOOR_CLIENT_ID: ${CASDOOR_CLIENT_ID:-twclient001}
      APP_UPLOADS_S3_BUCKET: ${APP_UPLOADS_S3_BUCKET:-hugmesh-uploads}
      KEYSTORE_PASSWORD: ${KEYSTORE_PASSWORD:?run make e2e}  # inline comment with ${X_PASSWORD:-notavalue}
      # CLICKHOUSE_PASSWORD: ${CLICKHOUSE_PASSWORD:-commentedout}
      # MINIO_ROOT_PASSWORD: commentedout
      dataSourceName: "user=u password=${GAMES_DB_PASS:?set it} host=postgres"
    entrypoint: |
      /bin/sh -c "
        mc alias set local http://minio:9000 \"$$MINIO_ROOT_USER\" \"$$MINIO_ROOT_PASSWORD\"
      "
EOF
)"
expect_green "required, empty-default, nested-var default, non-credential defaults, comments, dollar-dollar passthrough" "$green"

# --- 1. shapes: rejected, one line each ----------------------------------
reject() { # reject LABEL EXPECTED_NAME LINE
  local label="$1" name="$2" line="$3"
  local f="$work/red-$pass-$fail.yml"
  printf 'services:\n  s:\n    environment:\n      %s\n' "$line" > "$f"
  expect_red "$label" "$name" "$f"
}
reject "PASSWORD literal default"          CLICKHOUSE_PASSWORD   'CLICKHOUSE_PASSWORD: ${CLICKHOUSE_PASSWORD:-fakevalue1}'
reject "_USER literal default"             MINIO_ROOT_USER       'MINIO_ROOT_USER: ${MINIO_ROOT_USER:-fakeuser1}'
reject "colon-less default (no colon)"         MINIO_ROOT_PASSWORD   'MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD-fakevalue2}'
reject "_PASS literal default"             CASDOOR_ADMIN_PASS    'CASDOOR_ADMIN_PASS: ${CASDOOR_ADMIN_PASS:-fakevalue3}'
reject "SECRET literal default"            CASDOOR_CLIENT_SECRET 'CASDOOR_CLIENT_SECRET: ${CASDOOR_CLIENT_SECRET:-fakevalue4}'
reject "TOKEN literal default"             API_TOKEN             'API_TOKEN: ${API_TOKEN:-fakevalue5}'
reject "PASSWD literal default"            DB_PASSWD             'DB_PASSWD: ${DB_PASSWD:-fakevalue6}'
reject "plain literal, unquoted"           REPLICATOR_PASSWORD   'REPLICATOR_PASSWORD: fakevalue7'
reject "plain literal, double-quoted"      REPLICATOR_PASSWORD   'REPLICATOR_PASSWORD: "fakevalue8"'
reject "plain literal, single-quoted"      X_SECRET              "X_SECRET: 'fakevalue9'"
reject "plain literal, list form"          X_TOKEN               '- X_TOKEN=fakevalue10'
reject "default inside a longer scalar"    KEYSTORE_PASSWORD     'JAVA_TOOL_OPTIONS: -Djavax.net.ssl.trustStorePassword=${KEYSTORE_PASSWORD:-fakevalue11}'
reject "default inside a DSN string"       GAMES_DB_PASS         'dataSourceName: "user=u password=${GAMES_DB_PASS:-fakevalue12} host=h"'

# The report must name the variable and NEVER print the value.
run_guard "$work/red-0-0.yml"
if printf '%s' "$out" | grep -F 'fakevalue1' >/dev/null; then
  bad "report leaks the default value"
else
  ok "report names the variable, not the value"
fi

# --- 2. the real tree ----------------------------------------------------
expect_green "real tree: docker-compose.yml + replica + prod + staging" \
  "$root/docker-compose.yml" "$root/docker-compose.replica.yml" "$root/compose.prod.yml" "$root/compose.staging.yml"
run_guard
if [ "$rc" -eq 0 ]; then ok "real tree: default file list"; else bad "real tree: default file list (rc=$rc) -- $out"; fi

# --- 3. mutations of the real tree ----------------------------------------
mkdir -p "$work/tree"
for f in docker-compose.yml docker-compose.replica.yml compose.prod.yml compose.staging.yml; do
  cp "$root/$f" "$work/tree/$f"
done
tree_files=("$work/tree/docker-compose.yml" "$work/tree/docker-compose.replica.yml" "$work/tree/compose.prod.yml" "$work/tree/compose.staging.yml")

mutate() { # mutate FILE SED_EXPR LABEL -> fails the test if the file did not change
  local f="$1" expr="$2" label="$3"
  cp "$f" "$f.orig"
  sed -i -E "$expr" "$f"
  if cmp -s "$f" "$f.orig"; then
    bad "mutation did not change the file: $label"
    return 1
  fi
  return 0
}
restore() { mv -f "$1.orig" "$1"; }

# 3a. restore ONE fake literal default on ClickHouse in the base file
if mutate "$work/tree/docker-compose.yml" 's/\$\{CLICKHOUSE_PASSWORD:\?[^}]*\}/${CLICKHOUSE_PASSWORD:-mutantvalue}/' "ClickHouse default"; then
  expect_red "mutation: ClickHouse literal default restored" CLICKHOUSE_PASSWORD "${tree_files[@]}"
  if printf '%s' "$out" | grep -F mutantvalue >/dev/null; then bad "mutation report leaks the value"; else ok "mutation report does not leak the value"; fi
  restore "$work/tree/docker-compose.yml"
fi

# 3b. MinIO user default (the _USER rule) in the base file
if mutate "$work/tree/docker-compose.yml" '0,/\$\{MINIO_ROOT_USER:\?[^}]*\}/s//${MINIO_ROOT_USER:-mutantuser}/' "MinIO user default"; then
  expect_red "mutation: MinIO user literal default restored" MINIO_ROOT_USER "${tree_files[@]}"
  restore "$work/tree/docker-compose.yml"
fi

# 3c. replicator password back to a plain literal in the replica overlay
if mutate "$work/tree/docker-compose.replica.yml" '0,/REPLICATOR_PASSWORD: \$\{REPLICATOR_PASSWORD:\?[^}]*\}/s//REPLICATOR_PASSWORD: mutantvalue/' "replicator literal"; then
  expect_red "mutation: replicator plain literal restored" REPLICATOR_PASSWORD "${tree_files[@]}"
  restore "$work/tree/docker-compose.replica.yml"
fi

# 3d. a nested-variable default must stay green (it is not a literal)
if mutate "$work/tree/docker-compose.yml" '0,/\$\{MINIO_ROOT_PASSWORD:\?[^}]*\}/s//${MINIO_ROOT_PASSWORD:-${OTHER_SECRET:?set}}/' "nested default"; then
  expect_green "mutation: nested-variable default stays green" "${tree_files[@]}"
  restore "$work/tree/docker-compose.yml"
fi

# 3e. a commented-out literal must stay green (compose cannot reach it)
printf '      # CLICKHOUSE_PASSWORD: ${CLICKHOUSE_PASSWORD:-commentedmutant}\n' >> "$work/tree/compose.staging.yml"
expect_green "mutation: commented-out literal stays green" "${tree_files[@]}"
restore_line() { sed -i '$ d' "$1"; }
restore_line "$work/tree/compose.staging.yml"

# 3f. a NUL byte before a violation must not blind the guard
{ printf '\000'; printf '      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:-nulmutant}\n'; } >> "$work/tree/compose.prod.yml"
expect_red "mutation: NUL byte does not hide a literal default" MINIO_ROOT_PASSWORD "${tree_files[@]}"
cp "$root/compose.prod.yml" "$work/tree/compose.prod.yml"

# 3g. a scanned file that is missing is not clean
rm "$work/tree/compose.prod.yml"
run_guard "${tree_files[@]}"
if [ "$rc" -eq 2 ]; then ok "mutation: missing scanned file is red (rc=2)"; else bad "mutation: missing scanned file (rc=$rc, want 2) -- $out"; fi

echo
echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
