# Three Wizards In A Trenchcoat — Master Build
# Build chain: cargo → godot export → mvn package → ng build
#
# Prerequisites:
#   cargo       (rustup: https://rustup.rs)
#   godot       (Godot 4.5 CLI in PATH as `godot`)
#   mvn         (Maven 3.9+)
#   ng          (npm install -g @angular/cli)
#   node        (v20+)

SHELL := /bin/bash
.PHONY: all clean build build-rust build-godot build-backend build-frontend \
        dev-backend dev-frontend install deps-rust deps-frontend

GODOT        ?= godot
RUST_DIR      = game/rust
GODOT_DIR     = godot-project
FRONTEND_DIR  = frontend
BACKEND_DIR   = backend

RUST_TARGET_NATIVE  = target/release/libthree_wizards.so
RUST_TARGET_WASM    = target/wasm32-unknown-emscripten/release/three_wizards.wasm
GODOT_EXPORT_DIR    = $(FRONTEND_DIR)/src/assets/game
BACKEND_JAR         = $(BACKEND_DIR)/target/three-wizards-api-0.1.0-SNAPSHOT.jar
FRONTEND_DIST       = $(FRONTEND_DIR)/dist/three-wizards-frontend

# ── Full production build ─────────────────────────────────────────────────────

all: build

build: build-rust build-godot build-backend build-frontend
	@echo "✓ Full build complete"

# ── Rust GDExtension ──────────────────────────────────────────────────────────

build-rust: deps-rust
	@echo "→ Building Rust GDExtension (native)..."
	cd $(RUST_DIR) && cargo build --release
	mkdir -p $(GODOT_DIR)/rust_extension/bin
	cp $(RUST_DIR)/$(RUST_TARGET_NATIVE) $(GODOT_DIR)/rust_extension/bin/libthree_wizards.so
	@echo "→ Building Rust GDExtension (WASM for web export)..."
	cd $(RUST_DIR) && cargo build --release --target wasm32-unknown-emscripten 2>/dev/null || \
	  echo "  ⚠ WASM target skipped — install emscripten to enable web-native Rust"
	@echo "✓ Rust build done"

deps-rust:
	@command -v cargo >/dev/null 2>&1 || { echo "ERROR: cargo not found — install rustup"; exit 1; }
	rustup target add wasm32-unknown-emscripten 2>/dev/null || true

# ── Godot Web Export ──────────────────────────────────────────────────────────

build-godot: build-rust
	@echo "→ Exporting Godot project to WebGL..."
	@command -v $(GODOT) >/dev/null 2>&1 || { echo "ERROR: godot not found in PATH"; exit 1; }
	mkdir -p $(GODOT_EXPORT_DIR)
	cd $(GODOT_DIR) && $(GODOT) --headless --export-release "Web" \
	  "../$(GODOT_EXPORT_DIR)/index.html"; \
	  status=$$?; \
	  if [ $$status -ne 0 ]; then \
	    echo ""; \
	    echo "ERROR: Godot export failed (exit $$status)."; \
	    echo "  Most likely cause: export templates not installed."; \
	    echo "  Fix: open Godot → Editor → Manage Export Templates → download for your version."; \
	    echo "  Or set GODOT=/path/to/godot if the wrong binary is in PATH."; \
	    exit $$status; \
	  fi
	@echo "✓ Godot export done → $(GODOT_EXPORT_DIR)/"

# ── Spring Boot Backend ───────────────────────────────────────────────────────

build-backend:
	@echo "→ Building Spring Boot API..."
	@command -v mvn >/dev/null 2>&1 || { echo "ERROR: mvn not found"; exit 1; }
	cd $(BACKEND_DIR) && ./mvnw -q package -DskipTests
	@echo "✓ Backend build done → $(BACKEND_JAR)"

# ── Angular Frontend ──────────────────────────────────────────────────────────

build-frontend: deps-frontend
	@echo "→ Building Angular frontend..."
	cd $(FRONTEND_DIR) && npm run build:prod
	@echo "✓ Frontend build done → $(FRONTEND_DIST)/"

deps-frontend:
	@command -v node >/dev/null 2>&1 || { echo "ERROR: node not found"; exit 1; }
	@[ -d $(FRONTEND_DIR)/node_modules ] || (cd $(FRONTEND_DIR) && npm install)

# ── Install (first-time setup) ────────────────────────────────────────────────

install:
	@echo "→ Installing frontend dependencies..."
	cd $(FRONTEND_DIR) && npm install
	@echo "→ Generating Maven wrapper..."
	cd $(BACKEND_DIR) && mvn wrapper:wrapper -Dmaven=3.9.9 2>/dev/null || true
	@echo "✓ Install complete"

# ── Dev servers ───────────────────────────────────────────────────────────────

dev-backend:
	@echo "→ Starting Spring Boot API on :8080..."
	cd $(BACKEND_DIR) && ./mvnw clean spring-boot:run

dev-frontend:
	@echo "→ Starting Angular dev server on :4200 (proxy → :8080)..."
	cd $(FRONTEND_DIR) && npm start

# ── Clean ─────────────────────────────────────────────────────────────────────

clean:
	@echo "→ Cleaning all build artifacts..."
	cd $(RUST_DIR) && cargo clean
	rm -rf $(GODOT_EXPORT_DIR)/*.html $(GODOT_EXPORT_DIR)/*.js \
	       $(GODOT_EXPORT_DIR)/*.wasm $(GODOT_EXPORT_DIR)/*.pck \
	       $(GODOT_EXPORT_DIR)/*.png
	cd $(BACKEND_DIR) && ./mvnw clean 2>/dev/null || true
	rm -rf $(FRONTEND_DIR)/dist
	@echo "✓ Clean done"
