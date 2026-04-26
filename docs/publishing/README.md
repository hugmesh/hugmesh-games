# Snapdragon Games — publishing pipeline

This directory produces three families of artefacts that document and
introduce the project. Every output is git-ignored — only the sources
(LaTeX, Python, CSS, pom.xml plugin config) are committed. Rebuild any
of them locally with the targets in this directory's `Makefile` or the
matching `make docs-*` targets at the repo root.

```
docs/publishing/
├── architecture/              LaTeX architecture reference (~30 pp PDF)
│   ├── architecture.tex       Main document
│   ├── snapdragon-theme.sty   Brand colors, fonts, lstlisting styles, callouts
│   ├── chapters/              One file per chapter
│   ├── diagrams/              TikZ figures included by the chapters
│   └── build/                 ← output (git-ignored)
├── slides/                    Six python-pptx decks
│   ├── _snapdragon_deck.py    Shared deck builder (cover, section, closing layouts)
│   ├── intro_for_coders.py    Entry-level coder tour
│   ├── administration_manual.py
│   ├── godot_developer.py
│   ├── fullstack_developer.py
│   ├── maintainer_runbook.py
│   ├── data_architect_overview.py
│   └── build/                 ← output (git-ignored)
├── javadoc-theme/             Custom CSS + header/footer for Maven Javadoc
│   ├── snapdragon.css
│   ├── header.html
│   └── footer.html
├── javadoc-output/            ← output (git-ignored)
├── scripts/
│   ├── build-pdf.sh           Wrappers around `make` for path-driven callers
│   ├── build-slides.sh
│   ├── build-javadocs.sh
│   └── install-hooks.sh       Optional pre-push hook installer
└── Makefile                   The single source of truth for builds
```

## Quick start

```
make docs-pdf           # ~10 s — runs pdflatex twice for cross-refs
make docs-slides        # ~5 s — produces six .pptx files
make docs-javadocs      # ~30 s — runs `mvn javadoc:javadoc` on the BFF
make docs-bundle        # ~3 s — zip every produced artefact at repo root
make docs-all           # everything above + bundle
make docs-clean         # remove every produced artefact (incl. zips)
make docs-install-hooks # opt-in pre-push hook that re-runs docs-pdf on touch
```

### Build provenance

Every produced artefact (architecture PDF, six user-guide PDFs, six
slide decks, the themed Javadoc HTML, and the zip bundle's manifest)
carries the same project-name and UTC build datetime so a printed or
emailed page can be paired back to a specific build. The Makefile
emits `Snapdragon Games · YYYY-MM-DD HH:MM UTC` on every cover and
in every page footer. Override the project label on the command line:

```
make docs-all SNAPDRAGON_PROJECT_NAME='Snapdragon Games (RC1)'
```

The bundle target writes to `<repo-root>/snapdragon-docs-<YYYY-MM-DD>.zip`
with a top-level `snapdragon-docs-<YYYY-MM-DD>/` prefix and a
`MANIFEST.txt` listing every file. The zip is git-ignored.

Every target is idempotent. The Make targets at the repo root delegate
to this directory's `Makefile`, so `make docs-pdf` and
`make -C docs/publishing pdf` are interchangeable.

## Prerequisites

| Artefact | Tool | Notes |
|---|---|---|
| Architecture PDF | `pdflatex` (TeX Live 2024+), `tikz`, `tcolorbox`, `titlesec`, `csquotes`, `enumitem`, `inconsolata`, `fontawesome` | On Debian/Ubuntu: `apt install texlive-latex-extra texlive-fonts-extra texlive-pictures`. On Gentoo: `dev-tex/tex-live` with USE `extra`. |
| PowerPoints | `python3.12` + `python-pptx` | The Make target creates a venv at `~/.venvs/snapdragon-publishing` on first run. |
| Javadoc | `mvn` (Maven 3.9+), Java 21 | Plugin config lives in `backend/pom.xml`. |

The Make `venv` target probes for `python3.12` because Gentoo's stock
Python 3.14 doesn't ship `ensurepip`. Any Python ≥ 3.10 works at the
language level — adjust the venv target if you don't have 3.12.

## What each artefact is for

### `architecture/` — typeset architecture reference (PDF)

A ~30-page printable companion to the repository `README.md` and the
plan document. Seven chapters: overview, runtime architecture, module
federation, data model, API reference, build & deploy, extending the
portal. Five TikZ diagrams (runtime data flow, three-tier topology,
federation bootstrap, data-model clusters, build chain). All headings
link back to source files on GitHub.

The PDF is produced into `architecture/build/architecture.pdf` and is
git-ignored. To embed in a release, attach the produced PDF as a release
asset; do not commit it.

### `slides/` — six PowerPoint walkthroughs

| Deck | Audience | Slides | What it covers |
|---|---|---|---|
| `intro-for-coders.pptx` | New engineers, generalists | ~14 | Whirlwind tour of the architecture, where code lives, click-to-frame flow, common gotchas. |
| `administration-manual.pptx` | Operators / `ROLE_ADMIN` holders | ~16 | Bring-up, admin UI, hot upload, rollback, role grants, scheduled jobs, troubleshooting. |
| `godot-developer.pptx` | Game developers | ~16 | New Godot project from scratch, slug contract, export presets, ship via Make or upload via admin. |
| `fullstack-developer.pptx` | Backend + frontend engineers | ~18 | Mental model, file-by-file tour, endpoint addition end-to-end, federation rules, native-image hints. |
| `maintainer-runbook.pptx` | On-call / SRE | ~16 | Health checks, seven runbooks (BFF flap, SSE drop, federated 500s, blank iframe, upload failure, stuck launches, native-image start), DR posture. |
| `data-architect-overview.pptx` | Data architects / steering committee | ~16 | Domain map, data lifecycle, schema posture, regulatory + DR posture, cloud-relocation swap-points. |

Each deck is data-driven — the `SECTIONS` list at the top of each
`*.py` file is the editable surface. Re-run the deck's script (or
`make docs-slides`) to regenerate.

### `javadoc-theme/` — branded Maven Javadoc

The Maven `javadoc:javadoc` goal is configured in
`backend/pom.xml` to consume the assets in this directory:

- `snapdragon.css` — overrides the doclet's stylesheet with brand colors,
  type, header/footer chrome.
- `header.html` — the gradient brand strip injected into every page.
- `footer.html` — repository / architecture / README links.

Output lands in `javadoc-output/` (mirrored from
`backend/target/reports/apidocs/`) and is git-ignored.

## Hooks

`make docs-install-hooks` installs a pre-push hook that re-runs
`make docs-pdf` whenever a push touches `docs/publishing/` or
`docs/PLAN-module-federation-and-native.md`. It's opt-in because not
every developer ships docs every push; if you do, install it.

The hook is idempotent. Re-running the installer is a no-op if it's
already wired. If you have an existing `pre-push` hook, the installer
declines to clobber it and writes a `pre-push.sample` you can merge by
hand.

## CI

There is no CI publishing step today. To publish on every release:

1. Add a job that runs `make docs-all` on a builder image with
   TeX Live, Python 3.12, Maven, and Java 21.
2. Upload `docs/publishing/architecture/build/architecture.pdf`,
   `docs/publishing/slides/build/*.pptx`, and the contents of
   `docs/publishing/javadoc-output/` as release assets.
3. Optionally publish the Javadoc HTML to GitHub Pages — the brand
   stylesheet is self-contained and works without modification.
