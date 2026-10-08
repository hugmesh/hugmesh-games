# HugMesh Games — publishing pipeline

This directory builds the documents that describe the project: the
architecture PDF, eight slide decks, eight user-guide PDFs, themed Javadoc, an
OpenAPI spec, rendered diagrams, screenshots, and a zip that bundles them.
Every output is git-ignored. Only the sources are committed: LaTeX, Python,
CSS, shell scripts, and the `pom.xml` plugin configuration. Rebuild any output
locally with the targets in this directory's `Makefile`, or with the matching
`make docs-*` shortcuts at the repo root.

```
docs/publishing/
├── architecture/              LaTeX architecture reference (~30 pp PDF)
│   ├── architecture.tex       Main document
│   ├── hugmesh-theme.sty   Brand colors, fonts, lstlisting styles, callouts
│   ├── chapters/              One file per chapter (01-07 and 99-schema-diagrams)
│   ├── diagrams/              TikZ figures included by the chapters
│   ├── marbles/               TikZ marble diagrams, one per controller or service
│   └── build/                 ← output (git-ignored)
├── slides/                    Eight python-pptx decks
│   ├── _hugmesh_deck.py    Shared deck builder (cover, section, closing layouts)
│   ├── intro_for_coders.py    Entry-level coder tour
│   ├── administration_manual.py
│   ├── godot_developer.py
│   ├── fullstack_developer.py
│   ├── maintainer_runbook.py
│   ├── data_architect_overview.py
│   ├── moderator.py
│   ├── publisher.py
│   └── build/                 ← output (git-ignored)
├── userguides/                Eight per-audience LaTeX guides (one folder each)
│   ├── build-all.sh
│   └── _shared/               Shared preamble
├── openapi/                   Spec extraction and LaTeX rendering
├── diagrams-rendered/         render-all.sh (PNG output is git-ignored)
├── screenshots/               capture.py, harvest-from-e2e.py
├── javadoc-theme/             Custom CSS + header/footer for Maven Javadoc
│   ├── hugmesh.css
│   ├── header.html
│   └── footer.html
├── javadoc-output/            ← output (git-ignored)
├── scripts/
│   ├── build-pdf.sh           Wrappers around `make` for path-driven callers
│   ├── build-slides.sh
│   ├── build-javadocs.sh
│   ├── build-schema-diagram.py
│   └── install-hooks.sh       Optional pre-push hook installer
└── Makefile                   The single source of truth for builds
```

The diagram below shows how the source files flow through the build
pipeline and where each artefact lands.

```mermaid
flowchart TB
    subgraph sources["Source files (committed)"]
        tex["architecture/*.tex\nchapters/ · diagrams/"]
        py["slides/*.py\n_hugmesh_deck.py"]
        css["javadoc-theme/\nhugmesh.css · header.html"]
        pom["backend/pom.xml\njavadoc plugin config"]
    end

    subgraph make["Makefile targets"]
        pdf["make docs-pdf"]
        slides["make docs-slides"]
        javadoc["make docs-javadocs"]
        bundle["make docs-bundle"]
    end

    subgraph outputs["Output (git-ignored)"]
        pdfout["architecture/build/\narchitecture.pdf"]
        pptxout["slides/build/\n*.pptx  (×8)"]
        javadocout["javadoc-output/\n*.html"]
        zip["docs/\nhugmesh-design-docs-&lt;timestamp&gt;.zip"]
    end

    tex --> pdf --> pdfout
    py  --> slides --> pptxout
    css --> javadoc
    pom --> javadoc --> javadocout
    pdfout  --> bundle
    pptxout --> bundle
    javadocout --> bundle
    bundle --> zip
```

## Quick start

```
make docs-pdf           # ~10 s — runs pdflatex twice for cross-refs
make docs-slides        # ~5 s — produces eight .pptx files
make docs-javadocs      # ~30 s — runs `mvn javadoc:javadoc` on the BFF
make docs-userguides    # eight user-guide PDFs (builds the diagrams first)
make docs-bundle        # ~3 s — zip every produced artefact into docs/
make docs-all           # OpenAPI spec, diagrams, screenshots, PDF, decks, guides, Javadoc, bundle
make docs-clean         # remove every produced artefact (incl. zips)
make docs-install-hooks # opt-in pre-push hook that re-runs docs-pdf on touch
```

