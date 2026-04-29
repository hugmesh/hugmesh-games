#!/usr/bin/env python3
"""
Harvest per-step screenshots produced by ``make e2e-bdd`` and copy them
into ``docs/publishing/screenshots/build/`` under the names the user
guides + slide decks reference.

This is the "real-screenshots" alternative to ``capture.py``'s
Selenium-driven capture: instead of pointing a browser at a live dev
stack, we re-use the artefacts the BDD test suite already produces in
``e2e/results/artifacts/`` (one PNG per step per scenario, attached to
the HTML report by the AfterStep hook in
``e2e/features/support/fixtures.ts``).

Why this path beats ``capture.py``:

* ``make e2e-bdd`` brings up the deterministic-password integration-
  test stack (``E2E_PASSWORD=e2e-pass``) so EVERY seed user — admin,
  player, mod-three-wizards, publisher-dodecahedron, the lot — logs
  in successfully. The dev-stack flow used by ``capture.py`` only
  has admin + player passwords surfaced in ``.env``; mod / publisher
  variants fall through to placeholder PNGs.
* The BDD AfterStep hook already produces 124+ screenshots per run.
  We just pick the right ones rather than driving a separate Selenium
  pass.
* The screenshots ship with deterministic content (the integration-
  test catalog seed), so a slide deck doesn't change cosmetically when
  a contributor's local DB has uncommitted dev rows.

Run:
    python3 docs/publishing/screenshots/harvest-from-e2e.py

Expects ``e2e/results/artifacts/`` to be populated by a recent
``make e2e-bdd`` run. Exits non-zero if no per-role artefacts are
present so a stale make-graph build fails loudly instead of silently
landing placeholders in the user guides.
"""

from __future__ import annotations

import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent.parent
ART  = ROOT / "e2e" / "results" / "artifacts"
DEST = ROOT / "docs" / "publishing" / "screenshots" / "build"


def find_per_role_dirs() -> dict[str, Path]:
    """Walk the BDD per-role-login artefact dirs and index by username.

    Each per-role-login scenario produces a directory whose name is
    hashed (``features-auth-per-role-log-{hash}-...-bdd``); inside,
    the ``02--{user}-logs-in.png`` filename carries the username we
    need to recover the mapping.
    """
    found: dict[str, Path] = {}
    for d in ART.glob("features-auth-per-role-log-*"):
        if not d.is_dir():
            continue
        when_pngs = list(d.glob("02--*-logs-in.png"))
        if not when_pngs:
            continue
        m = re.match(r"02--([a-z0-9-]+)-logs-in\.png", when_pngs[0].name)
        if m:
            found[m.group(1)] = d
    return found


def main() -> int:
    if not ART.exists():
        print(f"✗ no artefacts dir: {ART}", file=sys.stderr)
        print("  run `make e2e-bdd` first to produce them.", file=sys.stderr)
        return 1

    per_role = find_per_role_dirs()
    if not per_role:
        print(f"✗ no per-role-login BDD artefacts under {ART}", file=sys.stderr)
        print("  the e2e-bdd run may have aborted before producing PNGs.", file=sys.stderr)
        return 1

    print(f"→ harvesting BDD screenshots; found {len(per_role)} per-role artefact dirs:")
    for u in sorted(per_role):
        print(f"    {u}")

    # Helpers — file-name patterns inside each scenario's artefact dir.
    def then_png(d: Path)  -> Path: return d / "03-the-catalog-renders-at-least-one-game-card.png"
    def given_png(d: Path) -> Path: return d / "01-a-fresh-browser-context.png"

    # Map docs/publishing/screenshots/build/<name>.png → BDD source.
    # Some doc filenames are aliases for the same screenshot (e.g.
    # `home-page` and `home-after-login` both want the post-login
    # catalog view); reuse the same source so the slides stay
    # internally consistent.
    mappings: dict[str, Path] = {}
    if "player" in per_role:
        p = per_role["player"]
        for name in ("home-page", "home-after-login", "tour-02-home",
                     "player-home", "player-session"):
            mappings[name + ".png"] = then_png(p)
        # Game-canvas screenshots (cave / dodec) aren't directly
        # captured by BDD — the per-role-login scenarios stop at the
        # catalog. Fall back to the player's catalog view, which is
        # better than a placeholder and shows the right host shell.
        for name in ("tour-03-game-cave", "tour-04-game-dodec",
                     "game-cave", "game-dodecahedron"):
            mappings[name + ".png"] = then_png(p)
    if "admin" in per_role:
        a = per_role["admin"]
        for name in ("admin-dashboard", "admin-01-dashboard",
                     "admin-uploads", "admin-02-uploads",
                     "admin-docs", "admin-03-docs"):
            mappings[name + ".png"] = then_png(a)
    if "mod-three-wizards" in per_role:
        m = per_role["mod-three-wizards"]
        for name in ("mod-console", "mod-home"):
            mappings[name + ".png"] = then_png(m)
    if "publisher-three-wizards" in per_role:
        pub = per_role["publisher-three-wizards"]
        for name in ("publisher-console", "publisher-home"):
            mappings[name + ".png"] = then_png(pub)

    # Login form: any per-role artefact's pre-login Given step.
    if per_role:
        anchor = next(iter(per_role.values()))
        mappings["login-form.png"]   = given_png(anchor)
        mappings["tour-01-login.png"] = given_png(anchor)

    DEST.mkdir(parents=True, exist_ok=True)
    copied = missing = 0
    for name, src in sorted(mappings.items()):
        if not src.exists():
            print(f"  ✗ source missing: {src.relative_to(ROOT)}")
            missing += 1
            continue
        shutil.copy2(src, DEST / name)
        copied += 1
        print(f"  ✓ {name:<26} ← {src.relative_to(ROOT)}")

    print(f"\n→ copied {copied} screenshot(s) into {DEST.relative_to(ROOT)}")
    if missing:
        print(f"⚠ {missing} source PNG(s) missing — those guide pages will keep their stale image.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
