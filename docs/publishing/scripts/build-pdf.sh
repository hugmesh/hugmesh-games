#!/usr/bin/env bash
# Convenience wrapper for `make -C docs/publishing pdf`. The Make target
# is the source of truth; this script is for editors and CI runners that
# call build scripts by path rather than by Make target.

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
exec make -C "$REPO_ROOT/docs/publishing" pdf "$@"
