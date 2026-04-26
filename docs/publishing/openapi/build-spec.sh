#!/usr/bin/env bash
# Snapdragon Games — OpenAPI spec build pipeline.
#
# Produces a single merged spec at
#   docs/publishing/openapi/build/openapi.json
# combining:
#   1. The live BFF spec (curl /v3/api-docs) when reachable, OR
#   2. A static fallback spec from this directory if not, OR
#   3. A minimal stub if neither exists.
# Then merges the editorial sidecar `metadata.json` into the spec
# under x-snapdragon extensions on each tag/operation, so downstream
# consumers (LaTeX generator, Angular API explorer) see ONE file with
# the OpenAPI contract AND the rich docs side-by-side.
#
# Output is git-ignored. Run via `make docs-openapi-spec`.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
HERE="$ROOT/docs/publishing/openapi"
OUT="$HERE/build"
META="$HERE/metadata.json"
FALLBACK="$HERE/openapi.fallback.json"
PUBLISHED="$OUT/openapi.json"
LIVE="${SNAPDRAGON_BASE_URL:-http://localhost:8080}/v3/api-docs"
PYTHON="${PYTHON:-$HOME/.venvs/snapdragon-publishing/bin/python}"

mkdir -p "$OUT"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

echo "→ probing live BFF for OpenAPI spec ($LIVE)"
# curl + a json sanity check: ports 8080/4200 may be serving the
# frontend (HTML) without the BFF. Don't accept non-JSON as a spec.
if curl -sf -m 5 -H "Accept: application/json" "$LIVE" -o "$tmp" \
   && [[ -s "$tmp" ]] \
   && head -c 1 "$tmp" | grep -q '{'; then
  echo "  ✓ fetched $(wc -c < "$tmp") bytes from $LIVE"
  cp "$tmp" "$OUT/openapi.live.json"
  base="$OUT/openapi.live.json"
elif [[ -s "$FALLBACK" ]]; then
  echo "  ✗ live BFF unreachable — using static fallback at $FALLBACK"
  base="$FALLBACK"
else
  echo "  ✗ live BFF unreachable AND no fallback at $FALLBACK"
  echo "  writing minimal stub spec so downstream builds keep working"
  cat > "$OUT/openapi.stub.json" <<'STUB'
{ "openapi": "3.0.3",
  "info": { "title": "Snapdragon Gaming BFF", "version": "0.1.0",
            "description": "Stub spec — bring the BFF up and re-run `make docs-openapi-spec` to refresh." },
  "paths": {} }
STUB
  base="$OUT/openapi.stub.json"
fi

echo "→ merging $base + $META → $PUBLISHED"
"$PYTHON" "$HERE/merge-metadata.py" \
  --spec "$base" \
  --metadata "$META" \
  --out "$PUBLISHED"
echo "  ✓ merged spec at $PUBLISHED"
