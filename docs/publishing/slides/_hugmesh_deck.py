"""
Shared deck builder for the six HugMesh Games slide decks.

Every deck script imports `make_deck(title, subtitle, sections, output)`
from this module and feeds it a list of section dicts. Each section
becomes one slide. Edit the deck data structures in the per-script files;
this module only knows about layout and brand.

Each section dict supports these keys (all optional except `title`):
    title  : str                  — slide heading
    body   : list[str | (int, str)] — bullets, optional indent level
    code   : str                  — single-block code under the body
    image  : str (path)           — PNG to embed in the right pane
                                    (rendered diagram / screenshot)
    dark   : bool                 — invert palette

If `image` is given but the file isn't on disk yet (because docs-diagrams
or docs-screenshots hasn't run) the slide renders a placeholder box so
the deck still builds.

Brand tokens mirror docs/publishing/architecture/hugmesh-theme.sty
and docs/publishing/javadoc-theme/hugmesh.css so the three artefact
families look like one product.
"""

import os
from datetime import datetime, timezone
from pathlib import Path

from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR


# Repo-root-relative resolver so deck data can use short keys like
# "diagrams-rendered/build/topology.png" instead of absolute paths.
PUBLISHING_DIR = Path(__file__).resolve().parent.parent
DIAGRAMS_DIR   = PUBLISHING_DIR / "diagrams-rendered" / "build"
SCREENSHOTS_DIR = PUBLISHING_DIR / "screenshots" / "build"


# Brand label + per-build timestamp shown on every cover and on every
# slide footer. The Makefile passes BUILD_DATETIME so all artefacts in
# one bundle ship the same string; we fall back to UTC `now()` when the
# script is invoked directly.
PROJECT_NAME = os.environ.get("HUGMESH_PROJECT_NAME", "HugMesh Games")
BUILD_DATETIME = (
    os.environ.get("HUGMESH_BUILD_DATETIME")
    or datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
)
BUILD_STAMP = f"{PROJECT_NAME} · {BUILD_DATETIME}"


def resolve_image(spec):
    """
    Accept a Path, an absolute path string, or a short key like
    'diagram:topology' / 'screenshot:home-page'. Returns a Path that
    may or may not exist (callers handle the missing case).
    """
    if spec is None:
        return None
    if isinstance(spec, Path):
        return spec
    s = str(spec)
    if s.startswith("diagram:"):
        return DIAGRAMS_DIR / f"{s[len('diagram:'):]}.png"
    if s.startswith("screenshot:"):
        return SCREENSHOTS_DIR / f"{s[len('screenshot:'):]}.png"
    return Path(s)


# ── Brand palette ──────────────────────────────────────────────────────────
ACCENT     = RGBColor(0x7C, 0x4D, 0xFF)
ACCENT2    = RGBColor(0xB3, 0x88, 0xFF)
ACCENT_DIM = RGBColor(0x4A, 0x2F, 0xB5)
DARK       = RGBColor(0x0A, 0x0A, 0x12)
SURFACE    = RGBColor(0x12, 0x12, 0x1E)
TEXT       = RGBColor(0xE8, 0xE8, 0xF0)
MUTED      = RGBColor(0x70, 0x70, 0xA0)
WHITE      = RGBColor(0xFF, 0xFF, 0xFF)
CODE_BG    = RGBColor(0x18, 0x18, 0x2A)


# ── Geometry ──────────────────────────────────────────────────────────────
SLIDE_W = Inches(13.333)
SLIDE_H = Inches(7.5)


def _new_presentation() -> Presentation:
    pres = Presentation()
    pres.slide_width  = SLIDE_W
    pres.slide_height = SLIDE_H
    return pres


def _fill(shape, rgb: RGBColor) -> None:
    shape.fill.solid()
    shape.fill.fore_color.rgb = rgb
    shape.line.fill.background()


def _add_textbox(slide, left, top, width, height, *,
                 text="", size=18, bold=False, color=TEXT,
                 font="Calibri", align=PP_ALIGN.LEFT):
    tb = slide.shapes.add_textbox(left, top, width, height)
    tf = tb.text_frame
    tf.word_wrap = True
    tf.margin_left = Pt(2); tf.margin_right = Pt(2)
    tf.margin_top  = Pt(2); tf.margin_bottom = Pt(2)
    p = tf.paragraphs[0]
    p.alignment = align
    run = p.add_run()
    run.text = text
    run.font.name = font
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.color.rgb = color
    return tb, tf, p


def _add_paragraph(tf, text, *, size=14, bold=False, color=TEXT,
                   bullet=False, indent=0, font="Calibri",
                   align=PP_ALIGN.LEFT, space_before=4):
    p = tf.add_paragraph()
    p.alignment = align
    p.space_before = Pt(space_before)
    if indent:
        p.level = indent
    run = p.add_run()
    if bullet:
        run.text = f"•  {text}"
    else:
        run.text = text
    run.font.name = font
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.color.rgb = color
    return p