### Build provenance

Every produced artefact — the architecture PDF, the eight slide decks,
the themed Javadoc HTML, and the zip bundle's `MANIFEST.txt` — carries
the same project name and UTC build timestamp, so a printed or
emailed page can be traced to an exact build. The Makefile writes
`HugMesh Games · YYYY-MM-DD HH:MM UTC` on every cover slide and in
every PDF page footer. Override the project label at build time:

```
make docs-all HUGMESH_PROJECT_NAME='HugMesh Games (RC1)'
```

The bundle target writes `docs/hugmesh-design-docs-<YYYY-MM-DDTHHMMSSZ>.zip`.
The zip has a top-level `hugmesh-design-docs-<YYYY-MM-DDTHHMMSSZ>/` prefix and a
`MANIFEST.txt` that lists every included file. An `all-docs/` folder holds every
PDF and deck flat. Override the name with `BUNDLE_NAME`. The zip is
git-ignored. `make docs-clean` also removes the old `hugmesh-docs-*.zip` that
earlier builds wrote at the repo root.

Every target is idempotent, so re-running one is always safe. The
`make docs-*` targets at the repo root delegate to this directory's
`Makefile`, so `make docs-pdf` and `make -C docs/publishing pdf` do the same
thing.

## Prerequisites

| Artefact | Tool | Notes |
|---|---|---|
| Architecture PDF | `pdflatex` (TeX Live 2024+) with the packages that `architecture.tex` and `hugmesh-theme.sty` load: `tikz`, `tcolorbox`, `titlesec`, `csquotes`, `enumitem`, `listings`, `fancyhdr`, `microtype`, `pdfpages`, `hyperref`, `tabularx`, `longtable`, `booktabs`, and others | On Debian/Ubuntu: `apt install texlive-latex-extra texlive-fonts-extra texlive-pictures`. On Gentoo: `dev-tex/tex-live` with USE `extra`. |
| PowerPoints | `python3.12` + `python-pptx` | The Make target creates a venv at `~/.venvs/hugmesh-publishing` on first run. |
| Javadoc | `mvn` (Maven 3.9+), Java 25 | Plugin config lives in `backend/pom.xml`; the BFF targets Java 25 (`java.version`). |

The Make `venv` target looks for `python3.12` specifically because
Gentoo's stock Python 3.14 does not include `ensurepip`, which the venv needs
to bootstrap. Any Python ≥ 3.10 works at the language level. The target creates
the venv only when `~/.venvs/hugmesh-publishing/bin/python` is missing. If you
do not have 3.12, build that venv yourself with another interpreter and
`pip install python-pptx` into it. Editing the Makefile's `PYTHON` variable does
not help, because it already points into that venv.

## What each artefact is for

### `architecture/` — typeset architecture reference (PDF)

A printable, ~30-page companion to the repository `README.md` and the
plan document. It has seven chapters (overview, runtime architecture,
module federation, data model, API reference, build & deploy, and
extending the portal), a schema-diagrams chapter, and six TikZ diagrams
(runtime data flow, three-tier topology, sessions topology, federation
bootstrap, data-model clusters, and the build chain), plus a marble
diagram for each controller and service. Every section heading links to
the matching source file on GitHub.

The PDF builds into `architecture/build/architecture.pdf` and is
git-ignored. To include it in a release, attach the built PDF as a
GitHub release asset. Do not commit the binary.

### `slides/` — six PowerPoint walkthroughs

There are eight decks. (The heading says six because it predates the moderator and publisher decks; it is left as is so its link anchor keeps working.) Slide counts are one cover, one slide per `SECTIONS` entry, and one closing
slide, counted from the source. They were not checked against a rendered deck
(`python-pptx` is not installed here).

