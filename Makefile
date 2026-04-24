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
.PHONY: all clean build build-rust build-godot patch-godot build-backend build-frontend \
        dev-backend dev-frontend install deps-rust deps-frontend seed-db deploy gen-api

GODOT        ?= godot
DB_HOST      ?= localhost
DB_PORT      ?= 5432
DB_NAME      ?= threewizards
DB_USER      ?= threewizards
PSQL         ?= psql
RUST_DIR      = game/rust
GODOT_DIR     = godot-project
FRONTEND_DIR  = frontend
BACKEND_DIR   = backend

RUST_TARGET_NATIVE  = target/release/libthree_wizards.so
RUST_TARGET_WASM    = target/wasm32-unknown-emscripten/release/three_wizards.wasm
GODOT_EXPORT_ROOT   = $(BACKEND_DIR)/src/main/resources/assets/levels
# Space-separated list of level slugs that map to export preset names.
# Add new levels here AND in godot-project/export_presets.cfg.
GODOT_LEVELS        = Cave Field
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
	@echo "→ Exporting Godot project — one WASM bundle per level..."
	@command -v $(GODOT) >/dev/null 2>&1 || { echo "ERROR: godot not found in PATH"; exit 1; }
	@for preset in $(GODOT_LEVELS); do \
	  slug=$$(echo $$preset | tr '[:upper:]' '[:lower:]'); \
	  out_dir=$(GODOT_EXPORT_ROOT)/$$slug; \
	  mkdir -p $$out_dir; \
	  echo "→ Exporting preset '$$preset' → $$out_dir/"; \
	  cd $(GODOT_DIR) && $(GODOT) --headless --export-release "$$preset" \
	    "../$$out_dir/index.html"; \
	  status=$$?; cd - >/dev/null; \
	  if [ $$status -ne 0 ]; then \
	    echo ""; \
	    echo "ERROR: Godot export of preset '$$preset' failed (exit $$status)."; \
	    echo "  Common cause: export templates not installed for your Godot version."; \
	    echo "  Fix: Godot → Editor → Manage Export Templates → download."; \
	    exit $$status; \
	  fi; \
	  python3 scripts/patch-godot-html.py $$out_dir/index.html $$out_dir/index.side.wasm; \
	done
	@echo "✓ All levels exported under $(GODOT_EXPORT_ROOT)/"

# ── Post-export index.html patches ───────────────────────────────────────────
# Godot regenerates index.html on every export. This target re-applies custom
# patches: correct file sizes (progress bar) and scene prefetch script.

patch-godot:
	@echo "→ Patching exported index.html files..."
	@for preset in $(GODOT_LEVELS); do \
	  slug=$$(echo $$preset | tr '[:upper:]' '[:lower:]'); \
	  out_dir=$(GODOT_EXPORT_ROOT)/$$slug; \
	  if [ -f $$out_dir/index.html ]; then \
	    python3 scripts/patch-godot-html.py $$out_dir/index.html $$out_dir/index.side.wasm; \
	  fi; \
	done

# ── Spring Boot Backend ───────────────────────────────────────────────────────

build-backend:
	@echo "→ Building Spring Boot API..."
	@command -v mvn >/dev/null 2>&1 || { echo "ERROR: mvn not found"; exit 1; }
	cd $(BACKEND_DIR) && ./mvnw -q package -DskipTests
	@echo "✓ Backend build done → $(BACKEND_JAR)"

# ── OpenAPI model generation ──────────────────────────────────────────────────
# Fetches the live OpenAPI spec and regenerates TypeScript models in src/app/api/.
# Requires the backend to be running on :8080 (local dev or compose).
# The resulting openapi.json is committed; generated src/app/api/ is gitignored.

gen-api: deps-frontend
	@echo "→ Generating Angular API models from OpenAPI spec..."
	@# Write to a temp file first; only replace openapi.json if we got a non-empty payload.
	@tmpfile=$$(mktemp); \
	if curl -sf http://localhost:8080/v3/api-docs -o $$tmpfile 2>/dev/null && [ -s $$tmpfile ]; then \
	  mv $$tmpfile $(FRONTEND_DIR)/openapi.json; \
	  echo "  Fetched spec from http://localhost:8080"; \
	elif docker compose ps api 2>/dev/null | grep -qE "running|Up" && \
	     docker compose exec -T api sh -c 'wget -qO- http://localhost:8080/v3/api-docs' > $$tmpfile 2>/dev/null && \
	     [ -s $$tmpfile ]; then \
	  mv $$tmpfile $(FRONTEND_DIR)/openapi.json; \
	  echo "  Fetched spec from compose api container"; \
	else \
	  rm -f $$tmpfile; \
	  echo "  Backend not reachable — using existing openapi.json (run make gen-api with backend up to refresh)"; \
	fi
	@if [ -s $(FRONTEND_DIR)/openapi.json ]; then \
	  cd $(FRONTEND_DIR) && npm run gen && echo "✓ API models generated → $(FRONTEND_DIR)/src/app/api/"; \
	else \
	  echo "  Skipped generation — no non-empty openapi.json available"; \
	fi

# ── Angular Frontend ──────────────────────────────────────────────────────────

build-frontend: deps-frontend gen-api
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

# ── Database seeding ─────────────────────────────────────────────────────────
# Upserts game records (including asset_url) into the running Postgres instance.
# Against the compose stack:  make seed-db
# Against a remote DB:        make seed-db DB_HOST=myhost DB_USER=myuser
# Against compose directly:   docker compose exec -T postgres \
#                               psql -U threewizards -d threewizards \
#                               -f /dev/stdin < scripts/seed-games.sql

seed-db:
	@echo "→ Seeding game records into $(DB_HOST):$(DB_PORT)/$(DB_NAME)..."
	@if docker compose ps postgres 2>/dev/null | grep -q "running\|Up"; then \
	  docker compose exec -T postgres \
	    psql -U $(DB_USER) -d $(DB_NAME) -f /dev/stdin < scripts/seed-games.sql; \
	else \
	  PGPASSWORD=$(DB_PASS) $(PSQL) -h $(DB_HOST) -p $(DB_PORT) -U $(DB_USER) -d $(DB_NAME) \
	    -f scripts/seed-games.sql; \
	fi
	@echo "✓ Game records seeded"

# ── Deploy (export + seed) ────────────────────────────────────────────────────
# Rebuilds the Godot WASM bundle, patches HTML, then upserts DB game records.
# Rebuilding the backend Docker image is required to pick up new classpath assets.
deploy: build-godot seed-db
	@echo "✓ Deploy done — rebuild and push the api container to serve new assets"

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
	@for preset in $(GODOT_LEVELS); do \
	  slug=$$(echo $$preset | tr '[:upper:]' '[:lower:]'); \
	  rm -f $(GODOT_EXPORT_ROOT)/$$slug/*.html \
	        $(GODOT_EXPORT_ROOT)/$$slug/*.js \
	        $(GODOT_EXPORT_ROOT)/$$slug/*.wasm \
	        $(GODOT_EXPORT_ROOT)/$$slug/*.pck \
	        $(GODOT_EXPORT_ROOT)/$$slug/*.png 2>/dev/null || true; \
	done
	cd $(BACKEND_DIR) && ./mvnw clean 2>/dev/null || true
	rm -rf $(FRONTEND_DIR)/dist
	@echo "✓ Clean done"
