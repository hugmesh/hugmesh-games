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
.PHONY: all clean build build-rust build-godot patch-godot build-backend build-backend-native build-frontend \
        dev-backend dev-frontend install deps-rust deps-frontend seed-db seed-roles deploy \
        gen-certs e2e e2e-native e2e-down e2e-logs gen-api \
        test test-rust test-backend test-frontend \
        docs-pdf docs-slides docs-javadocs docs-all docs-clean docs-install-hooks \
        docs-diagrams docs-screenshots docs-userguides

GODOT        ?= godot
DB_HOST      ?= localhost
DB_PORT      ?= 5432
DB_NAME      ?= threewizards
DB_USER      ?= threewizards
PSQL         ?= psql
RUST_DIR      = extensions/snapdragon-bridge
GAMES_DIR     = games
FRONTEND_DIR  = frontend
BACKEND_DIR   = backend

RUST_TARGET_NATIVE  = target/release/libsnapdragon_bridge.so
RUST_TARGET_WASM    = target/wasm32-unknown-emscripten/release/snapdragon_bridge.wasm
GODOT_EXPORT_ROOT   = $(BACKEND_DIR)/src/main/resources/assets/levels

# Per-game project layout. Each row maps:
#   <game-slug-dir-under-games/> : <space-separated export preset names>
# `make build-godot` iterates this list, runs each preset, and writes the
# resulting bundle to backend/.../assets/levels/<lower-preset>/. Adding a
# new game = drop a new project under games/<slug>/ + append a row here.
GAME_PROJECTS       = three-wizards-in-a-trenchcoat dodecahedron-demo
PRESETS_three-wizards-in-a-trenchcoat = Cave Field
PRESETS_dodecahedron-demo             = Dodecahedron
# Flat slug list used by `make seed-roles` and other per-game iteration.
GAME_SLUGS          = three-wizards dodecahedron

BACKEND_JAR         = $(BACKEND_DIR)/target/snapdragon-gaming-bff-0.1.0-SNAPSHOT.jar
FRONTEND_DIST       = $(FRONTEND_DIR)/dist/snapdragon-games-frontend

# ── Full production build ─────────────────────────────────────────────────────

all: build

build: build-rust build-godot build-backend build-frontend
	@echo "✓ Full build complete"

# ── Rust GDExtension ──────────────────────────────────────────────────────────

build-rust: deps-rust
	@echo "→ Building Snapdragon Bridge GDExtension (native)..."
	cd $(RUST_DIR) && cargo build --release
	@# One canonical binary in the extension dir; copy into every game project's
	@# bin/ so each project opens standalone in the Godot editor.
	mkdir -p $(RUST_DIR)/bin
	cp $(RUST_DIR)/$(RUST_TARGET_NATIVE) $(RUST_DIR)/bin/libsnapdragon_bridge.so
	@for game in $(GAME_PROJECTS); do \
	  mkdir -p $(GAMES_DIR)/$$game/bin; \
	  cp $(RUST_DIR)/$(RUST_TARGET_NATIVE) $(GAMES_DIR)/$$game/bin/libsnapdragon_bridge.so; \
	done
	@echo "→ Building Snapdragon Bridge (WASM for web export)..."
	cd $(RUST_DIR) && cargo build --release --target wasm32-unknown-emscripten 2>/dev/null || \
	  echo "  ⚠ WASM target skipped — install emscripten to enable web-native Rust"
	@echo "✓ Rust build done"

deps-rust:
	@command -v cargo >/dev/null 2>&1 || { echo "ERROR: cargo not found — install rustup"; exit 1; }
	rustup target add wasm32-unknown-emscripten 2>/dev/null || true

# ── Godot Web Export ──────────────────────────────────────────────────────────

