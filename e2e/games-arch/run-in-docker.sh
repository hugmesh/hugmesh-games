#!/usr/bin/env bash
# e2e/games-arch/run-in-docker.sh
#
# Repeatable RECHECK driver for the multi-user games architecture e2e.
#
# The api service has NO host port (see docker-compose.yml), so the harness MUST
# run from a container on the sdgames_default network to reach api:8080,
# casdoor:8000, and wasm-fragment:8080. This script:
#   1. assumes the sdgames stack is already up (docker compose -p sdgames up -d);
#   2. greps the Casdoor seed creds (client secret + player passwords) out of the
#      casdoor logs — they're randomised per fresh DB;
#   3. runs e2e/games-arch/run.mjs inside a throwaway node:20 container attached
#      to sdgames_default, with the harness dir bind-mounted read-only.
#
# Usage:  bash e2e/games-arch/run-in-docker.sh
# Env:    PROJECT (compose project, default sdgames)
#         NETWORK (default <PROJECT>_default)
set -euo pipefail

PROJECT="${PROJECT:-sdgames}"
NETWORK="${NETWORK:-${PROJECT}_default}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Resolve the actual docker network name (compose may prefix differently).
if ! docker network inspect "$NETWORK" >/dev/null 2>&1; then
  ALT="$(docker network ls --format '{{.Name}}' | grep -E "^${PROJECT}[-_]default$" | head -1 || true)"
  if [ -n "$ALT" ]; then NETWORK="$ALT"; fi
fi
echo "Using docker network: $NETWORK"

# Pull seed creds out of the casdoor first-run log. Write the log to a temp
# FILE and grep the file — NOT `echo "$LOGS" | grep -m1`. Under `set -o
# pipefail`, an early-exiting `grep -m1` closes the pipe while echo is still
# writing the (large) casdoor log, so echo dies with SIGPIPE; pipefail then
# propagates 141 and `set -e` aborts the whole script before a single token is
# minted (observed: exit 141 right after the network line). grep reading a file
# has no upstream writer to kill, so it is SIGPIPE-safe.
CASDOOR_LOG="$(mktemp)"
trap 'rm -f "$CASDOOR_LOG"' EXIT
docker compose -p "$PROJECT" logs casdoor > "$CASDOOR_LOG" 2>&1 || true

field() { grep -m1 "$1" "$CASDOOR_LOG" | sed -E "$2" || true; }

CLIENT_SECRET="${CASDOOR_CLIENT_SECRET:-$(field 'Client Secret' 's/.*Client Secret *: *//; s/[[:space:]]+$//')}"
PLAYER1_PASS="${PLAYER1_PASS:-$(field 'player1 ' 's#.*player1 +/ *##; s/[[:space:]]+$//')}"
PLAYER2_PASS="${PLAYER2_PASS:-$(field 'player2 ' 's#.*player2 +/ *##; s/[[:space:]]+$//')}"
PLAYER_PASS="${PLAYER_PASS:-$(field '  player ' 's#.*player +/ *##; s/[[:space:]]+$//')}"

if [ -z "$CLIENT_SECRET" ] || [ -z "$PLAYER1_PASS" ] || [ -z "$PLAYER2_PASS" ] || [ -z "$PLAYER_PASS" ]; then
  echo "ERROR: could not extract Casdoor seed creds from logs." >&2
  echo "  client_secret=[$CLIENT_SECRET] p1=[${PLAYER1_PASS:+set}] p2=[${PLAYER2_PASS:+set}] p=[${PLAYER_PASS:+set}]" >&2
  echo "  Pass them explicitly via CASDOOR_CLIENT_SECRET / PLAYER1_PASS / PLAYER2_PASS / PLAYER_PASS." >&2
  exit 3
fi

echo "Resolved creds (client_secret + player1/player2/player passwords)."

exec docker run --rm \
  --network "$NETWORK" \
  -v "$HERE":/harness:ro \
  -w /harness \
  -e API_BASE="${API_BASE:-http://api:8080}" \
  -e CASDOOR_BASE="${CASDOOR_BASE:-http://casdoor:8000}" \
  -e WASM_FRAGMENT_BASE="${WASM_FRAGMENT_BASE:-http://wasm-fragment:8080}" \
  -e CASDOOR_CLIENT_ID="${CASDOOR_CLIENT_ID:-twclient001}" \
  -e CASDOOR_CLIENT_SECRET="$CLIENT_SECRET" \
  -e PLAYER1_PASS="$PLAYER1_PASS" \
  -e PLAYER2_PASS="$PLAYER2_PASS" \
  -e PLAYER_PASS="$PLAYER_PASS" \
  node:20-alpine \
  node run.mjs
