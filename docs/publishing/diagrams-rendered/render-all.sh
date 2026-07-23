#!/usr/bin/env bash
# Render every TikZ diagram + marble diagram in the architecture/
# tree as a standalone PNG so python-pptx (slides) and the user-guide
# LaTeX builds can embed them as images.
#
# Each .tex source under architecture/diagrams/ and architecture/marbles/
# is wrapped in a minimal standalone document that imports the project
# theme, compiled with pdflatex into a single-page PDF, and converted
# to PNG via pdftoppm at 220 DPI (sharp on slide projectors and good
# for print at 1/2 letter size).
#
# Output goes to docs/publishing/diagrams-rendered/build/{<stem>.png}
# and is git-ignored.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
ARCH="$ROOT/docs/publishing/architecture"
OUT="$ROOT/docs/publishing/diagrams-rendered/build"
TMP="$(mktemp -d -t snap-diagrams-XXXXXX)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$OUT"

# Each rendered figure gets a tiny standalone wrapper that loads the
# HugMesh theme so colours, marble shapes, and \snapdiagram all work
# the same as in the architecture PDF.
WRAPPER() {
  # Standalone class + hugmesh-theme. We deliberately DO NOT load
  # hyperref — it's incompatible with standalone-class quirks and the
  # PNG render doesn't need clickable links anyway. The theme gates its
  # \hypersetup call on hyperref being loaded so this is a clean skip.
  cat <<EOF
\documentclass[border=4pt,varwidth=176mm]{standalone}
\usepackage{hugmesh-theme}
\begin{document}
\input{$1}
\end{document}
EOF
}

render_one() {
  local src="$1"
  local stem
  stem="$(basename "$src" .tex)"
  local job="$TMP/$stem"
  mkdir -p "$job"
  cp "$ARCH/hugmesh-theme.sty" "$job/"
  WRAPPER "$src" > "$job/$stem.tex"
  echo "  $stem"
  ( cd "$job" && pdflatex -halt-on-error -interaction=nonstopmode \
      "$stem.tex" >/dev/null 2>&1 ) || {
    echo "    ERROR: pdflatex failed for $src" >&2
    return 1
  }
  pdftoppm -png -r 220 "$job/$stem.pdf" "$OUT/$stem" >/dev/null 2>&1
  # pdftoppm writes <stem>-1.png for single-page input; rename for tidiness.
  if [[ -f "$OUT/${stem}-1.png" ]]; then
    mv "$OUT/${stem}-1.png" "$OUT/${stem}.png"
  fi
}

echo "→ rendering architecture/diagrams/*.tex"
for f in "$ARCH/diagrams"/*.tex; do
  render_one "$f"
done

echo "→ rendering architecture/marbles/*.tex"
for f in "$ARCH/marbles"/*.tex; do
  render_one "$f"
done

count=$(find "$OUT" -maxdepth 1 -name '*.png' | wc -l)
echo "✓ rendered $count PNG(s) → $OUT/"
