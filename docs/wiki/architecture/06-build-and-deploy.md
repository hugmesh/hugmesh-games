# 06 — Build and deploy

> Mirror of [`chapters/06-build-and-deploy.tex`](../../publishing/architecture/chapters/06-build-and-deploy.tex).

The repository's root [`Makefile`](../../../Makefile) is the canonical entry point. Every target is idempotent and safe to re-run; the build script for the typeset doc ([`docs/publishing/Makefile`](../../publishing/Makefile)) follows the same convention.

## Build chain

```mermaid
flowchart LR
    rust[make build-rust<br/>cargo + dockerised emscripten] --> godot[make build-godot<br/>per-game export presets]
    godot --> backend[make build-backend<br/>./mvnw package]
    rust --> backend
    godot --> frontend[make build-frontend<br/>ng build host · build-frontend-all adds 5 remotes]
    backend --> compose[docker compose up -d]
    frontend --> compose
    backend --> native[make build-backend-native<br/>GraalVM, 5-10 min]
    native -.-> e2enative[make e2e-native]
    compose --> e2e[make e2e<br/>Playwright]
    backend --> docs[make docs-pdf · docs-javadocs<br/>docs-slides · docs-userguides · docs-bundle]
```

## Native (Linux) build

```bash
make build-rust          # cargo build --release (native .so) + the dockerised wasm32-unknown-emscripten build
make build-godot         # exports every preset in every game project under games/<dir>/
make build-backend       # ./mvnw -q package -DskipTests
make build-frontend      # npm run build:prod: the host shell only
make build-frontend-all  # host plus the login, admin, api-docs, three-wizards and dodecahedron remotes
make build               # build-rust, build-godot, build-backend, build-frontend in that order
```

The `build-rust` target needs `cargo` and `docker` on `PATH` (`deps-rust` checks both and stops otherwise). It runs `build-rust-wasm` first, which builds the `wasm32-unknown-emscripten` module inside the image from [`tools/build-rust-wasm/Dockerfile`](../../../tools/build-rust-wasm/Dockerfile) (rust + emsdk, built once and cached), then `sync-bridge-addon`, then the native `.so` for the editor. There is no warn-and-continue path: without Docker the target fails.

The 14 web-native game cores are separate. `make build-games-wasm` compiles their `-wasm` shim crates to `wasm32-unknown-unknown` in the image from [`tools/build-games-wasm/Dockerfile`](../../../tools/build-games-wasm/Dockerfile), with `--offline`. It builds only: it installs nothing, so copying a fresh `.wasm` into `games/<slug>/wasm/` and the deployed bundle stays a manual, reviewable step.

`make build-godot` depends on `build-rust`, needs a `godot` binary on `PATH`, and patches each exported `index.html` (`scripts/patch-godot-html.py`).

## Docker compose (recommended)

```bash
docker compose up -d            # postgres + cassandra + casdoor + bff + frontend + remotes
make e2e                        # builds the images, starts the TLS stack, runs the Playwright suite
make e2e-native                 # same, with the BFF swapped for the GraalVM native image
```

The base `docker-compose.yml` binds the frontend port to `127.0.0.1` by default (`${FRONTEND_BIND:-127.0.0.1:}${FRONTEND_PORT:-8080}`) so a fresh clone never accidentally exposes the BFF or Postgres to the LAN. Two knobs change that:

| Goal | Knob | How |
|---|---|---|
| LAN demo / multi-device playtest | `lan.properties` + `make lan-up` (or `make lan-up-tls` for HTTPS) | Set `FRONTEND_BIND=0.0.0.0:`. Frontend re-binds to all interfaces. `make lan-info` prints reachable URLs. `lan.properties` is a docker-compose env-file — `docker compose --env-file lan.properties up -d` produces the same result without going through make. |
| Production deploy at `hugmesh.games` | `compose.prod.yml` + `PROD_FRONTEND_BIND` | Defaults to loopback (assumes upstream LB). Set `PROD_FRONTEND_BIND=0.0.0.0:` for single-VM direct-internet deploys. Edge rate limits at the host nginx level are sized for ~10 k concurrent users — see [decision 019](../decisions/019-production-rate-limits.md). |

### ⚠ Docker 29.x needs buildx

Docker 29+ deprecated the legacy in-daemon image builder. `docker compose build` will hang silently if `docker-buildx` isn't installed. The fix is a user-local install of the v0.18+ binary into `~/.docker/cli-plugins/docker-buildx` (no sudo needed), or your distro's `docker-buildx` package. The README documents the details in its "Docker buildx required" note.

### ⚠ Casdoor seed credentials

Casdoor randomises seed credentials per fresh database. First-time setup:

