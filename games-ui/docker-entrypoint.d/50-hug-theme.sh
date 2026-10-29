#!/bin/sh
# 50-hug-theme.sh — runtime login THEME injection (sibling of 40-snap-config.sh).
#
# Writes hug-theme.css AND snap-theme.css (see DUAL SERVE below) from the theme
# env at container start; index.html <link>s one of them, and its
# ":root:root { --hug-*: ... }" block beats the compiled :root fallbacks in
# styles.css by specificity. Rebrand = update the env (ConfigMap) + restart the
# pod — NO image rebuild.
#
# ZERO colour maths happens here — deliberately. The theme env carries token
# text ALREADY derived by the single implementation (hugmesh-generator
# studio/hugmesh_studio/core/theme.py, via the Studio GUI or
# scripts/tooling/derive-theme.py). A shell re-derivation would be a second
# implementation whose drift makes the Studio preview lie, so this script only
# writes the text verbatim.
#
# Unset/empty => EMPTY stylesheets: the <link> resolves with zero bytes and the
# compiled :root literals render the shipped default brand, byte-identically.
set -eu

# ── RENAME PHASE 3: IN-IMAGE NAMES FLIPPED (dual read + dual serve KEPT) ──────
# The fleet is renaming SNAP_* -> HUG_*. This contract crosses a PROCESS
# boundary (compose / the cluster ConfigMap sets the env; this container reads
# it), so the two halves cannot flip in one commit. A one-sided flip is the
# quietest failure in the fleet: the var this hook reads is unset, an EMPTY
# stylesheet is written, the <link> resolves 200 with zero bytes, nginx logs
# nothing, the container exits 0 and the healthcheck passes — the page just
# silently wears the shipped brand.
#
# So this side accepts BOTH spellings, new name winning when set:
#   HUG_THEME_CSS  else SNAP_THEME_CSS  -> the token text (verbatim)
#   HUG_THEME_OUT  else SNAP_THEME_OUT  -> where to write it
# and it writes the SAME bytes under BOTH filenames (hug-theme.css AND
# snap-theme.css), so an index.html from either era resolves.
#
# WHAT PHASE 3 CHANGED, and why it is safe where the env flip is not: the
# FILE name and the URL are settled entirely INSIDE this image — this hook
# writes it, this image's nginx.conf serves it, this image's index.html asks
# for it, this image's Dockerfile COPYs the hook in by name. All four move in
# one commit and ship as one layer, so there is no window in which half the
# contract is renamed. The *_THEME_CSS env var is the opposite shape (a
# producer outside the image sets it) and is deliberately NOT touched here.
#
# The dual read above therefore stays exactly as it was. hug-theme.css is now
# the PRIMARY output and snap-theme.css the legacy twin; nginx serves both.

OUT="${HUG_THEME_OUT:-${SNAP_THEME_OUT:-/usr/share/nginx/html/hug-theme.css}}"
CSS="${HUG_THEME_CSS:-${SNAP_THEME_CSS:-}}"

if [ -n "${HUG_THEME_CSS:-}" ]; then
  FROM='$HUG_THEME_CSS'
elif [ -n "${SNAP_THEME_CSS:-}" ]; then
  FROM='$SNAP_THEME_CSS'
else
  FROM='no theme env'
fi

printf '%s' "$CSS" > "$OUT"

# The twin filename, in the same directory, derived rather than hard-coded so an
# operator-supplied *_THEME_OUT keeps both names in step. Pure parameter
# expansion: no dirname/basename dependency.
case "$OUT" in
  */*) OUT_DIR="${OUT%/*}" ;;
  *)   OUT_DIR="." ;;
esac
OUT_BASE="${OUT##*/}"
case "$OUT_BASE" in
  snap-*) TWIN="$OUT_DIR/hug-${OUT_BASE#snap-}" ;;
  hug-*)  TWIN="$OUT_DIR/snap-${OUT_BASE#hug-}" ;;
  *)      TWIN="" ;;
esac
if [ -n "$TWIN" ] && [ "$TWIN" != "$OUT" ]; then
  printf '%s' "$CSS" > "$TWIN"
fi

if [ -s "$OUT" ]; then
  echo "50-hug-theme.sh: wrote $OUT${TWIN:+ + $TWIN} ($(wc -c < "$OUT") bytes, from $FROM)"
else
  echo "50-hug-theme.sh: wrote empty $OUT${TWIN:+ + $TWIN} ($FROM — shipped default brand)"
fi
