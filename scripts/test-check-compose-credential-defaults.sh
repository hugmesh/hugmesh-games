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
first_red=""
reject() { # reject LABEL EXPECTED_NAME LINE
  local label="$1" name="$2" line="$3"
  local f="$work/red-$pass-$fail.yml"
  printf 'services:\n  s:\n    environment:\n      %s\n' "$line" > "$f"
  [ -n "$first_red" ] || first_red="$f"
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
# Nested defaults (the round-1 fail-open: a regex that stopped at the first
# `}` saw `${OTHER:-literal` as "starts with $" and never looked inside).
# Each renders the literal under `docker compose config` with the vars unset.
reject "nested: credential in credential"  MINIO_ROOT_PASSWORD   'MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:-${MINIO_FALLBACK_PASSWORD:-fakenested1}}'
reject "nested: inner credential named"    MINIO_FALLBACK_PASSWORD 'MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:-${MINIO_FALLBACK_PASSWORD:-fakenested2}}'
reject "nested: credential under non-cred" DB_PASSWORD           'HUGMESH_TAG: ${HUGMESH_TAG:-${DB_PASSWORD:-fakenested3}}'
reject "nested: non-cred under credential" CLICKHOUSE_PASSWORD   'CLICKHOUSE_PASSWORD: ${CLICKHOUSE_PASSWORD:-${CH_FALLBACK:-fakenested4}}'
reject "nested: literal after inner var"   CLICKHOUSE_PASSWORD   'CLICKHOUSE_PASSWORD: ${CLICKHOUSE_PASSWORD:-${CH_PREFIX}fakenested5}'
reject "nested: three deep"                CLICKHOUSE_PASSWORD   'CLICKHOUSE_PASSWORD: ${CLICKHOUSE_PASSWORD:-${A:-${B:-fakenested6}}}'
reject "nested: required var then literal" API_TOKEN             'API_TOKEN: ${API_TOKEN:-${BASE:?set}fakenested7}'
reject "second interpolation on a line"    X_SECRET              'URL: ${HOST:?set}:${PORT:-8080}/${X_SECRET:-fakevalue13}'
reject "dollar-escaped shell default"      MINIO_ROOT_PASSWORD   "entrypoint: sh -c 'mc alias set l http://m \$\${MINIO_ROOT_USER:?} \$\${MINIO_ROOT_PASSWORD:-fakeshell1}'"
reject "alternate value on a credential"   CASDOOR_ADMIN_PASS    'CASDOOR_ADMIN_PASS: ${CASDOOR_ADMIN_PASS:+fakealt1}'
reject "unknown operator fails closed"     DB_PASSWD             'DB_PASSWD: ${DB_PASSWD:=fakeunknown1}'
# A ` #` inside a QUOTED scalar is value text, not a YAML comment: compose
# renders `${MINIO_ROOT_PASSWORD:- #fakeH2}` unset as ` #fakeH2`. The old
# stripper cut every ` #...` tail regardless of quoting, left the dangling
# `${MINIO_ROOT_PASSWORD:-`, and the guard printed "clean" (hugmesh-games#61
# refute). Non-credential keys, so rule B cannot rescue any of these.
reject "hash in double-quoted default"     MINIO_ROOT_PASSWORD   'CONN: "${MINIO_ROOT_PASSWORD:- #fakeH2}"'
reject "hash in single-quoted default"     MINIO_ROOT_PASSWORD   "CONN: '\${MINIO_ROOT_PASSWORD:- #fakeH3}'"
reject "hash in quoted list entry"         X_SECRET              '- "CONN=${X_SECRET:- #fakeH4}"'
reject "hash after an escaped dquote"      X_TOKEN               'CONN: "a\" #b ${X_TOKEN:-fakeH5}"'
reject "hash after a doubled squote"       X_SECRET              "CONN: 'it''s #b \${X_SECRET:-fakeH6}'"
reject "hash in a flow sequence entry"     X_PASSWORD            'command: ["sh", "-c", "echo ${X_PASSWORD:- #fakeH7}"]'
reject "hash in an earlier flow entry"     X_PASSWORD            'command: ["a #b", "${X_PASSWORD:-fakeH8}"]'
reject "hash with no space is not a comment" X_PASSWORD          'URL: http://h/#frag${X_PASSWORD:-fakeH9}'
# A node property (`&anchor`, `!tag`, `!!str`, compose's `!override`)
# between the indicator and the quote still leaves a quoted scalar: the
# round-2 stripper only knew a quote right after `key: ` and cut these at
# ` #y`, so the default was never scanned (#69 refute).
reject "anchor before a double quote"      MINIO_ROOT_PASSWORD   'CONN: &c "x #y ${MINIO_ROOT_PASSWORD:-fakeP1}"'
reject "anchor before a single quote"      MINIO_ROOT_PASSWORD   "CONN: &c 'x #y \${MINIO_ROOT_PASSWORD:-fakeP2}'"
reject "!!str tag before a quote"          X_SECRET              'CONN: !!str "x #y ${X_SECRET:-fakeP3}"'
reject "!override tag before a quote"      X_TOKEN               'CONN: !override "x #y ${X_TOKEN:-fakeP4}"'
reject "anchor and tag before a quote"     X_PASSWORD            'CONN: &c !!str "x #y ${X_PASSWORD:-fakeP5}"'
reject "tag on a quoted list entry"        X_SECRET              '- !!str "CONN=x #y ${X_SECRET:-fakeP6}"'
reject "flow mapping, adjacent value"      X_SECRET              'labels: {"a":"b #c ${X_SECRET:-fakeF2}"}'

# Nested shapes that render no literal stay green.
nested_green="$(fixture nested-green <<'EOF'
services:
  s:
    environment:
      A_PASSWORD: ${A_PASSWORD:-${B_PASSWORD:-}}
      C_PASSWORD: ${C_PASSWORD:-$C_FROM_ENV}
      D_PASSWORD: ${D_PASSWORD:-${D_FROM_ENV}}
      E_PASSWORD: ${E_PASSWORD:-${E_FROM_ENV:?set E}}
      F_TOKEN: ${F_TOKEN:-${G_TOKEN:-${H_TOKEN:?set H}}}
      HUGMESH_TAG: ${HUGMESH_TAG:-${OTHER_TAG:-dev}}
      URL: ${HOST:?set}:${PORT:-8080}/${PATH_PREFIX:-api}
    entrypoint: sh -c 'echo $${I_PASSWORD:?} $$J_PASSWORD'
EOF
)"
expect_green "nested defaults made only of env values; non-credential nested literals" "$nested_green"

