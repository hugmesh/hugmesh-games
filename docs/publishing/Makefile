# HugMesh Games — publishing pipeline
#
# Targets:
#   pdf        → architecture/build/architecture.pdf  (LaTeX, two-pass)
#   slides     → slides/build/{intro,admin,godot}.pptx (python-pptx)
#   javadocs   → javadoc-output/index.html             (mvn javadoc:javadoc themed)
#   all        → pdf + slides + javadocs
#   clean      → remove every produced artefact
#
# All produced artefacts are git-ignored — see ../publishing/.gitignore.
# The publishing root is ALWAYS this Makefile's directory; absolute paths
# below let `make -C docs/publishing pdf` work from any cwd.

ROOT          := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
REPO_ROOT     := $(abspath $(ROOT)/../..)
ARCH_DIR      := $(ROOT)/architecture
BUILD_DIR     := $(ARCH_DIR)/build
SLIDES_DIR    := $(ROOT)/slides
SLIDES_BUILD  := $(SLIDES_DIR)/build
JAVADOC_OUT   := $(ROOT)/javadoc-output
THEME_DIR     := $(ROOT)/javadoc-theme
SCRIPTS_DIR   := $(ROOT)/scripts
PYTHON_VENV   := $(HOME)/.venvs/hugmesh-publishing
PYTHON        := $(PYTHON_VENV)/bin/python
PIP           := $(PYTHON_VENV)/bin/pip
BACKEND_DIR   := $(ROOT)/../../backend
REPO_ROOT     := $(ROOT)/../..

PDF           := $(BUILD_DIR)/architecture.pdf

# ── Build provenance ──────────────────────────────────────────────────────
# Same project label + UTC datetime threaded through every artefact so a
# single bundle ships matched stamps. Override on the command line:
#   make all HUGMESH_PROJECT_NAME='HugMesh Games (RC1)'
HUGMESH_PROJECT_NAME  ?= HugMesh Games
HUGMESH_BUILD_DATE    := $(shell date -u +%Y-%m-%d)
HUGMESH_BUILD_TIMESTAMP := $(shell date -u +%Y-%m-%dT%H%M%SZ)
HUGMESH_BUILD_DATETIME := $(shell date -u +'%Y-%m-%d %H:%M UTC')

# `bundle` writes the archive under docs/ (NOT the repo root) with a
# UTC timestamp suffix so multiple bundles per day don't collide.
# Override BUNDLE_NAME on the command line if you need a custom suffix
# (e.g. release tag).
DOCS_DIR    := $(REPO_ROOT)/docs
BUNDLE_NAME ?= hugmesh-design-docs-$(HUGMESH_BUILD_TIMESTAMP).zip
BUNDLE_PATH := $(DOCS_DIR)/$(BUNDLE_NAME)

# Export so deck scripts (python-pptx) and shell helpers see the same
# stamp without each having to re-derive `date`.
export HUGMESH_PROJECT_NAME
export HUGMESH_BUILD_DATETIME

# build-stamp.tex is sourced by hugmesh-theme.sty (via \IfFileExists)
# so the LaTeX builds carry the SAME project + datetime string as the
# slide decks and Javadoc, instead of re-resolving \today / \currenttime
# at typeset time.
BUILD_STAMP_TEX := $(ARCH_DIR)/build-stamp.tex

.PHONY: all pdf slides javadocs clean help bundle \
        venv pdf-once diagrams schema-diagram screenshots userguides openapi-spec openapi-latex \
        slides-intro slides-admin slides-godot \
        slides-fullstack slides-maintainer slides-data-architect \
        slides-moderator slides-publisher

help:
	@echo "HugMesh Games publishing pipeline"
	@echo ""
	@echo "  make diagrams    — render every TikZ diagram + marble to PNG"
	@echo "  make screenshots — capture UI screenshots (Selenium; falls back"
	@echo "                     to placeholders if compose isn't reachable)"
	@echo "  make pdf         — typeset architecture/architecture.tex"
	@echo "  make slides      — generate eight .pptx decks (with diagrams)"
	@echo "  make userguides  — eight user-guide PDFs (one per deck)"
	@echo "  make javadocs    — themed Javadoc HTML for the BFF"
	@echo "  make bundle      — zip every produced doc into"
	@echo "                     docs/$(BUNDLE_NAME) (an all-docs/"
	@echo "                     subdir holds every PDF + .pptx flat)"
	@echo "  make all         — everything above + bundle, in dependency order"
	@echo "  make clean       — remove every produced artefact (incl. zips)"
	@echo ""
	@echo "  Project name : $(HUGMESH_PROJECT_NAME)"
	@echo "  Build stamp  : $(HUGMESH_BUILD_DATETIME)"
	@echo ""
	@echo "  All output paths are git-ignored."