def _solid_rect(slide, left, top, width, height, rgb):
    shape = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, left, top, width, height)
    _fill(shape, rgb)
    shape.shadow.inherit = False
    return shape


def _gradient_band(slide, left, top, width, height):
    # Simulate a gradient by stacking two thin rects.
    bg = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, left, top, width, height)
    _fill(bg, ACCENT)
    return bg


def _accent_rule(slide, left, top, width, height=Pt(3), rgb=ACCENT):
    return _solid_rect(slide, left, top, width, height, rgb)


# ── Slide layouts ─────────────────────────────────────────────────────────
def _add_dark_background(slide):
    bg = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, SLIDE_W, SLIDE_H)
    _fill(bg, DARK)
    return bg


def _add_light_background(slide):
    bg = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, SLIDE_W, SLIDE_H)
    _fill(bg, WHITE)
    return bg


def _make_cover(pres, title, subtitle, kicker, audience):
    slide = pres.slides.add_slide(pres.slide_layouts[6])  # blank
    _add_dark_background(slide)

    # Decorative purple disc.
    disc = slide.shapes.add_shape(
        MSO_SHAPE.OVAL, Inches(-2), Inches(-2), Inches(8), Inches(8))
    _fill(disc, ACCENT)
    disc.fill.transparency = 0.7

    disc2 = slide.shapes.add_shape(
        MSO_SHAPE.OVAL, Inches(8), Inches(4), Inches(7), Inches(7))
    _fill(disc2, ACCENT2)

    # Kicker.
    _add_textbox(slide, Inches(0.8), Inches(1.4), Inches(11), Inches(0.6),
                 text=kicker.upper(), size=14, bold=True, color=ACCENT2,
                 font="Calibri")

    # Title.
    _add_textbox(slide, Inches(0.8), Inches(2.0), Inches(11), Inches(2.4),
                 text=title, size=54, bold=True, color=WHITE,
                 font="Calibri")

    # Subtitle.
    _add_textbox(slide, Inches(0.8), Inches(4.6), Inches(11), Inches(1.0),
                 text=subtitle, size=22, color=ACCENT2, font="Calibri")

    # Accent rule.
    _accent_rule(slide, Inches(0.8), Inches(5.7), Inches(4))

    # Footer audience block.
    _add_textbox(slide, Inches(0.8), Inches(6.1), Inches(11), Inches(0.4),
                 text=f"Audience: {audience}",
                 size=12, color=MUTED, font="Calibri")
    _add_textbox(slide, Inches(0.8), Inches(6.5), Inches(11), Inches(0.4),
                 text=BUILD_STAMP,
                 size=12, color=ACCENT2, font="Calibri")
    _add_textbox(slide, Inches(0.8), Inches(6.9), Inches(11), Inches(0.4),
                 text="github.com/Snapdragon-Systems/hugmesh-games",
                 size=11, color=MUTED, font="Calibri")
    return slide


def _make_section_header(slide, title, body_lines, footer_n, footer_total,
                         *, dark=False, code=None, image=None):
    """
    Build one content slide. The body region splits depending on what's
    present: if `image` is set, the body shrinks to the left half and
    the image fills the right; otherwise body uses full width. `code`
    docks a code block to the bottom strip and is mutually exclusive
    with `image` (slides can't carry both without crowding).
    """
    if dark:
        _add_dark_background(slide)
        title_color = WHITE
        body_color = TEXT
    else:
        _add_light_background(slide)
        title_color = ACCENT_DIM
        body_color = RGBColor(0x1F, 0x1F, 0x2E)

    # Top accent band.
    _accent_rule(slide, 0, 0, SLIDE_W, height=Pt(6))

    # Title.
    _add_textbox(slide, Inches(0.6), Inches(0.4), Inches(12.2), Inches(1.0),
                 text=title, size=32, bold=True, color=title_color,
                 font="Calibri")

    # Title rule.
    _accent_rule(slide, Inches(0.6), Inches(1.32), Inches(2), height=Pt(3),
                 rgb=ACCENT)

    # Body region geometry depends on whether an image is present.
    body_left  = Inches(0.6)
    body_top   = Inches(1.7)
    body_width = Inches(12.2)
    if image is not None:
        body_width = Inches(6.4)   # left half; image lives on the right.

    body_height = Inches(5.0 if not code else 3.5)

    # Body bullets.
    if body_lines:
        tb, tf, _p = _add_textbox(
            slide, body_left, body_top,
            body_width, body_height,
            text=body_lines[0],
            size=16 if image else 18, color=body_color,
            font="Calibri")
        for line in body_lines[1:]:
            level = 0
            text = line
            if isinstance(line, tuple):
                level, text = line
            _add_paragraph(tf, text,
                           size=14 if image else 16,
                           color=body_color,
                           bullet=True, indent=level, space_before=6)

    # Right-side image (if any).
    if image is not None:
        _add_image_panel(slide, image,
                         left=Inches(7.2), top=Inches(1.7),
                         width=Inches(5.6), height=Inches(5.0))

    # Optional code block (across full width below body).
    if code:
        code_top = Inches(5.0)
        _solid_rect(slide, Inches(0.6), code_top, Inches(12.2),
                    Inches(1.7), CODE_BG if dark else SURFACE)
        _add_textbox(
            slide, Inches(0.8), code_top + Inches(0.1),
            Inches(11.8), Inches(1.5),
            text=code, size=14, color=ACCENT2 if dark else WHITE,
            font="Consolas")

    # Footer: project name + build datetime (left), slide number (right).
    _add_textbox(slide, Inches(0.6), Inches(7.05), Inches(10), Inches(0.3),
                 text=BUILD_STAMP,
                 size=10, color=MUTED, font="Calibri")
    _add_textbox(slide, Inches(11.5), Inches(7.05), Inches(1.5), Inches(0.3),
                 text=f"{footer_n} / {footer_total}",
                 size=10, color=MUTED, font="Calibri",
                 align=PP_ALIGN.RIGHT)