# Multi-line shapes: what YAML reads as value text across a line break.
ml_dq="$(fixture ml-dq <<'EOF'
services:
  s:
    environment:
      CONN: "first part
        ${X_TOKEN:- #fakeML1}"
EOF
)"
expect_red "multi-line double-quoted scalar: hash on the continuation line" X_TOKEN "$ml_dq"
ml_dq_comment="$(fixture ml-dq-comment <<'EOF'
services:
  s:
    environment:
      CONN: "first part
        # still inside the quotes ${X_TOKEN:-fakeML2}"
EOF
)"
expect_red "multi-line double-quoted scalar: a '#' line inside it is not a comment" X_TOKEN "$ml_dq_comment"
block="$(fixture block <<'EOF'
services:
  s:
    entrypoint: |
      sh -c 'x #y $${X_PASSWORD:-fakeBL1}'
EOF
)"
expect_red "block scalar: hash inside it is value text" X_PASSWORD "$block"
block_hashline="$(fixture block-hashline <<'EOF'
services:
  s:
    entrypoint: >-
      # a shell comment is still part of the value
      sh -c 'echo ok'
      # ${Y_SECRET:-fakeBL2}
EOF
)"
expect_red "block scalar: a '#' line inside it is not a YAML comment" Y_SECRET "$block_hashline"
anchor_block="$(fixture anchor-block <<'EOF'
services:
  s:
    entrypoint: &e |
      sh -c "x #y $${X_PASSWORD:-fakeB1}"