build-godot: build-rust
	@echo "→ Exporting per-game Web bundles..."
	@command -v $(GODOT) >/dev/null 2>&1 || { echo "ERROR: godot not found in PATH"; exit 1; }
	@for game in $(GAME_PROJECTS); do \
	  case "$$game" in \
	    three-wizards-in-a-trenchcoat) presets="Cave Field";; \
	    dodecahedron-demo)             presets="Dodecahedron";; \
	    *)                             presets="";; \
	  esac; \
	  for preset in $$presets; do \
	    slug=$$(echo $$preset | tr '[:upper:]' '[:lower:]'); \
	    out_dir=$(GODOT_EXPORT_ROOT)/$$slug; \
	    mkdir -p $$out_dir; \
	    echo "→ [$$game] preset '$$preset' → $$out_dir/"; \
	    cd $(GAMES_DIR)/$$game && $(GODOT) --headless --export-release "$$preset" \
	      "../../$$out_dir/index.html"; \
	    status=$$?; cd - >/dev/null; \
	    if [ $$status -ne 0 ]; then \
	      echo "ERROR: Godot export of preset '$$preset' failed (exit $$status)."; \
	      echo "  Common cause: export templates not installed for your Godot version."; \
	      echo "  Fix: Godot → Editor → Manage Export Templates → download."; \
	      exit $$status; \
	    fi; \
	    python3 scripts/patch-godot-html.py $$out_dir/index.html $$out_dir/index.side.wasm; \
	  done; \
	done
	@echo "✓ All bundles exported under $(GODOT_EXPORT_ROOT)/"

# ── Post-export index.html patches ───────────────────────────────────────────
# Godot regenerates index.html on every export. This target re-applies custom
# patches: correct file sizes (progress bar) and scene prefetch script.

patch-godot:
	@echo "→ Patching exported index.html files..."
	@for game in $(GAME_PROJECTS); do \
	  case "$$game" in \
	    three-wizards-in-a-trenchcoat) presets="Cave Field";; \
	    dodecahedron-demo)             presets="Dodecahedron";; \
	    *)                             presets="";; \
	  esac; \
	  for preset in $$presets; do \
	    slug=$$(echo $$preset | tr '[:upper:]' '[:lower:]'); \
	    out_dir=$(GODOT_EXPORT_ROOT)/$$slug; \
	    if [ -f $$out_dir/index.html ]; then \
	      python3 scripts/patch-godot-html.py $$out_dir/index.html $$out_dir/index.side.wasm; \
	    fi; \
	  done; \
	done

# ── Spring Boot Backend ───────────────────────────────────────────────────────

build-backend:
	@echo "→ Building Spring Boot API..."
	@command -v mvn >/dev/null 2>&1 || { echo "ERROR: mvn not found"; exit 1; }
	cd $(BACKEND_DIR) && ./mvnw -q package -DskipTests
	@echo "✓ Backend build done → $(BACKEND_JAR)"

# ── Native AOT build (GraalVM) ────────────────────────────────────────────────
# Produces a single statically-linked binary — sub-second cold start, ~150 MB
# image. Build is slow (~5–10 min) and memory-hungry (~6–8 GB). The JVM build
# (`make build-backend`) remains the default; this target is opt-in until the
# native path is proven end-to-end.
#
# Phase-2 plan: docs/PLAN-module-federation-and-native.md (section D).
NATIVE_IMAGE = snapdragon-gaming-bff:native

build-backend-native:
	@echo "→ Building native-image (GraalVM, ~5–10 min)…"
	docker build -f $(BACKEND_DIR)/Dockerfile.native -t $(NATIVE_IMAGE) $(BACKEND_DIR)
	@echo "✓ Native image built → $(NATIVE_IMAGE)"

# ── End-to-end test against the native-image stack ───────────────────────────
# Same Playwright suite as `make e2e` but with the JVM api swapped for
# the GraalVM native binary via docker-compose.native.yml. Use this to
# surface any reflection / resource hint gaps that the AOT processor
# missed; fold the fixes into SnapdragonRuntimeHints.

E2E_NATIVE_FILES = $(E2E_FILES) -f docker-compose.native.yml