```bash
docker compose up -d
docker compose logs casdoor 2>&1 | grep -A4 "Casdoor seed credentials"
# Copy Client Secret + admin pass + player pass into a new .env at repo root:
cat > .env <<EOF
CASDOOR_CLIENT_ID=twclient001
CASDOOR_CLIENT_SECRET=<paste from log>
CASDOOR_ADMIN_PASS=<paste from log>
CASDOOR_PLAYER_PASS=<paste from log>
EOF
docker compose up -d --force-recreate api frontend
```

The BFF restart-loops on a Casdoor secret mismatch. See also [CLAUDE.md](../../../CLAUDE.md).

## GraalVM native image

The BFF compiles to a native binary via GraalVM `native-image`. The driver is a separate `native` build profile in [`backend/pom.xml`](../../../backend/pom.xml), plus a Dockerfile ([`backend/Dockerfile.native`](../../../backend/Dockerfile.native)) and a compose overlay ([`docker-compose.native.yml`](../../../docker-compose.native.yml)).

```bash
make build-backend-native     # docker build -f backend/Dockerfile.native -t hugmesh-gaming-bff:native (5-10 minutes, 6-8 GB RAM)
make e2e-native               # build-backend-native, then the e2e stack with docker-compose.native.yml layered on
```

The native build needs reflection hints for the DTO records that cross the JSON boundary and resource hints for the `assets/` tree. [`HugMeshRuntimeHints`](../../../backend/src/main/java/games/hugmesh/bff/aot/HugMeshRuntimeHints.java) lists them, so a new JSON-boundary DTO adds one entry there. [`R2dbcConvertersConfig`](../../../backend/src/main/java/games/hugmesh/bff/config/R2dbcConvertersConfig.java) is a different fix: it registers the `OffsetDateTime` / `LocalDateTime` to `Instant` converters that native-image would otherwise skip. In the native stack Liquibase runs in a separate init container, not in the binary. See [decision 004](../decisions/004-graalvm-native.md).

## The publishing pipeline

This document, the Javadoc, and the slide decks are produced by [`docs/publishing/`](../../publishing/). The published artefacts are git-ignored.

```bash
make docs-pdf          # docs/publishing/architecture/build/architecture.pdf
make docs-javadocs     # docs/publishing/javadoc-output/index.html
make docs-slides       # docs/publishing/slides/build/*.pptx
make docs-userguides   # docs/publishing/userguides/<name>/build/<name>.pdf
make docs-bundle       # docs/hugmesh-design-docs-<UTC-TIMESTAMP>.zip
make docs-all          # all of the above
make docs-clean        # remove every produced artefact
```

### LaTeX prerequisites

- TeX Live 2024+ with `pdflatex` (the Makefile runs two `pdflatex` passes), `tikz`, `tcolorbox`, `titlesec`, `csquotes`, `enumitem`, `inconsolata` and `fontawesome`, per [`docs/publishing/README.md`](../../publishing/README.md).
- On Debian / Ubuntu: `apt install texlive-latex-extra texlive-fonts-extra texlive-pictures`.
- On Gentoo: the `tex-live` portage package with USE `extra` produces the same set.

### Javadoc theming

[`backend/pom.xml`](../../../backend/pom.xml) configures the Maven Javadoc plugin to pass [`docs/publishing/javadoc-theme/hugmesh.css`](../../publishing/javadoc-theme/hugmesh.css) to the javadoc tool as `--add-stylesheet`; the page header is inline in the pom. The override is additive — the standard doclet's default stylesheet still loads first; the HugMesh CSS layers on top.

### PowerPoint generator

The slide decks are generated by Python scripts under [`docs/publishing/slides/`](../../publishing/slides/). Each script imports `python-pptx` (through the shared `_hugmesh_deck.py`), builds the deck programmatically, and writes the `.pptx` file to `docs/publishing/slides/build/`. The scripts are deliberately written so a non-Python audience can edit slide content by editing the data structure at the top of each file.

### `make gen-api` poisoning `openapi.json`

If the BFF is unreachable on `:8080`, the host nginx serves Angular's `index.html` for any unknown path with HTTP 200, so a naive `curl /v3/api-docs` happily writes HTML to `openapi.json`. The Makefile writes the response to a temp file and replaces `openapi.json` only if it parses as JSON; if you see "end of stream" parser errors during `npm run gen`, wipe `frontend/openapi.json` (it's git-ignored) and rebuild.

## CI hooks

A pre-push hook installer is shipped at [`docs/publishing/scripts/install-hooks.sh`](../../publishing/scripts/install-hooks.sh) (`make docs-install-hooks` runs it). It installs `.git/hooks/pre-push` that re-runs `make docs-pdf` on any push that touches `docs/publishing/` or `docs/PLAN-module-federation-and-native.md`. Failures abort the push so a broken build can't land on `main`. The hook is opt-in; the script is idempotent and prints what it changed.

---

Previous: [05 — API + service reference](05-api-reference.md) · Next: [07 — Extending the portal](07-extending.md).