EOF
)"
expect_red "block scalar after an anchor: hash inside it is value text" X_PASSWORD "$anchor_block"
tag_block="$(fixture tag-block <<'EOF'
services:
  s:
    environment:
      CONN: !!str >-
        x #y ${Y_SECRET:-fakeB2}
EOF
)"
expect_red "block scalar after a tag: hash inside it is value text" Y_SECRET "$tag_block"
flow_ml="$(fixture flow-ml <<'EOF'
services:
  s:
    command: ["a",
      "b #c ${X_PASSWORD:-fakeF1}"]
EOF
)"
expect_red "flow sequence across lines: a quote after ',' on the next line" X_PASSWORD "$flow_ml"
flow_ml_plain="$(fixture flow-ml-plain <<'EOF'
services:
  s:
    command: [a,
      b, "c #d ${X_PASSWORD:-fakeF3}"]
EOF
)"
expect_red "flow sequence across lines: ',' after a plain entry on the next line" X_PASSWORD "$flow_ml_plain"
seq_map="$(fixture seq-map <<'EOF'
services:
  s:
    volumes:
      - type: bind
        source: "/x #y ${X_SECRET:-fakeK1}"
        target: /x
EOF
)"
expect_red "mapping in a sequence entry: the next key is a node, not a continuation" X_SECRET "$seq_map"
seq_map_qkey="$(fixture seq-map-qkey <<'EOF'
x-list:
  - name: a
    "k #b": "${X_SECRET:-fakeK2}"
EOF
)"
expect_red "mapping in a sequence entry: a quoted key on the next line opens" X_SECRET "$seq_map_qkey"

# A quote INSIDE a plain scalar is plain text. `, ` / `- ` / `[` / `{` start
# a node only in flow context or at a node start; reading the quote in
# `MSG: hello, "world` as an opening one carried a false quote state to the
# next line, where the real opening quote was read as a closing one and
# ` #y ...` was stripped (#69 refute). Each renders the literal under
# `docker compose config`; the same CONN line alone was already red.
desync() { # desync LABEL NAME FIRST_LINE...: the lines go under environment:
  local label="$1" name="$2"; shift 2
  local f="$work/desync-$pass-$fail.yml"
  printf 'services:\n  s:\n    environment:\n' > "$f"
  printf '      %s\n' "$@" >> "$f"
  expect_red "$label" "$name" "$f"
}
desync "unbalanced quote after ', ' in a plain value"  MINIO_ROOT_PASSWORD \
  'MSG: hello, "world' 'CONN: "x #y ${MINIO_ROOT_PASSWORD:-fakeD1}"'
desync "unbalanced quote after ' - ' in a plain value" MINIO_ROOT_PASSWORD \
  "MSG: a - 'b" "CONN: 'x #y \${MINIO_ROOT_PASSWORD:-fakeD2}'"
desync "unbalanced quote on a plain continuation line" X_TOKEN \
  'MSG: hello' '  "world' 'CONN: "x #y ${X_TOKEN:-fakeD3}"'
desync "unbalanced quote on a continuation after a blank line" X_TOKEN \
  'MSG: hello' '' '  "world' 'CONN: "x #y ${X_TOKEN:-fakeD6}"'
desync "unbalanced quote after '{' in a plain value"   X_SECRET \
  'MSG: a {b "c' 'CONN: "x #y ${X_SECRET:-fakeD4}"'
desync "unbalanced quote after '[' ', ' in a plain value" X_SECRET \
  "MSG: a [b, 'c" "CONN: 'x #y \${X_SECRET:-fakeD5}'"

# A value that starts on a LATER line than its key or `-` is measured
# against its owner's column, not its own line's (#69 refute, round 3).
# A `|`/`>` header alone on its line may have its content at, or left of,
# its own column, as long as it is past the owner's: the round-3 guard
# measured from the header's line, ended the block at the first content
# line and stripped ` #y ...` as a comment. Each renders the literal under
# `docker compose config`; the same content under `entrypoint: |` was red.
lone_hdr() { # lone_hdr LABEL NAME FIXTURE_NAME  (YAML on stdin)
  local f
  f="$(fixture "$3")"
  expect_red "$1" "$2" "$f"
}
lone_hdr "lone header: content at the header's column" MINIO_ROOT_PASSWORD lone-same <<'EOF'
services:
  s:
    entrypoint:
      |
      sh -c "x #y $${MINIO_ROOT_PASSWORD:-fakeL1}"