| Deck | Audience | Slides | What it covers |
|---|---|---|---|
| `intro-for-coders.pptx` | New engineers, generalists | 21 | Whirlwind tour of the architecture, where code lives, click-to-frame flow, common gotchas. |
| `administration-manual.pptx` | Operators / `ROLE_ADMIN` holders | 26 | Bring-up, admin UI, hot upload, rollback, role grants, scheduled jobs, troubleshooting. |
| `godot-developer.pptx` | Game developers | 19 | New Godot project from scratch, slug contract, export presets, ship via Make or upload via admin. |
| `fullstack-developer.pptx` | Backend + frontend engineers | 23 | Mental model, file-by-file tour, endpoint addition end-to-end, federation rules, native-image hints. |
| `maintainer-runbook.pptx` | On-call / SRE | 20 | Health checks, seven runbooks (BFF flap, SSE drop, federated 500s, blank iframe, upload failure, stuck launches, native-image start), DR posture. |
| `data-architect-overview.pptx` | Data architects / steering committee | 25 | Domain map, data lifecycle, schema posture, regulatory + DR posture, cloud-relocation swap-points. |
| `moderator.pptx` | `ROLE_MOD` holders | 14 | What the role allows, where the per-game scope check lives, the day-to-day playbook for keeping a session healthy. |
| `publisher.pptx` | `ROLE_PUBLISHER` holders | 17 | Upload, roll back, pin versions, edit access policy for games in your scope. |

Each deck is data-driven. The `SECTIONS` list at the top of each `*.py` file
is the only thing you edit to change content. Re-run the deck's script
directly, or run `make docs-slides` to regenerate all eight decks.

### `javadoc-theme/` — branded Maven Javadoc

The `javadoc:javadoc` Maven goal, configured in `backend/pom.xml`, reads the
three assets in this directory:

- `hugmesh.css` — replaces the doclet's default stylesheet with
  HugMesh brand colours, typeface choices, and header/footer chrome.
- `header.html` — a gradient brand strip at the top of every generated page.
- `footer.html` — links to the repository, the architecture PDF, and the main
  README.

The generated HTML goes to `javadoc-output/`, which mirrors
`backend/target/reports/apidocs/` and is git-ignored.

## Hooks

`make docs-install-hooks` installs a Git `pre-push` hook that re-runs
`make docs-pdf` whenever a push touches anything under `docs/publishing/` or
the file `docs/PLAN-module-federation-and-native.md`. The hook is opt-in
because most developers do not touch the docs on every push. Install it when
you are actively working on documentation.

The installer is idempotent: re-running it does nothing if the hook is
already in place. If a `pre-push` hook that is not ours already exists, the
installer leaves it alone, prints a warning, and exits with status 1. It writes
nothing in that case. It writes `docs/publishing/scripts/pre-push.sample`
(git-ignored) only on a fresh install, so to merge by hand you must copy the
hook body from `docs/publishing/scripts/install-hooks.sh` yourself.

## CI

No CI publishing step exists yet. This repo's CI is GitLab
(`.gitlab-ci.yml`), and it has no docs job. The steps below sketch what such a
job needs; the GitHub Actions YAML is an untested outline, not a working job.
When you are ready to publish artefacts on every release:

1. Add a job that runs `make docs-all` on a builder image that has
   TeX Live 2024+, Python 3.12, Maven 3.9+, and Java 25. The `docs-all`
   target orders its own dependencies.
2. Upload the following paths as GitHub release assets:
   - `docs/publishing/architecture/build/architecture.pdf`
   - `docs/publishing/slides/build/*.pptx` (eight files)
   - All files under `docs/publishing/javadoc-output/`
3. Optionally deploy the Javadoc HTML to GitHub Pages. The `hugmesh.css`
   brand stylesheet is self-contained and needs no changes there.

A minimal GitHub Actions job skeleton:

```yaml
jobs:
  publish-docs:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '25', distribution: 'temurin' }
      - run: sudo apt-get install -y texlive-latex-extra texlive-fonts-extra texlive-pictures python3.12
      - run: make docs-all
      - uses: actions/upload-release-asset@v1
        # ... point at docs/publishing/architecture/build/architecture.pdf etc.
```