all: openapi-spec diagrams screenshots openapi-latex pdf slides userguides javadocs bundle

# ── LaTeX architecture PDF ────────────────────────────────────────────────
pdf: $(PDF)

$(PDF): $(ARCH_DIR)/architecture.tex \
        $(ARCH_DIR)/hugmesh-theme.sty \
        $(wildcard $(ARCH_DIR)/chapters/*.tex) \
        $(wildcard $(ARCH_DIR)/diagrams/*.tex) \
        build-stamp
	@mkdir -p $(BUILD_DIR)
	@command -v pdflatex >/dev/null 2>&1 || { echo "ERROR: pdflatex not found — install texlive-latex-extra"; exit 1; }
	@echo "→ pdflatex pass 1/2"
	@cd $(ARCH_DIR) && pdflatex -halt-on-error -interaction=nonstopmode \
	  -output-directory=$(BUILD_DIR) architecture.tex >/dev/null
	@echo "→ pdflatex pass 2/2 (resolves cross-references)"
	@cd $(ARCH_DIR) && pdflatex -halt-on-error -interaction=nonstopmode \
	  -output-directory=$(BUILD_DIR) architecture.tex >/dev/null
	@echo "✓ $(PDF)"

pdf-once: build-stamp
	@mkdir -p $(BUILD_DIR)
	@cd $(ARCH_DIR) && pdflatex -halt-on-error -interaction=nonstopmode \
	  -output-directory=$(BUILD_DIR) architecture.tex

# build-stamp.tex is regenerated on every make invocation so
# HUGMESH_BUILD_DATETIME exactly matches the value baked into slide
# decks and Javadoc HTML in the same `make all` run. .PHONY because
# we always want it to refresh.
.PHONY: build-stamp
build-stamp:
	@printf '%% Auto-generated by docs/publishing/Makefile — do not edit.\n%% Sourced by hugmesh-theme.sty so the LaTeX builds match the\n%% datetime stamp baked into slide decks + Javadoc HTML.\n\\providecommand{\\snapprojectname}{$(HUGMESH_PROJECT_NAME)}\n\\providecommand{\\snapbuilddate}{$(HUGMESH_BUILD_DATE)}\n\\providecommand{\\snapbuildtime}{$(shell date -u +'%H:%M UTC')}\n' > $(BUILD_STAMP_TEX)

# ── PowerPoint decks (python-pptx) ────────────────────────────────────────
slides: venv slides-intro slides-admin slides-godot \
        slides-fullstack slides-maintainer slides-data-architect \
        slides-moderator slides-publisher

slides-intro:
	@mkdir -p $(SLIDES_BUILD)
	$(PYTHON) $(SLIDES_DIR)/intro_for_coders.py \
	  --output $(SLIDES_BUILD)/intro-for-coders.pptx
	@echo "✓ $(SLIDES_BUILD)/intro-for-coders.pptx"

slides-admin:
	@mkdir -p $(SLIDES_BUILD)
	$(PYTHON) $(SLIDES_DIR)/administration_manual.py \
	  --output $(SLIDES_BUILD)/administration-manual.pptx
	@echo "✓ $(SLIDES_BUILD)/administration-manual.pptx"

slides-godot:
	@mkdir -p $(SLIDES_BUILD)
	$(PYTHON) $(SLIDES_DIR)/godot_developer.py \
	  --output $(SLIDES_BUILD)/godot-developer.pptx
	@echo "✓ $(SLIDES_BUILD)/godot-developer.pptx"

slides-fullstack:
	@mkdir -p $(SLIDES_BUILD)
	$(PYTHON) $(SLIDES_DIR)/fullstack_developer.py \
	  --output $(SLIDES_BUILD)/fullstack-developer.pptx
	@echo "✓ $(SLIDES_BUILD)/fullstack-developer.pptx"

slides-maintainer:
	@mkdir -p $(SLIDES_BUILD)
	$(PYTHON) $(SLIDES_DIR)/maintainer_runbook.py \
	  --output $(SLIDES_BUILD)/maintainer-runbook.pptx
	@echo "✓ $(SLIDES_BUILD)/maintainer-runbook.pptx"

slides-data-architect:
	@mkdir -p $(SLIDES_BUILD)
	$(PYTHON) $(SLIDES_DIR)/data_architect_overview.py \
	  --output $(SLIDES_BUILD)/data-architect-overview.pptx
	@echo "✓ $(SLIDES_BUILD)/data-architect-overview.pptx"

slides-moderator:
	@mkdir -p $(SLIDES_BUILD)
	$(PYTHON) $(SLIDES_DIR)/moderator.py \
	  --output $(SLIDES_BUILD)/moderator.pptx
	@echo "✓ $(SLIDES_BUILD)/moderator.pptx"

slides-publisher:
	@mkdir -p $(SLIDES_BUILD)
	$(PYTHON) $(SLIDES_DIR)/publisher.py \
	  --output $(SLIDES_BUILD)/publisher.pptx
	@echo "✓ $(SLIDES_BUILD)/publisher.pptx"

venv:
	@if [ ! -x "$(PYTHON)" ]; then \
	  command -v python3.12 >/dev/null 2>&1 || { echo "ERROR: python3.12 required for venv"; exit 1; }; \
	  echo "→ creating venv at $(PYTHON_VENV)"; \
	  python3.12 -m venv $(PYTHON_VENV); \
	  $(PIP) install --quiet --upgrade pip; \
	  $(PIP) install --quiet python-pptx; \
	fi

# ── Themed Javadoc ────────────────────────────────────────────────────────
javadocs:
	@mkdir -p $(JAVADOC_OUT)
	@command -v mvn >/dev/null 2>&1 || { echo "ERROR: mvn not found"; exit 1; }
	@echo "→ mvn javadoc:javadoc with HugMesh theme"
	@# Theme + stylesheet config live in backend/pom.xml's
	@# maven-javadoc-plugin block — no extra -D flags needed here.
	cd $(BACKEND_DIR) && mvn -q -DskipTests javadoc:javadoc
	@echo "→ copying generated HTML → $(JAVADOC_OUT)"
	@rm -rf $(JAVADOC_OUT)/*
	@if [ -d $(BACKEND_DIR)/target/reports/apidocs ]; then \
	  cp -R $(BACKEND_DIR)/target/reports/apidocs/. $(JAVADOC_OUT)/; \
	elif [ -d $(BACKEND_DIR)/target/site/apidocs ]; then \
	  cp -R $(BACKEND_DIR)/target/site/apidocs/. $(JAVADOC_OUT)/; \
	else \
	  echo "ERROR: javadoc output not found in target/{reports,site}/apidocs"; \
	  exit 1; \
	fi
	@# Inject the build stamp into every page's footer so a printed /
	@# emailed page is traceable to a specific build. The maven-javadoc-
	@# plugin's <bottom> emits one fixed div on each page; we insert a
	@# matching stamp line immediately after it. NOTE: the literal `&`
	@# in `&middot;` MUST be escaped in the sed replacement (`&` would
	@# otherwise be replaced with the matched text).
	@find $(JAVADOC_OUT) -name '*.html' -print0 | xargs -0 -I{} \
	  sed -i 's|<div class="snap-footer">|<div class="snap-footer"><span class="snap-buildstamp">$(HUGMESH_PROJECT_NAME) \&middot; $(HUGMESH_BUILD_DATETIME)</span><br>|' {}
	@echo "✓ $(JAVADOC_OUT)/index.html"

# ── Rendered diagrams (PNG) ───────────────────────────────────────────────
# Compile every TikZ diagram + marble standalone and convert to PNG so
# python-pptx (slides) and the user-guide LaTeX builds can embed them
# directly. Output is git-ignored.
diagrams: schema-diagram
	$(ROOT)/diagrams-rendered/render-all.sh

# Complete physical + logical schema diagram across every database in the
# stack (BFF Postgres, RPG-builder Postgres, VN-builder Postgres,
# Cassandra). Re-generated from the live Liquibase XML + Cassandra CQL
# on every build so the embedded PDF pages track the schema as it
# evolves. Emits one combined PDF + four per-database PDFs into
# diagrams-rendered/build/.
schema-diagram:
	@command -v dot >/dev/null 2>&1 || { echo "ERROR: graphviz not found — install graphviz"; exit 1; }
	@python3 $(ROOT)/scripts/build-schema-diagram.py

# ── Screenshots (Selenium) ────────────────────────────────────────────────
# Captures the live UI through a headless browser. Falls back to branded
# placeholder PNGs if the compose stack isn't reachable, so the rest of
# the publishing pipeline always succeeds. Useful when a dev stack is
# already up — `screenshots-from-e2e` is the deterministic alternative.
screenshots: venv
	$(PYTHON) $(ROOT)/screenshots/capture.py

# ── Screenshots (BDD harvest) ─────────────────────────────────────────────
# Deterministic alternative to `screenshots`: re-uses the per-step PNGs
# the BDD AfterStep hook produces under `e2e/results/artifacts/`. Every
# Casdoor seed user (admin, player1-3, mod-three-wizards, publisher-
# three-wizards, …) is logged in by the BDD per-role-login feature, so
# the harvested screenshots cover roles `capture.py` can't reach with
# only the dev stack's randomised admin+player passwords in `.env`.
#
# `screenshots-from-e2e` runs the BDD suite (which brings up the
# integration-test stack with every seed user sharing one freshly
# generated E2E_PASSWORD) and then harvests its artefacts. Skip the BDD
# run if you've JUST run `make e2e-bdd` and the artefact dir is fresh:
#     `make -C docs/publishing screenshots-harvest` does only the harvest.
screenshots-from-e2e:
	@echo "→ Running e2e-bdd to produce per-step screenshots..."
	$(MAKE) -C $(REPO_ROOT) e2e-bdd
	@$(MAKE) screenshots-harvest

screenshots-harvest:
	@echo "→ Harvesting BDD screenshots into screenshots/build/ ..."
	$(PYTHON) $(ROOT)/screenshots/harvest-from-e2e.py

# ── OpenAPI spec extraction + LaTeX rendering ─────────────────────────────
# `openapi-spec` produces docs/publishing/openapi/build/openapi.json — the
# live BFF spec (curl) merged with the editorial sidecar metadata.json
# from this directory; falls back to a hand-curated openapi.fallback.json
# when the BFF isn't reachable. `openapi-latex` then renders the merged
# spec as LaTeX fragments the architecture PDF and user guides \input.
openapi-spec: venv
	$(ROOT)/openapi/build-spec.sh

openapi-latex: openapi-spec
	@mkdir -p $(ARCH_DIR) $(ROOT)/openapi/build/latex
	$(PYTHON) $(ROOT)/openapi/spec-to-latex.py \
	  --spec $(ROOT)/openapi/build/openapi.json \
	  --out-dir $(ROOT)/openapi/build/latex
	@# Drop the summary table where chapter 5 \inputs from.
	cp $(ROOT)/openapi/build/latex/openapi-summary.tex $(ARCH_DIR)/openapi-summary.tex

# ── User-guide PDFs (one per deck) ────────────────────────────────────────
# Six standalone LaTeX PDFs, sharing the architecture-PDF theme via
# TEXINPUTS. Each guide embeds the rendered diagrams (and the screenshots
# when present) so the user-guides depend on `diagrams` having run.
# `build-stamp` keeps the project + datetime stamp in sync with the
# architecture PDF and the slide decks.
userguides: diagrams build-stamp
	$(ROOT)/userguides/build-all.sh

# ── Bundle (zip every produced doc at repo root) ──────────────────────────
# Collects the architecture PDF, the six user-guide PDFs, the six slide
# decks, the themed Javadoc HTML, the merged OpenAPI spec, and the
# rendered diagram + screenshot PNGs into one zip. The filename carries
# the build date so a consumer can match a printed copy back to the
# bundle that produced it.
#
# Missing artefacts are skipped (not an error) so `make bundle` works as
# a partial-collect step too — e.g. running it after `make pdf slides`
# without having the BFF up to generate Javadoc.
bundle:
	@mkdir -p $(DOCS_DIR)
	@echo "→ assembling $(BUNDLE_NAME)"
	@# Stage every artefact under a single top-level prefix inside the
	@# zip so unzipping anywhere creates a tidy <prefix>/ directory tree.
	@# Inside the prefix:
	@#   all-docs/      — flat dir with every PDF + .pptx for easy copy.
	@#                    Filenames carry the section to avoid collisions
	@#                    (architecture-architecture.pdf etc.).
	@#   architecture/, userguides/, slides/, javadoc/, openapi/,
	@#   diagrams/, screenshots/ — structured subdirs for the full set.
	@#   MANIFEST.txt   — provenance + file listing.
	@STAGE=$$(mktemp -d -t snap-docs-XXXXXX); \
	  PREFIX_NAME="hugmesh-design-docs-$(HUGMESH_BUILD_TIMESTAMP)"; \
	  PREFIX=$$STAGE/$$PREFIX_NAME; \
	  mkdir -p $$PREFIX $$PREFIX/all-docs; \
	  if [ -f $(PDF) ]; then \
	    mkdir -p $$PREFIX/architecture && cp $(PDF) $$PREFIX/architecture/; \
	    cp $(PDF) $$PREFIX/all-docs/architecture-reference.pdf; \
	  fi; \
	  if compgen -G "$(SLIDES_BUILD)/*.pptx" >/dev/null 2>&1; then \
	    mkdir -p $$PREFIX/slides && cp $(SLIDES_BUILD)/*.pptx $$PREFIX/slides/; \
	    for f in $(SLIDES_BUILD)/*.pptx; do \
	      cp $$f $$PREFIX/all-docs/$$(basename $$f); \
	    done; \
	  fi; \
	  if compgen -G "$(ROOT)/userguides/*/build/*.pdf" >/dev/null 2>&1; then \
	    mkdir -p $$PREFIX/userguides; \
	    for f in $(ROOT)/userguides/*/build/*.pdf; do \
	      cp $$f $$PREFIX/userguides/; \
	      cp $$f $$PREFIX/all-docs/$$(basename $$f); \
	    done; \
	  fi; \
	  if [ -d $(JAVADOC_OUT) ] && [ -f $(JAVADOC_OUT)/index.html ]; then \
	    mkdir -p $$PREFIX/javadoc && cp -R $(JAVADOC_OUT)/. $$PREFIX/javadoc/; \
	  fi; \
	  if [ -f $(ROOT)/openapi/build/openapi.json ]; then \
	    mkdir -p $$PREFIX/openapi && cp $(ROOT)/openapi/build/openapi.json $$PREFIX/openapi/; \
	  fi; \
	  if [ -f $(ROOT)/openapi/build/openapi-rpg.json ]; then \
	    mkdir -p $$PREFIX/openapi && cp $(ROOT)/openapi/build/openapi-rpg.json $$PREFIX/openapi/; \
	  fi; \
	  if [ -f $(ROOT)/openapi/build/openapi-vn.json ]; then \
	    mkdir -p $$PREFIX/openapi && cp $(ROOT)/openapi/build/openapi-vn.json $$PREFIX/openapi/; \
	  fi; \
	  if compgen -G "$(ROOT)/diagrams-rendered/build/*.png" >/dev/null 2>&1; then \
	    mkdir -p $$PREFIX/diagrams && cp $(ROOT)/diagrams-rendered/build/*.png $$PREFIX/diagrams/; \
	  fi; \
	  if compgen -G "$(ROOT)/screenshots/build/*.png" >/dev/null 2>&1; then \
	    mkdir -p $$PREFIX/screenshots && cp $(ROOT)/screenshots/build/*.png $$PREFIX/screenshots/; \
	  fi; \
	  printf '%s\n' "HugMesh Games — design docs bundle" \
	                "Built: $(HUGMESH_BUILD_DATETIME)" \
	                "Timestamp: $(HUGMESH_BUILD_TIMESTAMP)" \
	                "Project: $(HUGMESH_PROJECT_NAME)" \
	                "Repo: github.com/hugmesh/hugmesh-games" \
	                "" \
	                "Layout:" \
	                "  all-docs/   - every PDF + .pptx flat for easy copy-paste" \
	                "  architecture/, userguides/, slides/, javadoc/," \
	                "  openapi/, diagrams/, screenshots/  - structured set" \
	                "" \
	                "Contents:" > $$PREFIX/MANIFEST.txt; \
	  ( cd $$PREFIX && find . -type f -printf '  %p (%s bytes)\n' | sort >> $$PREFIX/MANIFEST.txt ); \
	  rm -f $(BUNDLE_PATH); \
	  ( cd $$STAGE && zip -qr $(BUNDLE_PATH) "$$PREFIX_NAME" ); \
	  rm -rf $$STAGE
	@echo "✓ $(BUNDLE_PATH)"
	@du -h $(BUNDLE_PATH) | awk '{printf "  size: %s\n", $$1}'

# ── Clean ─────────────────────────────────────────────────────────────────
clean:
	rm -rf $(BUILD_DIR) $(SLIDES_BUILD) $(JAVADOC_OUT) \
	       $(ROOT)/diagrams-rendered/build \
	       $(ROOT)/screenshots/build \
	       $(ROOT)/userguides/*/build \
	       $(ROOT)/openapi/build \
	       $(ARCH_DIR)/openapi-summary.tex
	@# Also remove zip bundles. Cover both the new docs-design-docs
	@# location (under docs/) AND the legacy hugmesh-docs-* path
	@# at the repo root, so a clean check is exhaustive.
	rm -f $(DOCS_DIR)/hugmesh-design-docs-*.zip $(REPO_ROOT)/hugmesh-docs-*.zip
	@echo "✓ removed publishing artefacts"