EOF
lone_hdr "lone header: a tag on the key line" MINIO_ROOT_PASSWORD lone-tag <<'EOF'
services:
  s:
    entrypoint: !override
      |
      sh -c "x #y $${MINIO_ROOT_PASSWORD:-fakeL2}"
EOF
lone_hdr "lone header: under a lone '-'" MINIO_ROOT_PASSWORD lone-seq <<'EOF'
services:
  s:
    command:
      -
        |
        sh -c "x #y $${MINIO_ROOT_PASSWORD:-fakeL3}"
EOF
lone_hdr "lone folded header in environment" MINIO_ROOT_PASSWORD lone-fold <<'EOF'
services:
  s:
    environment:
      CONN:
        >-
        x #y ${MINIO_ROOT_PASSWORD:-fakeL4}
EOF
lone_hdr "lone header deeper than its content" MINIO_ROOT_PASSWORD lone-deep <<'EOF'
services:
  s:
    entrypoint:
          |
      sh -c "x #y $${MINIO_ROOT_PASSWORD:-fakeL5}"
EOF
lone_hdr "lone header: content left of the header" X_SECRET lone-left <<'EOF'
services:
  s:
    entrypoint:
          |
     sh -c "x #y $${X_SECRET:-fakeL6}"
EOF
lone_hdr "lone header after a comment line" MINIO_ROOT_PASSWORD lone-comment-line <<'EOF'
services:
  s:
    entrypoint:
      # the script follows
      |
      sh -c "x #y $${MINIO_ROOT_PASSWORD:-fakeL7}"
EOF
lone_hdr "lone header after a key with an inline comment" MINIO_ROOT_PASSWORD lone-key-comment <<'EOF'
services:
  s:
    entrypoint: # the script follows
      |
      sh -c "x #y $${MINIO_ROOT_PASSWORD:-fakeL8}"
EOF
lone_hdr "lone header after a line of properties" MINIO_ROOT_PASSWORD lone-prop-line <<'EOF'
services:
  s:
    entrypoint:
      &e
      |
      sh -c "x #y $${MINIO_ROOT_PASSWORD:-fakeL9}"
EOF
# The same owner column decides where a PLAIN value that starts on the next
# line continues. Measured from its own line, a less-indented continuation
# line was read as a new node, its unbalanced quote opened a false quote
# state, and the real opening quote on the CONN line closed it.
lone_hdr "plain value on the next line: continuation left of it" MINIO_ROOT_PASSWORD next-plain <<'EOF'
services:
  s:
    environment:
      MSG:
        hello
       "world
      CONN: "x #y ${MINIO_ROOT_PASSWORD:-fakeN1}"
EOF
lone_hdr "plain value on the next line after a tag" MINIO_ROOT_PASSWORD next-plain-tag <<'EOF'
services:
  s:
    environment:
      MSG: !!str
        hello
       "world
      CONN: "x #y ${MINIO_ROOT_PASSWORD:-fakeN2}"
EOF
lone_hdr "plain sequence entry on the line after '-'" MINIO_ROOT_PASSWORD next-plain-seq <<'EOF'
services:
  s:
    command:
      -
        hello
       "world
    environment:
      CONN: "x #y ${MINIO_ROOT_PASSWORD:-fakeN3}"
EOF
# A key's column is its first property's: `&a MSG` starts at the `&`.
lone_hdr "anchored key: continuation left of the key" MINIO_ROOT_PASSWORD key-anchor <<'EOF'
services:
  s:
    environment:
      &a MSG: hello
        "world
      CONN: "x #y ${MINIO_ROOT_PASSWORD:-fakeN4}"
