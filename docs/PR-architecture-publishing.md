# Architecture publishing pipeline — PDF, marbles, six guides, OpenAPI explorer

> **PR helper.** Open the PR with:
>
> ```
> gh pr create --base main --head feature/architecture-publishing \
>   --title "Architecture publishing pipeline — PDF, marbles, six user guides, OpenAPI explorer" \
>   --body-file docs/PR-architecture-publishing.md
> ```
>
> Or paste the body below into the GitHub web UI.

---

## Summary

Self-contained, end-to-end publishing pipeline under `docs/publishing/` plus a federated `api-docs` Angular remote that mounts at `/admin/docs`. Five artefact families, all git-ignored — only the LaTeX, Python, CSS, TypeScript, and pom.xml plugin sources are committed.

### Artefacts

- **32-page LaTeX architecture reference** ([`architecture/architecture.tex`](docs/publishing/architecture/architecture.tex)) — custom `hugmesh-theme.sty` mirroring the project's brand palette (`#7C4DFF` accent on near-black). Seven chapters covering overview, runtime architecture, module federation, data model, API reference, build & deploy, and extending the portal. Five layout TikZ diagrams + seven per-controller marble diagrams + an auto-generated OpenAPI summary table. Every section links back to source files on GitHub via `\reposrc` / `\snapfile` macros.

- **Six python-pptx decks** in [`slides/`](docs/publishing/slides/), each targeting a distinct audience:
  - `intro-for-coders.pptx` — entry-level coder tour
  - `administration-manual.pptx` — `ROLE_ADMIN` operators
  - `godot-developer.pptx` — game developers shipping a Godot title
  - `fullstack-developer.pptx` — BFF + frontend engineers
  - `maintainer-runbook.pptx` — on-call / SRE, seven incident runbooks
  - `data-architect-overview.pptx` — steering committee, regulatory + DR posture
  
  Built from a shared `_hugmesh_deck.py` layout module so all six share cover/section/closing chrome. Slides embed rendered diagrams + screenshots in a half-width image panel.

- **Six user-guide PDFs** in [`userguides/`](docs/publishing/userguides/) — one LaTeX guide per slide deck. 11–15 pages each, sharing the architecture-PDF theme via `TEXINPUTS`. All embed the rendered TikZ diagrams + marble diagrams + screenshots.

- **Themed Maven Javadoc** at [`javadoc-output/`](docs/publishing/javadoc-output/) via [`backend/pom.xml`](backend/pom.xml)'s `maven-javadoc-plugin` config + [`javadoc-theme/`](docs/publishing/javadoc-theme/) (`hugmesh.css` + `header.html` + `footer.html`).

- **Federated `api-docs` Angular remote** at [`frontend/projects/api-docs/`](frontend/projects/api-docs/) — a navigator-style swagger/javadoc replacement generated FROM the BFF's OpenAPI spec, mounted at `/admin/docs` behind the existing `authGuard`.

## What's new beyond a "static docs site"

### Marble diagrams with distinct shapes per Reactor type

Custom `\snapmarble*` TikZ styles in [`hugmesh-theme.sty`](docs/publishing/architecture/hugmesh-theme.sty) using **distinct shapes per Reactor type**: circle = `Mono<T>`, square = `Flux<T>` element, pentagon = `ServerSentEvent`, hexagon = R2DBC tx value, triangle = signal, star = error, bar = onComplete.

Seven controllers covered in [`marbles/`](docs/publishing/architecture/marbles/) — `auth`, `game-stream`, `level`, `control`, `federation`, `game-launch`, `game-upload`. Each diagram is followed by a `\snapmarblelegend` tabularx that lists the **relevant fields/flags** of every carried object (e.g. `UploadRequest.file`, `LaunchDraft.uid`, `ControlEvent.spellSlot`).

### Diagram → PNG rendering pipeline

[`render-all.sh`](docs/publishing/diagrams-rendered/render-all.sh) wraps each `.tex` in a standalone document, compiles with pdflatex, then converts to PNG via pdftoppm at 220 DPI. Theme is gated on `chapter`/`hyperref` being defined so it loads under both `book` and `standalone` classes. **12 PNGs** produced (5 architecture diagrams + 7 marbles).

### Selenium screenshot capture with graceful fallback

[`capture.py`](docs/publishing/screenshots/capture.py) drives **headless Firefox** (Chrome fallback) against `$HUGMESH_BASE_URL`. Seven captures: home, login, post-login, admin dashboard, admin uploads, in-game cave, in-game dodecahedron. When the stack isn't reachable: writes **branded Pillow placeholders** at matching dimensions so the slide + user-guide builds always succeed.

### OpenAPI publishing pipeline

[`docs/publishing/openapi/`](docs/publishing/openapi/) builds an editorially-enriched merged OpenAPI document and renders it as both a LaTeX summary table (chapter 5 of the architecture PDF) and a federated Angular UI:

- [`metadata.json`](docs/publishing/openapi/metadata.json) — sidecar with `longDescription`, `sampleRequest/Response`, `sse`, `binaryUpload`, marble pointers, chapter refs per tag/operation. Deliberately NOT inline in the controllers — keeps Java source focused on the contract and editorial prose iterable independently.
- [`openapi.fallback.json`](docs/publishing/openapi/openapi.fallback.json) — hand-curated OpenAPI 3 used when the BFF isn't reachable.
- [`build-spec.sh`](docs/publishing/openapi/build-spec.sh) probes `/v3/api-docs` (with JSON sanity check), falls back to the static spec, then merges via [`merge-metadata.py`](docs/publishing/openapi/merge-metadata.py) which writes editorial enrichments into `x-hugmesh` extension fields. **Result is still valid OpenAPI 3.**
- [`spec-to-latex.py`](docs/publishing/openapi/spec-to-latex.py) walks the merged spec and emits `openapi-summary.tex` (4-column tabularx of every operation) + per-tag fragments under `build/latex/per-tag/`.

