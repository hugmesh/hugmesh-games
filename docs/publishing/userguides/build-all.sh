#!/usr/bin/env bash
# Build all six HugMesh Games user guide PDFs.
#
# Each guide lives in its own subdirectory with a guide.tex entry point.
# We add the architecture/ tree to TEXINPUTS so the shared theme +
# diagrams + marbles + rendered PNGs are findable without copies.
#
# Output: docs/publishing/userguides/<name>/build/<name>.pdf, which is
# git-ignored. Errors abort the whole batch so a broken guide doesn't
# silently ship.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
ARCH="$ROOT/docs/publishing/architecture"
DIAGRAMS="$ROOT/docs/publishing/diagrams-rendered/build"
SCREENSHOTS="$ROOT/docs/publishing/screenshots/build"
SHARED="$ROOT/docs/publishing/userguides/_shared"
USERGUIDES="$ROOT/docs/publishing/userguides"

# texinputs picks up: per-guide files, the shared preamble, the theme
# package, the architecture diagrams + marbles. Trailing `:` extends
# rather than replaces the system path.
export TEXINPUTS=".:$SHARED:$ARCH:$ARCH/diagrams:$ARCH/marbles:$DIAGRAMS:$SCREENSHOTS:"

GUIDES=(
  intro-for-coders
  administration
  godot-developer
  fullstack-developer
  maintainer-runbook
  data-architect-overview
  moderator
  publisher
)

for g in "${GUIDES[@]}"; do
  dir="$USERGUIDES/$g"
  if [[ ! -f "$dir/guide.tex" ]]; then
    echo "SKIP $g — $dir/guide.tex missing"
    continue
  fi
  out="$dir/build"
  mkdir -p "$out"
  echo "→ $g"
  ( cd "$dir" \
    && pdflatex -halt-on-error -interaction=nonstopmode \
        -output-directory="$out" guide.tex >/dev/null \
    && pdflatex -halt-on-error -interaction=nonstopmode \
        -output-directory="$out" guide.tex >/dev/null \
    && mv "$out/guide.pdf" "$out/$g.pdf" \
  ) || {
    echo "  ERROR: pdflatex failed for $g — see $out/guide.log"
    exit 1
  }
  echo "  ✓ $out/$g.pdf"
done