EOF
lone_hdr "anchored quoted key: continuation left of the key" MINIO_ROOT_PASSWORD key-anchor-quoted <<'EOF'
services:
  s:
    environment:
      &a "MSG": hello
        "world
      CONN: "x #y ${MINIO_ROOT_PASSWORD:-fakeN10}"
EOF
lone_hdr "anchored key after '-': the next key is a node" X_SECRET key-anchor-seq <<'EOF'
x-list:
  - &v name: a
    "k #b": "${X_SECRET:-fakeN9}"
EOF
# The owner column is used once: a node left open with no value, or whose
# value is done, does not make the next sibling a continuation.
lone_hdr "open key with no value, then a sibling" X_TOKEN pend-dropped <<'EOF'
services:
  s:
    environment:
      MSG:
      CONN: "x #y ${X_TOKEN:-fakeN5}"
EOF
lone_hdr "next-line value done, then a sibling" X_TOKEN pend-consumed <<'EOF'
services:
  s:
    environment:
      MSG:
        hello
      CONN: "x #y ${X_TOKEN:-fakeN6}"
EOF
lone_hdr "next-line value done, then a quoted key" X_SECRET pend-qkey <<'EOF'
services:
  s:
    volumes:
      - type: bind
        source:
          /a
        "target": "/x #y ${X_SECRET:-fakeN7}"
EOF
lone_hdr "next-line value done, then an explicit ': v' and a quoted key" X_SECRET pend-explicit <<'EOF'
x-map:
  MSG:
    ? k
    : v
    "q #b": "${X_SECRET:-fakeN12}"
EOF
lone_hdr "next-line entry done, then a quoted entry" X_SECRET pend-seq <<'EOF'
services:
  s:
    command:
      -
        hello
      - "x #y $${X_SECRET:-fakeN8}"
EOF

# A block header on the same line as `- key:` (or `- -`) is measured from
# the KEY's column (or the inner `-`'s), not the line's indent, which is the
# outer `-`'s (#69 refute, round 4). Measured from the line, the block was
# wider than YAML's: the mapping's later keys were swallowed as block
# content, their quote and next-line state never walked, and a line at or
# left of the `-` then ended the block mid-structure, quote still open.
# Each renders the literal under `docker compose config` (v5.1.4).
lone_hdr "'- key: |' block, then a sibling's quote continued at the '-' column" X_SECRET wide-service-dq <<'EOF'
services:
  s:
    image: busybox
    volumes:
      - source: >-
          /srv/a
        target: "/srv/b
      c #y ${X_SECRET:-fakeW9}"
        type: bind
EOF
lone_hdr "'- k: |' block, then a sibling's quote continued left of it" X_SECRET wide-dq-dashcol <<'EOF'
services:
  s:
    image: busybox
x-a:
  - k: |
      text
    m: "abc
  def #y ${X_SECRET:-fakeW10}"
EOF
lone_hdr "'- k: |' block, then a sibling's quote with a '#' line" X_SECRET wide-dq-hashline <<'EOF'
services:
  s:
    image: busybox
x-a:
  - k: |
      text
    m: "abc
  def
    #y ${X_SECRET:-fakeW11}"
EOF
lone_hdr "'- source: >-' block, then a next-line plain sibling with a quote" X_SECRET wide-volume-nextplain <<'EOF'
services:
  s:
    image: busybox
    volumes:
      - source: >-
          /srv/a
        target:
  # was /srv/b
          /srv/c
         "d
        type: bind
    environment:
      CONN: "x #y ${X_SECRET:-fakeW8}"
EOF
lone_hdr "'- k: |' block, then a sibling's lone header" X_SECRET wide-lonehdr <<'EOF'
services:
  s:
    image: busybox
x-a:
  - k: |
      text
    entrypoint:
# c
      |
     sh -c "x #y $${X_SECRET:-fakeW1}"
EOF
lone_hdr "'- k: |' block, then a sibling's next-line plain value" X_SECRET wide-nextplain <<'EOF'
services:
  s:
    image: busybox
x-a:
  - k: |
      text
    MSG:
# c
        hello
       "world
    CONN: "x #y ${X_SECRET:-fakeW2}"