### `api-docs` federated remote ([`frontend/projects/api-docs/`](frontend/projects/api-docs/))

Navigator-style top-level explorer with a tag-grouped left sidebar and three tabs:

- **Reference** ([`api-reference.component.ts`](frontend/projects/api-docs/src/app/api-reference.component.ts)) — Javadoc-style read-only view rendered from the merged spec: tag blurb, summary, long description, parameters table, request body schema, sample payloads, responses, sample event chunks.
- **Try it out** ([`api-explorer.component.ts`](frontend/projects/api-docs/src/app/api-explorer.component.ts)) — interactive: **auth-token override textbox** preloaded from `sessionStorage.tw_auth`, path/query parameter form, JSON body editor, **binary upload picker** for multipart endpoints, **in-window terminal panel** that subscribes to SSE endpoints via a fetch stream and pretty-prints incoming events.
- **PDFs & Javadoc** — links into the published reference PDFs and the themed Maven Javadoc.

All three modules exposed independently via federation (`./DocsShell`, `./ApiExplorer`, `./ApiReference`) so other hosts can embed just the parts they need.

## Build hooks

Root `Makefile` targets (delegate to `docs/publishing/Makefile`):

```
make docs-diagrams       render every TikZ diagram + marble to PNG
make docs-screenshots    capture UI screenshots (Selenium; placeholders if down)
make docs-openapi-spec   probe BFF / merge metadata → openapi.json
make docs-openapi-latex  render merged spec as LaTeX fragments
make docs-pdf            typeset architecture/architecture.tex
make docs-slides         generate six .pptx decks (with diagrams)
make docs-userguides     six user-guide PDFs (one per deck)
make docs-javadocs       themed Javadoc HTML for the BFF
make docs-all            everything above, in dependency order
make docs-clean          remove every produced artefact
make docs-install-hooks  opt-in pre-push hook that re-runs docs-pdf on touch
```

Every target is idempotent. The publishing root [`docs/publishing/Makefile`](docs/publishing/Makefile) is the source of truth; the root targets are thin delegators.

## Federation + access

- Liquibase changeset [`025-api-docs-federation.xml`](backend/src/main/resources/db/changelog/025-api-docs-federation.xml) inserts the `federation_modules` row with `metadata={mountAt:'/admin/docs',requires:'ROLE_ADMIN'}`.
- [`frontend/src/app/app.routes.ts`](frontend/src/app/app.routes.ts) — `/admin/docs` route added behind the existing `authGuard`.
- [`frontend/src/app/app.component.ts`](frontend/src/app/app.component.ts) — `FEDERATED_ROUTE_LABELS` updated so the NavigationError banner names the new route correctly.
- [`frontend/nginx.conf`](frontend/nginx.conf) — proxies `/_remotes/api-docs/` to the new container; serves `/docs/` static files.
- [`docker-compose.yml`](docker-compose.yml) — new `api-docs` service; the host frontend `depends_on` it.
- [`frontend/package.json`](frontend/package.json) — `build:remotes` chain extended.
- [`frontend/angular.json`](frontend/angular.json) — `api-docs` project registered.

## Verified

| Artefact | Pages / size | Overfull `\hbox` |
|---|---|---|
| `architecture.pdf` | 32 pages | **0** |
| `intro-for-coders.pdf` | 12 | **0** |
| `administration.pdf` | 12 | **0** |
| `godot-developer.pdf` | 15 | **0** |
| `fullstack-developer.pdf` | 11 | **0** |
| `maintainer-runbook.pdf` | 12 | **0** |
| `data-architect-overview.pdf` | 11 | **0** |
| six `.pptx` files | 50–260 KB | n/a (image-embedded) |
| 12 diagram PNGs | 220 DPI | n/a |
| Javadoc HTML | branded | n/a |
| OpenAPI merged spec | 20 ops, 11 tags | n/a |

Zero text and zero diagram overflow across all 7 PDFs. Full clean rebuild succeeds end-to-end with `make docs-all`.

## Test plan

- [ ] `make docs-clean && make docs-all` succeeds end-to-end on a fresh checkout (with `texlive-latex-extra`, `texlive-fonts-extra`, `texlive-pictures`, `python3.12`, `mvn`, Java 21 installed).
- [ ] `git status` shows zero PDF / pptx / HTML / PNG artefacts under `docs/publishing/` — only sources are tracked.
- [ ] Open `docs/publishing/architecture/build/architecture.pdf` in any PDF viewer; verify TOC, hyperlinks back to GitHub, TikZ + marble diagrams render, OpenAPI summary table appears in chapter 5.
- [ ] Open the six `.pptx` files in PowerPoint / Keynote / LibreOffice; verify cover, section, and closing slides + embedded diagrams.
- [ ] `make docs-install-hooks` is idempotent (run twice; second run reports "already installed").
- [ ] `docker compose up -d` brings up the new `api-docs` service; `/admin/docs` (logged in as a `ROLE_ADMIN` user) loads the navigator with the OpenAPI catalogue, supports try-it-out + binary upload + SSE terminal.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