e2e-native: gen-certs build-backend-native
	@echo "→ Building remaining e2e images on top of the native api…"
	docker compose $(E2E_NATIVE_FILES) build frontend e2e
	@echo "→ Starting native-image stack with TLS…"
	docker compose $(E2E_NATIVE_FILES) up -d postgres cassandra cassandra-init casdoor api frontend
	@echo "→ Waiting for stack to become healthy (cassandra-init → api → frontend)…"
	@for i in $$(seq 1 60); do \
	  api_ok=0; web_ok=0; \
	  docker compose $(E2E_NATIVE_FILES) exec -T api sh -c 'wget -qO- http://localhost:8080/actuator/health' >/dev/null 2>&1 && api_ok=1; \
	  curl -ksf https://localhost:8080/api/games >/dev/null 2>&1 && web_ok=1; \
	  if [ $$api_ok -eq 1 ] && [ $$web_ok -eq 1 ]; then echo "  ✓ stack ready (attempt $$i)"; break; fi; \
	  if [ $$((i % 6)) -eq 0 ]; then \
	    echo "  … attempt $$i/60: api=$$api_ok web=$$web_ok"; \
	  fi; \
	  if [ $$i -eq 60 ]; then echo "  ✗ native stack failed to come up"; \
	    docker compose $(E2E_NATIVE_FILES) logs --tail 50 api; exit 1; fi; \
	  sleep 5; \
	done
	@echo "→ Running Playwright suite against native-image…"
	@set +e; \
	docker compose $(E2E_NATIVE_FILES) run --rm \
	  -e E2E_PLAYER_USERNAME="$(E2E_PLAYER_USER)" \
	  -e E2E_PLAYER_PASSWORD="$(E2E_PLAYER_PASS)" \
	  e2e; \
	status=$$?; \
	echo "→ Tearing down…"; \
	docker compose $(E2E_NATIVE_FILES) down -v --remove-orphans >/dev/null 2>&1 || true; \
	if [ $$status -eq 0 ]; then echo "✓ E2E (native) PASSED"; \
	else echo "✗ E2E (native) FAILED (status $$status) — see e2e/results/"; exit $$status; fi

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

# Build the host shell + every federated remote bundle (login, admin,
# games-three-wizards, games-dodecahedron). Useful for catching
# per-remote build breakage locally — `docker compose build` would
# also catch it, but at the cost of spinning up an image per project.
build-frontend-all: deps-frontend gen-api
	@echo "→ Building Angular host + 4 remote bundles..."
	cd $(FRONTEND_DIR) && npm run build:all
	@echo "✓ Full frontend build done (host + remotes) → $(FRONTEND_DIR)/dist/"

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

# ── Per-game Casdoor roles ────────────────────────────────────────────────────
# Idempotently creates one `play-<slug>` role per game slug in Casdoor. The
# GameAccess admin endpoint can then narrow a game to that role
# (scope='role', scope_value='play-<slug>') so the available-games API
# only surfaces it to permitted users. Initial seed for the bundled games
# is in casdoor/init_data.json.tpl; this target is for games added AFTER
# first deploy (e.g. uploaded via the admin console).
#
# Requires: jq + curl on the host. Reads CASDOOR_ENDPOINT (default
# http://localhost:8000) and an admin password from CASDOOR_ADMIN_PASS.

CASDOOR_ENDPOINT ?= http://localhost:8000

seed-roles:
	@command -v curl >/dev/null 2>&1 || { echo "ERROR: curl not found"; exit 1; }
	@command -v jq >/dev/null 2>&1 || { echo "ERROR: jq not found — install jq for JSON shaping"; exit 1; }
	@if [ -z "$$CASDOOR_ADMIN_PASS" ]; then \
	  echo "ERROR: CASDOOR_ADMIN_PASS not set — copy from 'docker logs <casdoor-container>' line 'Admin user : admin / …'"; \
	  exit 1; \
	fi
	@echo "→ Seeding per-game Casdoor roles via $(CASDOOR_ENDPOINT)…"
	@token=$$(curl -ksf -X POST $(CASDOOR_ENDPOINT)/api/login/oauth/access_token \
	    -d "grant_type=password" -d "client_id=$${CASDOOR_CLIENT_ID:-twclient001}" \
	    -d "client_secret=$${CASDOOR_CLIENT_SECRET:-}" \
	    -d "username=admin" -d "password=$$CASDOOR_ADMIN_PASS" 2>/dev/null \
	  | jq -r '.access_token // empty'); \
	if [ -z "$$token" ]; then echo "ERROR: Casdoor admin login failed"; exit 1; fi; \
	for slug in $(GAME_SLUGS); do \
	  role_name="play-$$slug"; \
	  echo "  - upsert role $$role_name"; \
	  curl -ksf -X POST "$(CASDOOR_ENDPOINT)/api/add-role" \
	    -H "Authorization: Bearer $$token" \
	    -H "Content-Type: application/json" \
	    -d "{\"owner\":\"threewizards\",\"name\":\"$$role_name\",\"displayName\":\"Play: $$slug\",\"description\":\"Auto-seeded by make seed-roles\",\"isEnabled\":true}" \
	    >/dev/null 2>&1 || true; \
	done
	@echo "✓ Per-game roles seeded"

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
	@echo "✓ deploy ready — rebuild api/frontend images to ship the new assets"