EOF
lone_hdr "'- - |' block, then a lone '-' and its lone header" X_SECRET wide-dashdash <<'EOF'
services:
  s:
    image: busybox
x-a:
  - - |
      text
    -
# c
        |
       x #y ${X_SECRET:-fakeW5}
EOF

# A verbatim tag is one property, comma and all: `!<tag:yaml.org,2002:str>`
# read as `!<tag:yaml.org` and plain text left the quote after it unopened
# (#69 refute, round 4). Each renders the literal under `docker compose config`.
lone_hdr "verbatim tag with a comma before a quote" X_SECRET vtag-same <<'EOF'
services:
  s:
    image: busybox
    environment:
      CONN: !<tag:yaml.org,2002:str> "x #y ${X_SECRET:-fakeV1}"
EOF
lone_hdr "verbatim tag with a comma, quote on the next line" X_SECRET vtag-next <<'EOF'
services:
  s:
    image: busybox
    environment:
      CONN: !<tag:yaml.org,2002:str>
        "x #y ${X_SECRET:-fakeV2}"
EOF
lone_hdr "verbatim tag with a comma, then a lone header" X_SECRET vtag-lonehdr <<'EOF'
services:
  s:
    image: busybox
    entrypoint: !<tag:yaml.org,2002:str>
      |
      sh -c "x #y $${X_SECRET:-fakeV3}"
EOF

unterminated="$(fixture unterminated <<'EOF'
services:
  s:
    environment:
      CONN: ${MINIO_ROOT_PASSWORD:-
        fakeUT1}
EOF
)"
expect_red "unterminated interpolation fails closed" MINIO_ROOT_PASSWORD "$unterminated"

# ...while real YAML comments stay comments.
comments_green="$(fixture comments-green <<'EOF'
services:
  s:
    environment:
      CONN: "${A:-x}" # ${B_PASSWORD:-commentonly1}
      MSG: it's plain text # ${C_PASSWORD:-commentonly2}
      - "it's" # ${D_PASSWORD:-commentonly3}
      HEALTH: "curl -f http://x/#frag ${E_PASSWORD:?set}"
      Q: 'a ''b'' c' # ${F_PASSWORD:-commentonly4}
      ML: "two
        lines" # ${G_PASSWORD:-commentonly5}
      # ${H_PASSWORD:-commentonly6}
    entrypoint: |
      echo hi
    # ${I_PASSWORD:-commentonly7}
    command: ["a", "b"] # ${J_PASSWORD:-commentonly8}
    labels: { a: "b" } # ${K_PASSWORD:-commentonly9}
EOF
)"
expect_green "YAML comments after quoted, plain, multi-line and block scalars" "$comments_green"

# ...and the property / plain-quote / flow shapes above, with the comment
# where YAML puts it: none of these may go red (no false quote state either
# way). `docker compose config` renders none of the commentonly values.
shapes_green="$(fixture shapes-green <<'EOF'
services:
  s:
    environment:
      MSG: hello, "world
      X: "a" # ${Z_PASSWORD:-commentonlyG1}
      MSG2: a - 'b
      Y: 'a' # ${Z_PASSWORD:-commentonlyG2}
      C1: &c "x" # ${Y_PASSWORD:-commentonlyG3}
      C2: !!str 'x' # ${Y_PASSWORD:-commentonlyG4}
      MSG3: hello
        "world
      Z: "a" # ${Z_PASSWORD:-commentonlyG5}
      MSG4: a {b "c
      W: "a" # ${Z_PASSWORD:-commentonlyG6}
      MSG5: a | b
        more # ${Z_PASSWORD:-commentonlyG10}
    command: ["a",
      "b"] # ${J_PASSWORD:-commentonlyG7}
    labels: {"a":"b"} # ${K_PASSWORD:-commentonlyG8}
    entrypoint: &e |
      echo hi
    # ${I_PASSWORD:-commentonlyG9}
EOF
)"
expect_green "comments after property, plain-quote, continuation and flow shapes" "$shapes_green"

