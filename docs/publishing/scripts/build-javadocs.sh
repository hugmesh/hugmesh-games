#!/usr/bin/env bash
# Convenience wrapper for `make -C docs/publishing javadocs`.
set -euo pipefail
REPO_ROOT="$(git rev-parse --show-toplevel)"
exec make -C "$REPO_ROOT/docs/publishing" javadocs "$@"
