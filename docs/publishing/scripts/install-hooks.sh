#!/usr/bin/env bash
# HugMesh Games — git hook installer for the publishing pipeline.
#
# Installs a pre-push hook that re-runs `make docs-pdf` whenever the push
# touches docs/publishing/ or docs/PLAN-module-federation-and-native.md.
# Failures abort the push so a broken LaTeX build can't land on main.
#
# Idempotent: re-running prints what's already in place. Safe to source
# from CI bootstrap.

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
HOOK_PATH="$REPO_ROOT/.git/hooks/pre-push"

if [[ -e "$HOOK_PATH" ]]; then
  if grep -q "hugmesh-publishing" "$HOOK_PATH"; then
    echo "[publishing] pre-push hook already installed at $HOOK_PATH"
    exit 0
  fi
  echo "[publishing] WARNING: $HOOK_PATH exists and isn't ours — leaving alone"
  echo "[publishing]          merge the contents of:"
  echo "[publishing]          docs/publishing/scripts/pre-push.sample"
  echo "[publishing]          into the existing hook by hand"
  exit 1
fi

cat > "$HOOK_PATH" <<'EOF'
#!/usr/bin/env bash
# hugmesh-publishing pre-push hook (installed by docs/publishing/scripts/install-hooks.sh)
#
# Rebuilds the architecture PDF whenever the push touches publishing
# sources. Aborts the push on a non-zero build exit.

set -euo pipefail
REPO_ROOT="$(git rev-parse --show-toplevel)"
WATCHED='docs/publishing/|docs/PLAN-module-federation-and-native\.md'

# git-pre-push receives <local-ref> <local-sha> <remote-ref> <remote-sha> on stdin.
trigger=0
while read -r local_ref local_sha remote_ref remote_sha; do
  [[ -z "$local_sha" || "$local_sha" =~ ^0+$ ]] && continue
  if [[ "$remote_sha" =~ ^0+$ ]]; then
    range="$local_sha"
  else
    range="$remote_sha..$local_sha"
  fi
  if git diff --name-only "$range" 2>/dev/null | grep -E "$WATCHED" >/dev/null; then
    trigger=1
    break
  fi
done

if [[ "$trigger" -eq 1 ]]; then
  echo "[publishing] push touches publishing sources — running 'make docs-pdf'…"
  ( cd "$REPO_ROOT" && make docs-pdf >/dev/null )
  echo "[publishing] PDF builds clean. Proceeding with push."
fi
EOF
chmod +x "$HOOK_PATH"

# Sample for the manual-merge fallback above.
SAMPLE_PATH="$REPO_ROOT/docs/publishing/scripts/pre-push.sample"
cp "$HOOK_PATH" "$SAMPLE_PATH"

echo "[publishing] installed pre-push hook at $HOOK_PATH"
echo "[publishing] sample also written to $SAMPLE_PATH (for manual merge if you already have a hook)"