def _add_image_panel(slide, image_path, *, left, top, width, height):
    """
    Drop an image into the slide centred in the given rect, scaled to
    fit while preserving aspect. Falls back to a placeholder rectangle
    with a label if the image is missing — slides remain valid even
    when the docs-diagrams or docs-screenshots target hasn't run.
    """
    p = Path(image_path)
    if not p.exists():
        ph = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, left, top, width, height)
        _fill(ph, SURFACE)
        ph.line.color.rgb = ACCENT
        _add_textbox(slide, left, top + Pt(8),
                     width, height,
                     text=f"[ image not yet rendered ]\n{p.name}",
                     size=12, color=MUTED, font="Calibri",
                     align=PP_ALIGN.CENTER)
        return None
    # python-pptx's add_picture preserves aspect when only one of
    # width/height is given. Try height-bounded first (taller images)
    # then check the resulting width and re-run width-bounded if it
    # busted the box.
    pic = slide.shapes.add_picture(str(p), int(left), int(top),
                                   height=int(height))
    if pic.width > width:
        # Too wide: scale down by the width:
        slide.shapes._spTree.remove(pic._element)
        pic = slide.shapes.add_picture(str(p), int(left), int(top),
                                       width=int(width))
    # Centre inside the panel rect.
    cx = left + (width  - pic.width)  / 2
    cy = top  + (height - pic.height) / 2
    pic.left = int(cx)
    pic.top  = int(cy)
    return pic


def _make_closing(pres, title, body_lines, links):
    slide = pres.slides.add_slide(pres.slide_layouts[6])
    _add_dark_background(slide)

    _add_textbox(slide, Inches(0.8), Inches(1.0), Inches(11.6), Inches(1.2),
                 text=title, size=44, bold=True, color=WHITE, font="Calibri")
    _accent_rule(slide, Inches(0.8), Inches(2.1), Inches(4), height=Pt(3),
                 rgb=ACCENT2)

    tb, tf, _ = _add_textbox(
        slide, Inches(0.8), Inches(2.6), Inches(11.6), Inches(2.5),
        text=body_lines[0] if body_lines else "",
        size=18, color=TEXT, font="Calibri")
    for line in body_lines[1:]:
        _add_paragraph(tf, line, size=16, color=TEXT, bullet=True,
                       space_before=8)

    if links:
        ltb, ltf, _ = _add_textbox(
            slide, Inches(0.8), Inches(5.5), Inches(11.6), Inches(1.5),
            text="Where to next:", size=14, bold=True, color=ACCENT2,
            font="Calibri")
        for link in links:
            _add_paragraph(ltf, link, size=14, color=ACCENT2, bullet=True,
                           space_before=4)

    _add_textbox(slide, Inches(0.8), Inches(7.0), Inches(11.6), Inches(0.4),
                 text="github.com/Snapdragon-Systems/hugmesh-games",
                 size=12, color=MUTED, font="Calibri")


# ── Public API ────────────────────────────────────────────────────────────
def make_deck(*, title: str, subtitle: str, kicker: str, audience: str,
              sections: list, closing: dict, output: Path) -> None:
    """
    Build a deck.

    sections: list of dicts with keys:
        title:  str
        body:   list[str | (level:int, str)]
        code:   optional str (rendered as a single block)
        dark:   optional bool — invert palette for that slide
    closing: dict with keys title, body, links

    output: pathlib.Path; parent dirs are created.
    """
    pres = _new_presentation()
    _make_cover(pres, title, subtitle, kicker, audience)

    total = len(sections) + 1  # +1 for closing
    for n, sec in enumerate(sections, start=1):
        slide = pres.slides.add_slide(pres.slide_layouts[6])
        _make_section_header(slide, sec["title"], sec.get("body", []),
                             n, total,
                             dark=sec.get("dark", False),
                             code=sec.get("code"),
                             image=resolve_image(sec.get("image")))

    _make_closing(pres, closing["title"], closing.get("body", []),
                  closing.get("links", []))

    output.parent.mkdir(parents=True, exist_ok=True)
    pres.save(str(output))