# ── Cross-platform end-to-end test ────────────────────────────────────────────
# `make e2e` runs the full stack with self-signed TLS + Java truststore mounted
# in the api, then drives Playwright against https://localhost:8080 to assert
# login, games list, and WASM/PCK loading on level click.
#
# Cross-platform: every dependency (openssl, JDK keytool, node, Chromium) runs
# inside Docker. Host requirements: docker + make + a POSIX shell.
#
# Override `CERT_PATH=/abs/path/to/certs` to use your own certs (must contain
# ca.crt, server.crt, server.key, keystore.p12, truststore.p12).
# Override `E2E_PLAYER_PASSWORD=...` to set the Casdoor player password.

E2E_FILES        = -f docker-compose.yml -f docker-compose.e2e.yml
E2E_PLAYER_USER ?= player
E2E_PLAYER_PASS ?= e2e-player-pass

gen-certs:
	@echo "→ Generating self-signed certs (or reusing CERT_PATH if set)…"
	@sh scripts/gen-certs.sh

e2e: gen-certs
	@echo "→ Building images with TLS layer…"
	docker compose $(E2E_FILES) build api frontend e2e wasm-fragment
	@echo "→ Starting stack with TLS…"
	docker compose $(E2E_FILES) up -d postgres cassandra cassandra-init casdoor api frontend
	@echo "→ Waiting for stack to become healthy (cassandra-init → api → frontend)…"
	@for i in $$(seq 1 60); do \
	  api_ok=0; web_ok=0; \
	  docker compose $(E2E_FILES) exec -T api sh -c 'wget -qO- http://localhost:8080/actuator/health' >/dev/null 2>&1 && api_ok=1; \
	  curl -ksf https://localhost:8080/api/games >/dev/null 2>&1 && web_ok=1; \
	  if [ $$api_ok -eq 1 ] && [ $$web_ok -eq 1 ]; then echo "  ✓ stack ready (attempt $$i)"; break; fi; \
	  if [ $$((i % 6)) -eq 0 ]; then \
	    echo "  … attempt $$i/60: api=$$api_ok web=$$web_ok"; \
	    docker compose $(E2E_FILES) ps --format 'table {{.Service}}\t{{.Status}}' | tail -n +2 | sed 's/^/      /'; \
	  fi; \
	  if [ $$i -eq 60 ]; then echo "  ✗ stack failed to come up in 5 minutes"; \
	    echo "--- api log ---"; docker compose $(E2E_FILES) logs --tail 30 api; \
	    echo "--- frontend log ---"; docker compose $(E2E_FILES) logs --tail 10 frontend; \
	    exit 1; fi; \
	  sleep 5; \
	done
	@echo "→ Running Playwright suite…"
	@# Single shell line: must capture $? from `compose run` *before* anything
	@# else clobbers it. A separate recipe line would start a fresh shell where
	@# $? is whatever the previous `echo` returned (i.e. always 0). `set +e`
	@# stops the shell aborting on the failing run so we still tear down.
	@set +e; \
	docker compose $(E2E_FILES) run --rm \
	  -e E2E_PLAYER_USERNAME="$(E2E_PLAYER_USER)" \
	  -e E2E_PLAYER_PASSWORD="$(E2E_PLAYER_PASS)" \
	  e2e; \
	status=$$?; \
	echo "→ Tearing down…"; \
	docker compose $(E2E_FILES) down -v --remove-orphans >/dev/null 2>&1 || true; \
	if [ $$status -eq 0 ]; then echo "✓ E2E PASSED"; \
	else echo "✗ E2E FAILED (status $$status) — see e2e/results/"; exit $$status; fi