# ...and the next-line shapes above, read the way YAML reads them. A line
# more indented than the owner continues a plain value even when it is left
# of the value's first line, so its quote is plain text and its ` #` starts
# a comment: `docker compose config` renders `MSG: hello "k` and the like,
# and none of the commentonly values. A comment back at the owner's column
# ends a lone header's block scalar.
next_green="$(fixture next-green <<'EOF'
services:
  s:
    environment:
      MSG:
          hello
        "k #b": "${X_SECRET:-commentonlyN1}"
      MSG2: !!str
          hello
       "x #y ${X_SECRET:-commentonlyN2}"
      &a MSG3: hello
        "x #y ${X_SECRET:-commentonlyN3}"
      MSG4: # note
        hello # ${X_SECRET:-commentonlyN4}
    entrypoint:
      # the script follows
      |
      echo hi
    # ${I_PASSWORD:-commentonlyN5}
    command:
      -
          hello
        "x #y $${X_SECRET:-commentonlyN6}"
EOF
)"
expect_green "next-line values: continuation and comment where YAML puts them" "$next_green"

# ...and the `- k: |` shape: the block ends at the next key of the same
# mapping, whose ` # ...` tail is a real comment (`docker compose config`
# renders `m: v`). Scanned as block content it was a false red.
wide_green="$(fixture wide-green <<'EOF'
services:
  s:
    image: busybox
x-a:
  - k: |
      text
    m: v # ${X_SECRET:-commentonlyW1}
EOF
)"
expect_green "'- k: |' block ends at the next key: its comment stays a comment" "$wide_green"

# The report must name the variable and NEVER print the value. The guard
# must have actually scanned the fixture (rc=1) for "no leak" to mean
# anything: a missing file (rc=2) would also print no value.
run_guard "$first_red"
if [ "$rc" -ne 1 ]; then
  bad "leak check did not run on a scanned violation (rc=$rc) -- $out"
elif printf '%s' "$out" | grep -F 'fakevalue1' >/dev/null; then
  bad "report leaks the default value"
else
  ok "report names the variable, not the value"
fi

# Same for a nested literal: neither the outer nor the inner value printed.
nested_leak="$work/nested-leak.yml"
printf 'services:\n  s:\n    environment:\n      %s\n' 'MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:-${MINIO_FALLBACK_PASSWORD:-fakenestedleak}}' > "$nested_leak"
run_guard "$nested_leak"
if [ "$rc" -ne 1 ]; then
  bad "nested leak check did not run on a scanned violation (rc=$rc) -- $out"
elif printf '%s' "$out" | grep -F 'fakenestedleak' >/dev/null; then
  bad "report leaks a nested default value"
else
  ok "nested report names the variables, not the value"
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

# 3d'. ...but a nested default that falls back to a literal must be red
if mutate "$work/tree/docker-compose.yml" '0,/\$\{MINIO_ROOT_PASSWORD:\?[^}]*\}/s//${MINIO_ROOT_PASSWORD:-${MINIO_FALLBACK_PASSWORD:-mutantnested}}/' "nested literal default"; then
  expect_red "mutation: nested literal default is red" MINIO_ROOT_PASSWORD "${tree_files[@]}"
  if printf '%s' "$out" | grep -F mutantnested >/dev/null; then bad "nested mutation report leaks the value"; else ok "nested mutation report does not leak the value"; fi
  restore "$work/tree/docker-compose.yml"
fi

# 3d''. a quoted default with a ` #` in it must be red (the #61 refute shape),
# under a NON-credential key so rule B's dangling-quote catch cannot mask it
if mutate "$work/tree/docker-compose.yml" '0,/APP_UPLOADS_S3_BUCKET: \$\{APP_UPLOADS_S3_BUCKET:-[^}]*\}/s//APP_UPLOADS_S3_BUCKET: "${MINIO_ROOT_PASSWORD:- #mutanthash}"/' "quoted hash default"; then
  expect_red "mutation: quoted default with a hash is red" MINIO_ROOT_PASSWORD "${tree_files[@]}"
  if printf '%s' "$out" | grep -F mutanthash >/dev/null; then bad "quoted-hash mutation report leaks the value"; else ok "quoted-hash mutation report does not leak the value"; fi
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
