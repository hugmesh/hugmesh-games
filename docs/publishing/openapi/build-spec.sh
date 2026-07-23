#!/usr/bin/env bash
# HugMesh Games — OpenAPI spec build pipeline.
#
# Produces three spec files at docs/publishing/openapi/build/:
#   openapi.json      — BFF (curl /v3/api-docs from the api service)
#                       merged with the editorial sidecar metadata.json
#                       so x-hugmesh extensions decorate every tag/op.
#   openapi-rpg.json  — rpg-builder side service (/api/rpg/*).
#   openapi-vn.json   — vn-builder  side service (/api/vn/*).
#
# Each spec fetches independently. Order of preference per spec:
#   1. Live /v3/api-docs from the matching service (curl), OR
#   2. Static fallback (only the BFF ships one — openapi.fallback.json), OR
#   3. Minimal stub so downstream builds keep working.
#
# Output is git-ignored. Run via `make docs-openapi-spec`.
#
# The api-docs federated remote (frontend/projects/api-docs/) reads the
# three specs from /docs/api/openapi.json, /docs/api/openapi-rpg.json,
# /docs/api/openapi-vn.json — bind-mounted into the host nginx by the
# frontend Dockerfile.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
HERE="$ROOT/docs/publishing/openapi"
OUT="$HERE/build"
META="$HERE/metadata.json"
FALLBACK="$HERE/openapi.fallback.json"
PUBLISHED="$OUT/openapi.json"
PYTHON="${PYTHON:-$HOME/.venvs/hugmesh-publishing/bin/python}"

# Endpoint URLs — override via HUGMESH_*_URL when running outside compose.
# Default to localhost:8080 because docs-openapi-spec is normally invoked
# while a dev stack is up; the host nginx proxies /api/rpg/ -> rpg-builder
# and /api/vn/ -> vn-builder, so /api/rpg/v3/api-docs and /api/vn/v3/api-docs
# reach the right side service without per-service ports.
BFF_LIVE="${HUGMESH_BFF_URL:-${HUGMESH_BASE_URL:-http://localhost:8080}}/v3/api-docs"
RPG_LIVE="${HUGMESH_RPG_BUILDER_URL:-${HUGMESH_BASE_URL:-http://localhost:8080}}/api/rpg/v3/api-docs"
VN_LIVE="${HUGMESH_VN_BUILDER_URL:-${HUGMESH_BASE_URL:-http://localhost:8080}}/api/vn/v3/api-docs"

mkdir -p "$OUT"

# fetch_spec <label> <live_url> <out_path> <fallback_path_or_empty>
fetch_spec() {
  local label="$1" url="$2" out="$3" fallback="$4"
  local tmp
  tmp="$(mktemp)"
  echo "→ probing live $label spec ($url)"
  if curl -sf -m 5 -H "Accept: application/json" "$url" -o "$tmp" \
     && [[ -s "$tmp" ]] \
     && head -c 1 "$tmp" | grep -q '{'; then
    echo "  ✓ fetched $(wc -c < "$tmp") bytes from $url"
    cp "$tmp" "$out"
  elif [[ -n "$fallback" && -s "$fallback" ]]; then
    echo "  ✗ live $label unreachable — using static fallback at $fallback"
    cp "$fallback" "$out"
  else
    echo "  ✗ live $label unreachable AND no fallback — writing stub"
    cat > "$out" <<STUB
{ "openapi": "3.0.3",
  "info": { "title": "$label spec (stub)", "version": "0.1.0",
            "description": "Stub — bring the matching service up and re-run 'make docs-openapi-spec'." },
  "paths": {} }
STUB
  fi
  rm -f "$tmp"
}

fetch_spec "BFF"         "$BFF_LIVE" "$OUT/openapi.live.json" "$FALLBACK"
fetch_spec "rpg-builder" "$RPG_LIVE" "$OUT/openapi-rpg.json"  ""
fetch_spec "vn-builder"  "$VN_LIVE"  "$OUT/openapi-vn.json"   ""

echo "→ merging $OUT/openapi.live.json + $META → $PUBLISHED"
"$PYTHON" "$HERE/merge-metadata.py" \
  --spec "$OUT/openapi.live.json" \
  --metadata "$META" \
  --out "$PUBLISHED"
echo "  ✓ merged BFF spec at $PUBLISHED"
echo "  ✓ rpg-builder spec at $OUT/openapi-rpg.json"
echo "  ✓ vn-builder  spec at $OUT/openapi-vn.json"
