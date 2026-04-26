# Snapdragon Games — publishing pipeline
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
ARCH_DIR      := $(ROOT)/architecture
BUILD_DIR     := $(ARCH_DIR)/build
SLIDES_DIR    := $(ROOT)/slides
SLIDES_BUILD  := $(SLIDES_DIR)/build
JAVADOC_OUT   := $(ROOT)/javadoc-output
THEME_DIR     := $(ROOT)/javadoc-theme
SCRIPTS_DIR   := $(ROOT)/scripts
PYTHON_VENV   := $(HOME)/.venvs/snapdragon-publishing
PYTHON        := $(PYTHON_VENV)/bin/python
PIP           := $(PYTHON_VENV)/bin/pip
BACKEND_DIR   := $(ROOT)/../../backend

PDF           := $(BUILD_DIR)/architecture.pdf

.PHONY: all pdf slides javadocs clean help \
        venv pdf-once \
        slides-intro slides-admin slides-godot \
        slides-fullstack slides-maintainer slides-data-architect

help:
	@echo "Snapdragon Games publishing pipeline"
	@echo ""
	@echo "  make pdf        — typeset architecture/architecture.tex"
	@echo "  make slides     — generate three .pptx decks"
	@echo "  make javadocs   — themed Javadoc HTML for the BFF"
	@echo "  make all        — pdf + slides + javadocs"
	@echo "  make clean      — remove every produced artefact"
	@echo ""
	@echo "  Artefacts: $(BUILD_DIR), $(SLIDES_BUILD), $(JAVADOC_OUT)"
	@echo "  All output paths are git-ignored."

all: pdf slides javadocs

# ── LaTeX architecture PDF ────────────────────────────────────────────────
pdf: $(PDF)

$(PDF): $(ARCH_DIR)/architecture.tex \
        $(ARCH_DIR)/snapdragon-theme.sty \
        $(wildcard $(ARCH_DIR)/chapters/*.tex) \
        $(wildcard $(ARCH_DIR)/diagrams/*.tex)
	@mkdir -p $(BUILD_DIR)
	@command -v pdflatex >/dev/null 2>&1 || { echo "ERROR: pdflatex not found — install texlive-latex-extra"; exit 1; }
	@echo "→ pdflatex pass 1/2"
	@cd $(ARCH_DIR) && pdflatex -halt-on-error -interaction=nonstopmode \
	  -output-directory=$(BUILD_DIR) architecture.tex >/dev/null
	@echo "→ pdflatex pass 2/2 (resolves cross-references)"
	@cd $(ARCH_DIR) && pdflatex -halt-on-error -interaction=nonstopmode \
	  -output-directory=$(BUILD_DIR) architecture.tex >/dev/null
	@echo "✓ $(PDF)"

pdf-once:
	@mkdir -p $(BUILD_DIR)
	@cd $(ARCH_DIR) && pdflatex -halt-on-error -interaction=nonstopmode \
	  -output-directory=$(BUILD_DIR) architecture.tex

# ── PowerPoint decks (python-pptx) ────────────────────────────────────────
slides: venv slides-intro slides-admin slides-godot \
        slides-fullstack slides-maintainer slides-data-architect

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
	@echo "→ mvn javadoc:javadoc with Snapdragon theme"
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
	@echo "✓ $(JAVADOC_OUT)/index.html"

# ── Clean ─────────────────────────────────────────────────────────────────
clean:
	rm -rf $(BUILD_DIR) $(SLIDES_BUILD) $(JAVADOC_OUT)
	@echo "✓ removed publishing artefacts"
