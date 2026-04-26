# Architecture publishing pipeline — PDF, six PowerPoints, themed Javadoc

> **PR helper.** Open the PR with:
>
> ```
> gh pr create --base main --head feature/architecture-publishing \
>   --title "Architecture publishing pipeline — PDF, six PowerPoints, themed Javadoc" \
>   --body-file docs/PR-architecture-publishing.md
> ```

## Summary

Adds a self-contained publishing pipeline under `docs/publishing/` that produces three families of artefacts, all git-ignored — only the LaTeX, Python, CSS, and pom.xml plugin sources are committed:

- **LaTeX architecture reference (28-page PDF)** — `architecture/architecture.tex` with a custom `snapdragon-theme.sty` mirroring the project's brand palette (`#7C4DFF` accent on near-black). Seven chapters covering overview, runtime architecture, module federation, data model, API reference, build & deploy, and extending the portal. Five TikZ diagrams (runtime data flow, three-tier topology, federation bootstrap, data-model clusters, build chain). Every section links back to source files on GitHub via `\reposrc` / `\snapfile` macros.

- **Six python-pptx decks**, each targeting a distinct audience:
  - `intro-for-coders.pptx` — entry-level coder tour
  - `administration-manual.pptx` — `ROLE_ADMIN` operators
  - `godot-developer.pptx` — game developers shipping a Godot title
  - `fullstack-developer.pptx` — BFF + frontend engineers
  - `maintainer-runbook.pptx` — on-call / SRE, seven incident runbooks
  - `data-architect-overview.pptx` — steering committee, regulatory + DR posture

  Built from a shared `_snapdragon_deck.py` layout module so all six share cover/section/closing chrome and brand palette. Section content is data-driven (edit the `SECTIONS` list at the top of each `*.py`).

- **Themed Maven Javadoc** — `backend/pom.xml` configures `maven-javadoc-plugin` to consume `docs/publishing/javadoc-theme/{snapdragon.css,header.html,footer.html}`. `doclint=none` + `failOnWarnings=false` so the doc build doesn't fail on legitimate but undocumented record `@param` lines.

## Build hooks

Root `Makefile` targets (delegate to `docs/publishing/Makefile`):

```
make docs-pdf           ~10 s — runs pdflatex twice for cross-refs
make docs-slides        ~5 s  — produces six .pptx files
make docs-javadocs      ~30 s — runs `mvn javadoc:javadoc` on the BFF
make docs-all           everything above
make docs-clean         remove every produced artefact
make docs-install-hooks opt-in pre-push hook that re-runs docs-pdf on touch
```

The `install-hooks.sh` script installs a `.git/hooks/pre-push` that re-runs `make docs-pdf` whenever the push touches `docs/publishing/` or `docs/PLAN-module-federation-and-native.md`. Failures abort the push so a broken LaTeX build can't land on `main`. Idempotent; declines to clobber a pre-existing hook.

`python-pptx` runs inside `~/.venvs/snapdragon-publishing` (PEP 668 — Gentoo's system Python is externally-managed). The Make target creates the venv on first run.

## Files

```
docs/publishing/
├── README.md
├── Makefile
├── architecture/
│   ├── architecture.tex
│   ├── snapdragon-theme.sty
│   ├── chapters/01-overview … 07-extending.tex
│   └── diagrams/runtime-flow / topology / federation-bootstrap / data-model / build-chain .tex
├── slides/
│   ├── _snapdragon_deck.py        (shared layout)
│   ├── intro_for_coders.py
│   ├── administration_manual.py
│   ├── godot_developer.py
│   ├── fullstack_developer.py
│   ├── maintainer_runbook.py
│   └── data_architect_overview.py
├── javadoc-theme/
│   ├── snapdragon.css             (overrides standard doclet stylesheet)
│   ├── header.html                (gradient brand strip)
│   └── footer.html                (repo / architecture / README links)
└── scripts/
    ├── build-pdf.sh
    ├── build-slides.sh
    ├── build-javadocs.sh
    └── install-hooks.sh
```

Output paths (`architecture/build/`, `slides/build/`, `javadoc-output/`) are git-ignored at both `docs/publishing/.gitignore` and the repo root `.gitignore`.

## Test plan

- [ ] `make docs-pdf` produces `docs/publishing/architecture/build/architecture.pdf` (~530 KB, 28 pp).
- [ ] `make docs-slides` produces six `.pptx` files under `docs/publishing/slides/build/`, all valid Microsoft PowerPoint 2007+ format.
- [ ] `make docs-javadocs` populates `docs/publishing/javadoc-output/` with themed HTML — `index.html` includes the Snapdragon brand strip and links `snapdragon.css` from `resource-files/`.
- [ ] `make docs-clean && make docs-all` rebuilds everything from scratch with no errors.
- [ ] `git status` shows only sources + Makefile + README as untracked under `docs/publishing/`; no PDF / pptx / HTML artefacts.
- [ ] Verify `make docs-install-hooks` is idempotent (run twice; second run reports "already installed").
- [ ] Open the PDF in any PDF viewer; verify TOC, hyperlinks back to GitHub, and TikZ diagrams render.
- [ ] Open the six `.pptx` files in PowerPoint / Keynote / LibreOffice; verify cover, section, and closing slides.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