e2e-down:
	docker compose $(E2E_FILES) down -v --remove-orphans

e2e-logs:
	docker compose $(E2E_FILES) logs -f --tail 50

# ── Per-project unit tests ───────────────────────────────────────────────────
# Each subproject runs its own native test framework. `make test` runs them
# all sequentially. Each one is also runnable in isolation (CI fan-out).
#
#   test-rust      → cargo test in extensions/snapdragon-bridge (host toolchain; needs cargo)
#   test-backend   → mvnw test in backend/   (host toolchain; needs JDK 21)
#   test-frontend  → ng test in a Docker container (Chromium baked in,
#                    so the host doesn't need a browser)
#
# Godot has no GDScript test suite yet — coverage of the runtime layer is
# exercised by `make e2e`, which loads the WASM bundle in a real browser.

FRONTEND_TEST_IMAGE = snapdragon-games-frontend-test

test: test-rust test-backend test-frontend
	@echo "✓ All project unit tests passed"

test-rust: deps-rust
	@echo "→ Running Rust unit tests…"
	cd $(RUST_DIR) && cargo test
	@echo "✓ Rust tests passed"

test-backend:
	@echo "→ Running backend (Spring Boot) unit tests…"
	@command -v mvn >/dev/null 2>&1 || { echo "ERROR: mvn not found"; exit 1; }
	cd $(BACKEND_DIR) && ./mvnw -q test
	@echo "✓ Backend tests passed"

test-frontend:
	@echo "→ Building frontend test image (chromium baked in)…"
	docker build -q -f $(FRONTEND_DIR)/Dockerfile.test -t $(FRONTEND_TEST_IMAGE) $(FRONTEND_DIR) >/dev/null
	@echo "→ Running Angular (Karma) unit tests in container…"
	docker run --rm --init \
	  -v $(PWD)/$(FRONTEND_DIR):/work \
	  -w /work \
	  $(FRONTEND_TEST_IMAGE) \
	  sh -c '\
	    if [ ! -d node_modules ] || ! npm ls karma-jasmine >/dev/null 2>&1; then \
	      echo "[test-frontend] installing deps (one-shot)…"; \
	      npm install --silent --no-audit --no-fund; \
	    fi; \
	    npm test \
	  '
	@echo "✓ Frontend tests passed"

# ── Dev servers ───────────────────────────────────────────────────────────────

dev-backend:
	@echo "→ Starting Spring Boot API on :8080..."
	cd $(BACKEND_DIR) && ./mvnw clean spring-boot:run

dev-frontend:
	@echo "→ Starting Angular dev server on :4200 (proxy → :8080)..."
	cd $(FRONTEND_DIR) && npm start

# ── Publishing pipeline ───────────────────────────────────────────────────────
# All output (PDF, .pptx, themed Javadoc HTML) is git-ignored. Run any of
# these targets to regenerate from source. See docs/publishing/README.md
# for the full description and the per-deck content layout.

docs-pdf:
	@$(MAKE) -C docs/publishing pdf

docs-slides:
	@$(MAKE) -C docs/publishing slides

docs-javadocs:
	@$(MAKE) -C docs/publishing javadocs

docs-diagrams:
	@$(MAKE) -C docs/publishing diagrams

docs-screenshots:
	@$(MAKE) -C docs/publishing screenshots

docs-userguides:
	@$(MAKE) -C docs/publishing userguides

docs-all:
	@$(MAKE) -C docs/publishing all

docs-clean:
	@$(MAKE) -C docs/publishing clean

docs-install-hooks:
	@bash docs/publishing/scripts/install-hooks.sh

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
