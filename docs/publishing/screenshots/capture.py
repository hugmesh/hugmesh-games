"""
Snapdragon Games — UI screenshot capture for documentation.

Drives a headless Firefox via Selenium against a running compose stack
(or any base URL). Each capture is a (path, label, settle_ms, action?)
tuple; the script visits the path, waits for the page to settle, runs
the optional pre-shot action (login, click, etc.), and writes a PNG to
docs/publishing/screenshots/build/<label>.png.

If the base URL is unreachable, every shot is replaced with a branded
placeholder PNG so the slide builds and user-guide PDF builds still
succeed when run on a fresh checkout without docker compose up. The
placeholder names match the live names so callers don't need to
distinguish.

Usage:
    python3 docs/publishing/screenshots/capture.py
    SNAPDRAGON_BASE_URL=http://localhost:8080 \\
        python3 docs/publishing/screenshots/capture.py

Why Selenium (and not Playwright):
- The repo's e2e suite uses Playwright via Docker, but our publishing
  pipeline already runs in a Python venv (python-pptx, selenium). One
  language, one venv, one dependency surface.
- Selenium 4 ships its own webdriver auto-installer (selenium-manager)
  so this script needs no separate `pip install webdriver-manager`.
- Firefox is on most Linux distros by default; Chrome is not. Falling
  back to Chrome if geckodriver fails covers macOS users.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional

from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC


# ── Constants ─────────────────────────────────────────────────────────────
SCREENSHOTS_DIR = Path(__file__).resolve().parent / "build"
WINDOW_SIZE     = (1366, 800)

# Login credentials. Read from env so secrets stay out of git; defaults
# match the Casdoor seed so a vanilla `docker compose up -d` works
# without extra config.
#
# Per-role overrides: SNAPDRAGON_{ROLE}_USER / SNAPDRAGON_{ROLE}_PASS
# where ROLE is PLAYER, MOD, PUBLISHER, ADMIN (upper-case). Falls back
# to SNAPDRAGON_TEST_USER / SNAPDRAGON_TEST_PASS, then to the constants
# below.
SHARED_PASS   = os.environ.get("SNAPDRAGON_TEST_PASS", "123")
ROLE_CREDS: dict[str, tuple[str, str]] = {
    "admin":     (os.environ.get("SNAPDRAGON_ADMIN_USER",     "admin"),
                  os.environ.get("SNAPDRAGON_ADMIN_PASS",     SHARED_PASS)),
    "player":    (os.environ.get("SNAPDRAGON_PLAYER_USER",    "player"),
                  os.environ.get("SNAPDRAGON_PLAYER_PASS",    SHARED_PASS)),
    "mod":       (os.environ.get("SNAPDRAGON_MOD_USER",       "mod-three-wizards"),
                  os.environ.get("SNAPDRAGON_MOD_PASS",       SHARED_PASS)),
    "publisher": (os.environ.get("SNAPDRAGON_PUBLISHER_USER", "publisher-three-wizards"),
                  os.environ.get("SNAPDRAGON_PUBLISHER_PASS", SHARED_PASS)),
}
# Legacy env kept for backwards compat — used as fallback if SNAPDRAGON_ADMIN_USER unset.
DEFAULT_USER = os.environ.get("SNAPDRAGON_TEST_USER", ROLE_CREDS["admin"][0])
DEFAULT_PASS = SHARED_PASS


# ── Capture spec ──────────────────────────────────────────────────────────
@dataclass
class Capture:
    label: str            # output filename stem (no .png)
    path:  str            # URL path relative to BASE_URL
    settle_ms: int = 1500
    pre_action: Optional[Callable] = None
    description: str = ""
    requires_auth: bool = True   # default — most pages are gated
    # Which ROLE_CREDS key to use when logging in for this capture.
    # None = use DEFAULT_USER / DEFAULT_PASS (admin) for the shared pass 2.
    login_as: Optional[str] = None


# Captures split in two passes:
#   - login-page (no auth) — captured first.
#   - everything else      — captured AFTER login_via_api() has
#                             written sessionStorage.tw_auth so the
#                             host's authGuard lets the route mount.
#
# `requires_auth=False` means the capture should run BEFORE login so
# we get the un-authenticated UI (the login form itself).
# Step-by-step page tours used as walkthrough screenshots inside the
# slide decks and user-guide PDFs. Names are stable — slide and guide
# templates reference them as `screenshot:<label>`.
#
# We group captures into two flavours:
#   • "tour-*"   — short, ordered click-through of the public surface
#                  used by the intro-for-coders + Godot-developer decks.
#   • "admin-*"  — admin-UI walkthrough used by the administration +
#                  maintainer decks. All require ROLE_ADMIN; the
#                  default Casdoor seed `admin / 123` has it.
#
# Plain `home-page`, `login-form`, etc. stay under their original
# names so existing slide refs don't break.
CAPTURES: list[Capture] = [
    # ── un-auth captures (pass 1) ────────────────────────────────────
    Capture("login-form",          "/login",            1200,
            requires_auth=False,
            description="Federated login remote — Casdoor proxy form."),
    Capture("tour-01-login",       "/login",            1200,
            requires_auth=False,
            description="Step 1 — Land on the login form. Username "
                        "+ password go to /api/auth/token; the BFF "
                        "proxies Casdoor."),

    # ── auth captures (pass 2 — after login_via_api) ────────────────
    Capture("home-page",           "/",                 1500,
            description="Game catalog landing page (federated host shell)."),
    Capture("home-after-login",    "/",                 2000,
            description="Home page once a session token is in sessionStorage."),
    Capture("tour-02-home",        "/",                 2000,
            description="Step 2 — Game catalog. Each card is a federated "
                        "remote loaded on click."),
    Capture("tour-03-game-cave",   "/?level=1",         3500,
            description="Step 3 — Click a level: the federated "
                        "three-wizards remote mounts and the iframe "
                        "loads the cave Godot bundle. Floating "
                        "fullscreen / new-tab toolbar visible top-right."),
    Capture("tour-04-game-dodec",  "/?level=99",        3500,
            description="Step 4 — Same flow for dodecahedron-demo. "
                        "Different remote, same toolbar — every game "
                        "ships with fullscreen + new-tab + new-window "
                        "controls."),

    # ── admin walkthrough ───────────────────────────────────────────
    Capture("admin-dashboard",     "/admin",            2500,
            description="Admin dashboard (federated admin remote)."),
    Capture("admin-uploads",       "/admin/games",      2500,
            description="Hot-upload + rollback UI for game bundles."),
    Capture("admin-docs",          "/admin/docs",       2500,
            description="API docs explorer (the api-docs federated remote)."),
    Capture("admin-01-dashboard",  "/admin",            2500,
            description="Step 1 — /admin landing. Per-user stats, "
                        "active sessions, recent uploads."),
    Capture("admin-02-uploads",    "/admin/games",      2500,
            description="Step 2 — /admin/games. Drop a Snapdragon "
                        "Export zip into the upload card; the BFF "
                        "validates manifest schema v2, hashes, "
                        "atomically moves into <gamesDir>/<slug>/<sha>, "
                        "runs register.sql in one R2DBC tx."),
    Capture("admin-03-docs",       "/admin/docs",       2500,
            description="Step 3 — /admin/docs. Navigator-style API "
                        "explorer rendered from the merged OpenAPI "
                        "spec; auth-token override + binary uploads + "
                        "live SSE terminal in the same UI."),

    # ── original game captures (still referenced) ──────────────────
    Capture("game-cave",           "/?level=1",         3500,
            description="In-game iframe — three-wizards cave scene."),
    Capture("game-dodecahedron",   "/?level=99",        3500,
            description="In-game iframe — dodecahedron demo."),

    # ── per-role captures ──────────────────────────────────────────
    # Each capture uses its own Casdoor credential so the screenshot
    # shows that role's view of the UI (nav links, console tabs, etc.)
    Capture("player-home",        "/",                  2000,
            login_as="player",
            description="Home page as a plain player — game cards, "
                        "no admin/mod/publisher nav links."),
    Capture("player-session",     "/",                  2000,
            login_as="player",
            description="Home with the multiplayer-session lobby visible "
                        "(same /home route; the session picker mounts below cards)."),
    Capture("mod-console",        "/mod",               2500,
            login_as="mod",
            description="/mod federated remote — live-session list, "
                        "kick controls, session-log tab. Scope-checked "
                        "to mod_game_scopes rows for mod-three-wizards."),
    Capture("mod-home",           "/",                  2000,
            login_as="mod",
            description="Home page as mod-three-wizards — mod nav link "
                        "visible in the host header alongside standard tabs."),
    Capture("publisher-console",  "/publisher",         2500,
            login_as="publisher",
            description="/publisher federated remote — upload card, "
                        "version history, rollback table. "
                        "Scope-checked to publisher_game_scopes rows."),
    Capture("publisher-home",     "/",                  2000,
            login_as="publisher",
            description="Home page as publisher-three-wizards — publisher "
                        "nav link visible in the host header."),
]


def make_driver():
    """
    Try Firefox first; fall back to Chrome. Both run headless. selenium-
    manager auto-installs the matching driver binary.
    """
    try:
        opts = webdriver.FirefoxOptions()
        opts.add_argument("-headless")
        opts.add_argument(f"--width={WINDOW_SIZE[0]}")
        opts.add_argument(f"--height={WINDOW_SIZE[1]}")
        d = webdriver.Firefox(options=opts)
        d.set_window_size(*WINDOW_SIZE)
        return d
    except Exception as fox_err:
        try:
            opts = webdriver.ChromeOptions()
            opts.add_argument("--headless=new")
            opts.add_argument(f"--window-size={WINDOW_SIZE[0]},{WINDOW_SIZE[1]}")
            opts.add_argument("--no-sandbox")
            d = webdriver.Chrome(options=opts)
            return d
        except Exception as chrome_err:
            raise RuntimeError(
                f"Both Firefox and Chrome drivers failed.\n"
                f"  Firefox: {fox_err}\n"
                f"  Chrome:  {chrome_err}"
            )


def can_reach(base_url: str, timeout_s: int = 5) -> bool:
    """
    Quick TCP probe before we spawn a browser. Saves ~10s of webdriver
    boot when we know the stack is down.
    """
    import socket
    from urllib.parse import urlparse
    u = urlparse(base_url)
    host = u.hostname or "localhost"
    port = u.port or (443 if u.scheme == "https" else 80)
    try:
        with socket.create_connection((host, port), timeout=timeout_s):
            return True
    except OSError:
        return False


def capture_one(driver, base_url: str, spec: Capture) -> Path:
    url = base_url.rstrip("/") + spec.path
    driver.get(url)
    WebDriverWait(driver, 10).until(
        lambda d: d.execute_script("return document.readyState") == "complete"
    )
    if spec.pre_action:
        try:
            spec.pre_action(driver)
        except Exception as e:
            print(f"  [{spec.label}] pre_action failed: {e}", file=sys.stderr)
    time.sleep(spec.settle_ms / 1000.0)
    out = SCREENSHOTS_DIR / f"{spec.label}.png"
    driver.save_screenshot(str(out))
    return out


def login_via_api(driver, base_url: str, username: str, password: str) -> bool:
    """
    Mirror what LoginRemoteComponent does in the browser: POST to
    /api/auth/token with {username, password}, then write the
    TokenResponse into sessionStorage.tw_auth so the host's authGuard
    treats the next navigation as already-logged-in.

    We do this from INSIDE the browser context (driver.execute_script)
    rather than via Python `requests` because:
      1) sessionStorage is per-origin — writing it from Python wouldn't
         leak into the Selenium tab anyway.
      2) The browser's fetch goes through nginx with the right Origin
         header, exercising the same CORS path the real login does.

    Returns True if login succeeded; False so the caller can skip the
    auth-required pages and let placeholders fill in.
    """
    # Land on the host so window.location is on the right origin —
    # sessionStorage we write here will then survive the next
    # `driver.get(...)` to / or /admin etc.
    driver.get(base_url.rstrip("/") + "/login")
    WebDriverWait(driver, 10).until(
        lambda d: d.execute_script("return document.readyState") == "complete"
    )
    js = """
        const cb = arguments[arguments.length - 1];
        const [user, pw] = [arguments[0], arguments[1]];
        fetch('/api/auth/token', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ username: user, password: pw }),
        }).then(async (r) => {
            if (!r.ok) {
                cb({ok: false, status: r.status, body: await r.text()});
                return;
            }
            const tok = await r.json();
            // Mirror AuthService.StoredAuth shape exactly so the host's
            // interceptor + guard accept this without a second login.
            sessionStorage.setItem('tw_auth', JSON.stringify(tok));
            cb({ok: true, casdoorUserId: tok.casdoorUserId || null});
        }).catch((e) => cb({ok: false, error: String(e)}));
    """
    driver.set_script_timeout(20)
    try:
        result = driver.execute_async_script(js, username, password)
    except Exception as e:
        print(f"  login: execute_async_script failed: {e}", file=sys.stderr)
        return False
    if isinstance(result, dict) and result.get("ok"):
        print(f"  ✓ logged in as {username} "
              f"(casdoor_user_id={result.get('casdoorUserId')})")
        return True
    print(f"  ✗ login failed: {result}", file=sys.stderr)
    return False


def write_placeholder(spec: Capture) -> Path:
    """
    Render a branded placeholder when the live stack is unreachable.
    Same dimensions as a real screenshot so slide layout doesn't shift.
    """
    out = SCREENSHOTS_DIR / f"{spec.label}.png"
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError:
        # If Pillow isn't even there, write a 1x1 PNG with a header
        # comment so callers see *something*.
        with open(out, "wb") as f:
            f.write(
                b"\x89PNG\r\n\x1a\n"
                b"\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
                b"\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\nIDAT"
                b"x\x9cc\x00\x01\x00\x00\x05\x00\x01\r\n-\xb4\x00\x00\x00"
                b"\x00IEND\xaeB`\x82"
            )
        return out
    bg     = (10, 10, 18)         # snap dark (#0A0A12)
    accent = (124, 77, 255)       # snap accent (#7C4DFF)
    accent2 = (179, 136, 255)     # snap accent-2 (#B388FF)
    fg     = (232, 232, 240)      # snap text on dark (#E8E8F0)

    img = Image.new("RGB", WINDOW_SIZE, bg)
    draw = ImageDraw.Draw(img)
    # Brand accent bar at top
    draw.rectangle([0, 0, WINDOW_SIZE[0], 10], fill=accent)
    # Decorative purple disc
    draw.ellipse([WINDOW_SIZE[0] - 220, -120, WINDOW_SIZE[0] + 100, 200], fill=(40, 25, 80))
    try:
        title_font = ImageFont.truetype(
            "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf", 36)
        body_font  = ImageFont.truetype(
            "/usr/share/fonts/dejavu/DejaVuSans.ttf", 18)
        small_font = ImageFont.truetype(
            "/usr/share/fonts/dejavu/DejaVuSans.ttf", 14)
    except OSError:
        title_font = body_font = small_font = ImageFont.load_default()

    draw.text((60, 90), "SNAPDRAGON GAMES", fill=accent2, font=small_font)
    draw.text((60, 120), spec.label.replace("-", " ").title(),
              fill=fg, font=title_font)
    draw.text((60, 180), spec.description, fill=fg, font=body_font)
    draw.text((60, 220),
              "(Screenshot placeholder — `make docs-screenshots` will replace "
              "this when a compose stack is reachable.)",
              fill=(112, 112, 160), font=small_font)
    draw.text((60, WINDOW_SIZE[1] - 40),
              spec.path,
              fill=accent2, font=small_font)
    img.save(out)
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url",
                        default=os.environ.get("SNAPDRAGON_BASE_URL",
                                               "http://localhost:8080"),
                        help="Base URL of the running stack")
    parser.add_argument("--placeholders-only", action="store_true",
                        help="Skip the live capture, write placeholders only")
    args = parser.parse_args()

    SCREENSHOTS_DIR.mkdir(parents=True, exist_ok=True)

    if args.placeholders_only or not can_reach(args.base_url):
        if not args.placeholders_only:
            print(f"→ {args.base_url} not reachable — writing placeholders.",
                  file=sys.stderr)
        for spec in CAPTURES:
            p = write_placeholder(spec)
            print(f"  ph  {spec.label:<24} {p}")
        return 0

    print(f"→ capturing screenshots from {args.base_url}")
    try:
        driver = make_driver()
    except RuntimeError as e:
        print(f"WARN: {e}\n  falling back to placeholders only", file=sys.stderr)
        for spec in CAPTURES:
            p = write_placeholder(spec)
            print(f"  ph  {spec.label:<24} {p}")
        return 0
    try:
        # Pass 1: capture pages that should be visited un-authenticated
        # (the login form itself).
        for spec in CAPTURES:
            if spec.requires_auth:
                continue
            try:
                p = capture_one(driver, args.base_url, spec)
                print(f"  ✓   {spec.label:<24} {p}")
            except Exception as e:
                print(f"  WARN: {spec.label}: {e}", file=sys.stderr)
                write_placeholder(spec)

        # Pass 2: auth-gated routes, grouped by login_as so we only
        # call login_via_api once per credential batch.
        # Captures with login_as=None use the shared admin credentials
        # (DEFAULT_USER / DEFAULT_PASS) — same behaviour as before.
        from itertools import groupby
        auth_captures = [s for s in CAPTURES if s.requires_auth]

        # Stable group order: None (admin) first, then other roles in
        # insertion order.
        def group_key(s: Capture) -> str:
            return s.login_as or "__default__"

        seen_groups: list[str] = []
        group_map: dict[str, list[Capture]] = {}
        for spec in auth_captures:
            k = group_key(spec)
            if k not in group_map:
                seen_groups.append(k)
                group_map[k] = []
            group_map[k].append(spec)

        for group in seen_groups:
            specs = group_map[group]
            if group == "__default__":
                user, pw = DEFAULT_USER, DEFAULT_PASS
            else:
                user, pw = ROLE_CREDS.get(group, (DEFAULT_USER, DEFAULT_PASS))
            print(f"  → logging in as {user!r} for {len(specs)} capture(s)")
            logged_in = login_via_api(driver, args.base_url, user, pw)
            for spec in specs:
                if not logged_in:
                    p = write_placeholder(spec)
                    print(f"  ph  {spec.label:<24} {p}  (login failed)")
                    continue
                try:
                    p = capture_one(driver, args.base_url, spec)
                    print(f"  ✓   {spec.label:<24} {p}")
                except Exception as e:
                    print(f"  WARN: {spec.label}: {e}", file=sys.stderr)
                    p = write_placeholder(spec)
                    print(f"  ph  {spec.label:<24} {p}  (placeholder)")
    finally:
        driver.quit()
    return 0


if __name__ == "__main__":
    sys.exit(main())
