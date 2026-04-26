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


# ── Capture spec ──────────────────────────────────────────────────────────
@dataclass
class Capture:
    label: str            # output filename stem (no .png)
    path:  str            # URL path relative to BASE_URL
    settle_ms: int = 1500
    pre_action: Optional[Callable] = None
    description: str = ""


CAPTURES: list[Capture] = [
    Capture("home-page",           "/",                 1500,
            description="Game catalog landing page (federated host shell)."),
    Capture("login-form",          "/login",            1200,
            description="Federated login remote — Casdoor auth code flow."),
    Capture("home-after-login",    "/",                 2000,
            description="Home page once a session token is in sessionStorage."),
    Capture("admin-dashboard",     "/admin",            2500,
            description="Admin dashboard (federated admin remote)."),
    Capture("admin-uploads",       "/admin/games",      2500,
            description="Hot-upload + rollback UI for game bundles."),
    Capture("game-cave",           "/?level=1",         3500,
            description="In-game iframe — three-wizards cave scene."),
    Capture("game-dodecahedron",   "/?level=99",        3500,
            description="In-game iframe — dodecahedron demo."),
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
        for spec in CAPTURES:
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
