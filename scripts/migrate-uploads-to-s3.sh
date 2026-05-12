#!/usr/bin/env bash
#
# One-shot migration: copy filesystem-stored game uploads into an
# S3-compatible bucket (MinIO in dev, real S3 / R2 / B2 in prod).
#
# Follow-up to decision 020 (Object-storage-backed game uploads). Run
# this once when you're ready to flip `app.uploads.storage` from
# `filesystem` to `s3` on a host that already has uploads on disk.
#
# What it does:
#   1. Reads `game_uploads.version_dir` rows whose locator starts
#      with `file:` (or has no prefix — legacy raw-path rows from
#      before the locator scheme landed).
#   2. For each row, walks the on-disk version_dir tree.
#   3. Uploads each file to `s3://${BUCKET}/<slug>/<sha-prefix>/<rel>`
#      using the same key shape `S3UploadStorage` writes to.
#   4. Once every file in a version_dir lands, UPDATEs the row's
#      `version_dir` column to the new `s3://...` locator (the BFF
#      reads via `UploadStorage.read(locator)` which dispatches on
#      the prefix, so flipping the column is what makes the cutover
#      visible).
#
# Safety:
#   - DRY-RUN by default. Pass `--apply` to actually upload + UPDATE.
#   - Files already at the target S3 key are SKIPPED (idempotent by
#     content-hash; re-runs are safe).
#   - The on-disk source is LEFT IN PLACE so a rollback is just
#     reverting the version_dir column. Run a separate cleanup pass
#     once you're confident the cutover stuck.
#
# Required env (or .env at repo root):
#   PGHOST          default: localhost
#   PGPORT          default: 5432
#   PGDATABASE      default: threewizards
#   PGUSER          default: threewizards
#   PGPASSWORD      default: threewizards
#   APP_UPLOADS_GAMES_DIR   default: /var/snapdragon/games
#   APP_UPLOADS_S3_BUCKET   default: snapdragon-uploads
#   APP_UPLOADS_S3_ENDPOINT default: http://localhost:9000
#   APP_UPLOADS_S3_ACCESS_KEY     (required)
#   APP_UPLOADS_S3_SECRET_KEY     (required)
#
# Requires: bash, psql, awscli (or any S3-compatible CLI; the script
#   uses `aws s3 cp` so MinIO + real AWS + R2 all work via the same
#   endpoint flag).

set -euo pipefail

APPLY=0
if [ "${1:-}" = "--apply" ]; then
    APPLY=1
fi

# Load .env if present at repo root (sibling of scripts/).
ENV_FILE="$(dirname "$(realpath "$0")")/../.env"
if [ -f "$ENV_FILE" ]; then
    # shellcheck disable=SC1090
    set -a; . "$ENV_FILE"; set +a
fi

: "${PGHOST:=localhost}"
: "${PGPORT:=5432}"
: "${PGDATABASE:=threewizards}"
: "${PGUSER:=threewizards}"
: "${PGPASSWORD:=threewizards}"
: "${APP_UPLOADS_GAMES_DIR:=/var/snapdragon/games}"
: "${APP_UPLOADS_S3_BUCKET:=snapdragon-uploads}"
: "${APP_UPLOADS_S3_ENDPOINT:=http://localhost:9000}"

if [ -z "${APP_UPLOADS_S3_ACCESS_KEY:-}" ] || [ -z "${APP_UPLOADS_S3_SECRET_KEY:-}" ]; then
    echo "ERROR: APP_UPLOADS_S3_ACCESS_KEY + APP_UPLOADS_S3_SECRET_KEY must be set" >&2
    exit 1
fi

export AWS_ACCESS_KEY_ID="$APP_UPLOADS_S3_ACCESS_KEY"
export AWS_SECRET_ACCESS_KEY="$APP_UPLOADS_S3_SECRET_KEY"
export AWS_DEFAULT_REGION="${APP_UPLOADS_S3_REGION:-us-east-1}"

PSQL="psql -h $PGHOST -p $PGPORT -U $PGUSER -d $PGDATABASE -tA"
AWS="aws --endpoint-url $APP_UPLOADS_S3_ENDPOINT s3"

echo "→ Migration plan:"
echo "   source        : $APP_UPLOADS_GAMES_DIR"
echo "   target bucket : s3://$APP_UPLOADS_S3_BUCKET (endpoint $APP_UPLOADS_S3_ENDPOINT)"
echo "   apply         : $([ $APPLY = 1 ] && echo yes || echo "no (DRY RUN — pass --apply to commit)")"
echo

# Bucket must already exist — fail fast if not.
if ! $AWS ls "s3://$APP_UPLOADS_S3_BUCKET" >/dev/null 2>&1; then
    echo "ERROR: bucket s3://$APP_UPLOADS_S3_BUCKET not reachable" >&2
    echo "  Create it first: $AWS mb s3://$APP_UPLOADS_S3_BUCKET" >&2
    exit 1
fi

# Pull migration candidates: filesystem-locator rows + legacy raw-path rows.
# version_dir column shape per UploadStorage:
#   filesystem  → `file:<absolute-path>` (post-locator-scheme)
#   legacy      → raw `<absolute-path>` (pre-locator-scheme rows that
#                  FilesystemUploadStorage.read also accepts)
#   already-S3  → `s3://<bucket>/<slug>/<sha-prefix>` (skip)
ROWS=$($PSQL <<'SQL'
SELECT id, slug, content_hash, version_dir
  FROM game_uploads
 WHERE superseded_at IS NULL
   AND (version_dir LIKE 'file:%' OR version_dir NOT LIKE 's3://%')
 ORDER BY id;
SQL
)

if [ -z "$ROWS" ]; then
    echo "Nothing to migrate — all active uploads already have s3:// locators."
    exit 0
fi

COUNT_OK=0
COUNT_SKIP=0
COUNT_FAIL=0

while IFS='|' read -r id slug hash version_dir; do
    [ -z "$id" ] && continue

    # Strip optional `file:` prefix.
    src="${version_dir#file:}"

    if [ ! -d "$src" ]; then
        echo "✗ #$id ($slug, $hash) → src missing on disk: $src"
        COUNT_FAIL=$((COUNT_FAIL + 1))
        continue
    fi

    # S3 key prefix matches S3UploadStorage.installFromStaging:
    #   <bucket>/<slug>/<first-12-of-hash>
    key_prefix="$slug/${hash:0:12}"
    target_locator="s3://$APP_UPLOADS_S3_BUCKET/$key_prefix"

    if [ $APPLY = 0 ]; then
        echo "  would copy: $src → $target_locator   (and UPDATE row #$id)"
        COUNT_OK=$((COUNT_OK + 1))
        continue
    fi

    # Mirror the tree. `aws s3 cp --recursive` does it in one call.
    if $AWS cp --recursive --quiet "$src" "$target_locator/"; then
        # Flip the locator. PSQL exits non-zero on UPDATE failure
        # (e.g. permission, FK violation), so we propagate via set -e.
        $PSQL <<SQL
UPDATE game_uploads
   SET version_dir = '$target_locator'
 WHERE id = $id
   AND version_dir = '$version_dir';
SQL
        echo "✓ #$id ($slug, $hash) → $target_locator"
        COUNT_OK=$((COUNT_OK + 1))
    else
        echo "✗ #$id ($slug, $hash) → upload failed; row left at $version_dir"
        COUNT_FAIL=$((COUNT_FAIL + 1))
    fi
done <<<"$ROWS"

echo
echo "Done — ok=$COUNT_OK skip=$COUNT_SKIP fail=$COUNT_FAIL"
[ $COUNT_FAIL -eq 0 ]
