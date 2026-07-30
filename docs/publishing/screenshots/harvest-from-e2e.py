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
``make e2e-bdd`` (or ``make e2e-godot``) run. Missing artefact GROUPS
are warned about and skipped — a ``--grep @godot`` biome-only run has
no per-role-login dirs and must still harvest its own videos/PNGs —
but a completely absent artefacts dir still fails loudly.
"""

from __future__ import annotations

import re
import shutil
import sys
from pathlib import Path

ROOT   = Path(__file__).resolve().parent.parent.parent.parent
ART    = ROOT / "e2e" / "results" / "artifacts"
DEST   = ROOT / "docs" / "publishing" / "screenshots" / "build"
VIDEOS = ROOT / "docs" / "publishing" / "videos"

BIOME_THEMES = ("desert", "forest", "dungeon", "cave", "town", "castle")


def _retry_no(d: Path) -> int:
    """-retryN suffix of an artefact dir (-1 for the first attempt)."""
    m = re.search(r"-retry(\d+)$", d.name)
    return int(m.group(1)) if m else -1


def _scenario_base(d: Path) -> str:
    """Artefact dir name with any -retryN suffix stripped."""
    return re.sub(r"-retry\d+$", "", d.name)


def prefer_last_attempt(dirs: list[Path]) -> list[Path]:
    """Collapse retried scenarios to their LAST attempt.

    Playwright writes each retry into a sibling ``…-retryN`` dir; the
    last attempt is the one that (usually) passed, so its PNGs/video
    are the evidence worth shipping.
    """
    by_base: dict[str, Path] = {}
    for d in dirs:
        base = _scenario_base(d)
        cur = by_base.get(base)
        if cur is None or _retry_no(d) > _retry_no(cur):
            by_base[base] = d
    return [by_base[k] for k in sorted(by_base)]


def find_per_role_dirs() -> dict[str, Path]:
    """Walk the BDD per-role-login artefact dirs and index by username.

    Each per-role-login scenario produces a directory whose name is
    hashed (``features-auth-per-role-log-{hash}-...-bdd``); inside,
    the ``02--{user}-logs-in.png`` filename carries the username we
    need to recover the mapping. Retried scenarios collapse to their
    last attempt.
    """
    found: dict[str, Path] = {}
    candidates = prefer_last_attempt(
        [d for d in ART.glob("features-auth-per-role-log-*") if d.is_dir()])
    for d in candidates:
        when_pngs = list(d.glob("02--*-logs-in.png"))
        if not when_pngs:
            continue
        m = re.match(r"02--([a-z0-9-]+)-logs-in\.png", when_pngs[0].name)
        if m:
            found[m.group(1)] = d
    return found


def harvest_biome_artifacts() -> tuple[int, int]:
    """Copy the biome-worlds (@godot) scenario evidence.

    * every ``features-makers-biome-*`` scenario dir's webm video(s) →
      ``docs/publishing/videos/<scenario-slug>.webm`` (gitignored —
      review evidence, NOT a LaTeX build input);
    * each explore scenario's render-assertion step PNG →
      ``docs/publishing/screenshots/build/biome-<theme>.png``.

    Returns (copied, missing) counts. A run without biome scenarios
    (plain e2e-bdd) is a clean no-op.
    """
    copied = missing = 0
    dirs = prefer_last_attempt(
        [d for d in ART.glob("features-makers-biome-*") if d.is_dir()])
    if not dirs:
        print("→ no biome-worlds artefact dirs — skipping video/biome harvest")
        return (0, 0)

    VIDEOS.mkdir(parents=True, exist_ok=True)
    for d in dirs:
        base = _scenario_base(d)
        # Fixture-context scenarios record `video.webm`; manually
        # created contexts (two-player) drop `<guid>.webm` files into
        # the same outputDir — copy them all, suffixing extras.
        webms = sorted(d.glob("*.webm"))
        if not webms:
            print(f"  ✗ no video in {d.relative_to(ROOT)}")
            missing += 1
        for i, v in enumerate(webms):
            suffix = "" if i == 0 else f"-{i}"
            target = VIDEOS / f"{base}{suffix}.webm"
            shutil.copy2(v, target)
            copied += 1
            print(f"  ✓ {target.relative_to(ROOT)} ← {v.relative_to(ROOT)}")

    # Key explore-step PNGs: the AfterStep shot of the render
    # assertion (`the "<theme>" realm renders a live 3D world`).
    DEST.mkdir(parents=True, exist_ok=True)
    for theme in BIOME_THEMES:
        shot = None
        for d in dirs:
            hits = sorted(d.glob(f"*-the--{theme}--realm-renders*.png"))
            if hits:
                shot = hits[-1]
                break
        if shot is None:
            print(f"  ✗ no render-step PNG for biome '{theme}'")
            missing += 1
            continue
        shutil.copy2(shot, DEST / f"biome-{theme}.png")
        copied += 1
        print(f"  ✓ biome-{theme}.png ← {shot.relative_to(ROOT)}")
    return (copied, missing)


def main() -> int:
    if not ART.exists():
        print(f"✗ no artefacts dir: {ART}", file=sys.stderr)
        print("  run `make e2e-bdd` first to produce them.", file=sys.stderr)
        return 1

    per_role = find_per_role_dirs()
    if not per_role:
        # Not fatal: a scoped run (e.g. `make e2e-godot`, which greps
        # for @godot) produces no per-role-login artefacts but still
        # has biome videos/PNGs worth harvesting below.
        print(f"⚠ no per-role-login BDD artefacts under {ART} — "
              "role screenshots keep their previous versions.", file=sys.stderr)
    else:
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

    print("\n→ harvesting biome-worlds (@godot) videos + screenshots…")
    b_copied, b_missing = harvest_biome_artifacts()
    if b_copied:
        print(f"→ copied {b_copied} biome artefact(s)"
              f" ({VIDEOS.relative_to(ROOT)} + {DEST.relative_to(ROOT)})")
    if b_missing:
        print(f"⚠ {b_missing} biome artefact(s) missing — re-run `make e2e-godot` for fresh evidence.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
