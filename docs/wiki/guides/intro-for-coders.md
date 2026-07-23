# Intro for coders

> Mirror of [`docs/publishing/userguides/intro-for-coders/guide.tex`](../../publishing/userguides/intro-for-coders/guide.tex).

A 12-section orientation for someone who has shipped some code in some language and is brand new to this codebase. Assumes nothing about Spring, Angular, Godot, or federation; points at where to read more.

## What is HugMesh Games?

A browser-native portal that hosts multiple Godot 4 games on one page. The Angular shell wraps each game with HUD, login, admin; a Spring Boot BFF streams game state and controls over Server-Sent Events. PostgreSQL holds the catalog and player state, Cassandra is the cross-pod movement cache and audit log, and Casdoor handles OAuth2 / OIDC behind a federated login remote.

The portal is delivered as one `docker compose` stack of roughly twelve containers: a host shell, four federated route or game remotes, an asset-tier nginx for the WASM bundles, the BFF, two databases, the identity provider, and a few init containers.

## Three sentences

The host shell is a native-federation Angular host that loads its `login`, `admin`, and per-game routes from sibling bundles served by per-remote nginx containers.

The BFF runs on Spring Boot 4.0 WebFlux + R2DBC + JDBC, exposes the SSE stream, the federation manifest, the upload pipeline, and the per-game launch / access endpoints, and persists state in Postgres with a Cassandra cache.

Each Godot game lives in its own project under [`games/<slug>/`](../../../games/), exports a self-contained WASM bundle, and is delivered to the browser through the dedicated `wasm-fragment` nginx remote with the COOP / COEP / CORP headers WASM games need.

## Three-tier topology

See the diagram in [02 — Runtime architecture](../architecture/02-runtime.md#three-tier-topology).

## Where the code lives

| Path | Purpose |
|---|---|
| [`frontend/`](../../../frontend/) | Angular host (one project). |
| [`frontend/projects/`](../../../frontend/projects/) | The four federated remotes (`login`, `admin`, `games-three-wizards`, `games-dodecahedron`). |
| [`wasm-fragment/`](../../../wasm-fragment/) | Asset-tier nginx that serves Godot bundles. |
| [`backend/`](../../../backend/) | Spring Boot BFF (WebFlux + R2DBC). |
| [`games/<slug>/`](../../../games/) | One Godot project per game. |
| [`extensions/hugmesh-bridge/`](../../../extensions/hugmesh-bridge/) | Reusable Rust GDExtension (native + WASM). |
| [`docker/`](../../../docker/), [`casdoor/`](../../../casdoor/), [`e2e/`](../../../e2e/) | Init containers, identity config, end-to-end tests. |

## How a player click reaches a frame

See the sequence diagram in [02 — Runtime architecture](../architecture/02-runtime.md#browser-side-flow).

1. The host's `HomeComponent` reads `SCENE_TYPE_TO_GAME_SLUG` and forks the renderer based on the active chapter's scene type.
2. Federated games `POST /api/games/{slug}/launch` to mint a 256-bit URL-safe `launch_uid`.
3. `RemoteLoaderService` loads the matching per-game remote through `globalThis.__HUGMESH_LOAD_REMOTE_MODULE__`.
4. The remote mounts a Web Component that builds the iframe URL.
5. `wasm-fragment` serves `index.html` with the COOP / COEP / CORP headers WASM bundles need.
6. Godot pulls scene JSON from `GET /api/chapters/{id}/scene` and renders.

## Server-Sent Events: one stream, eight channels

`GET /api/game/stream` emits **eight typed channels** plus a 15-second keepalive:

| Channel | What it carries |
|---|---|
| `movement` | `PlayerMovement` — x, y, z, rotationY, action, pressed, tick |
| `world` | `WorldEvent` — spawn / despawn / transition |
| `character` | `CharacterEvent` — hp, mp, xp, status flags |
| `scene` | scene mount / unmount events |
| `time` | `GameTimeDto` — day, hour, minute, phase |
| `input` | generalised input events (gamepad, mouse, keyboard, touch) |
| `audio` | audio cues; auto-routed off `input` whenever `kind=='audio'` |
| `wasm-console` | forwarded WASM-iframe `error` / `warn` lines |

A 15-second keepalive fires immediately on subscribe so `EventSource.onopen` lands fast. **Every `Sinks.many()` in this project sets `autoCancel=false`** — see [decision 003](../decisions/003-autocancel-false.md). If you forget, the sink dies on the first reconnect.

## How to run it

```bash
make build                 # full source build (rust + godot + backend + frontend)
docker compose up -d       # bring the whole stack up
make e2e                   # full Playwright suite against the live stack
make e2e-native            # same, against the GraalVM native binary
```

Open `http://localhost:8080`; the home page shows the visible games for the current Casdoor user (or all games if you're admin). First run: copy the seed credentials from `docker compose logs casdoor` into a `.env` — see [the build & deploy chapter](../architecture/06-build-and-deploy.md#-casdoor-seed-credentials).

## Your first change

| You're touching… | Open this |
|---|---|
| Controller / DTO / SQL | [`backend/`](../../../backend/) + a Liquibase changeset |
| Route / login UI / admin | [`frontend/projects/<remote>/`](../../../frontend/projects/) |
| The game itself | [`games/<slug>/`](../../../games/) in the Godot editor (after `make build-rust`) |
| Runtime data flow (host shell) | `HomeComponent` + `RemoteLoaderService` |

Always run the matching tests: `./mvnw test` (BFF), `npx ng test` (Angular host), `cargo test` (Rust extension).

## Reading list

- [`README.md`](../../../README.md) — installation, build, deploy, troubleshooting.
- [`CLAUDE.md`](../../../CLAUDE.md) — contributor onboarding for both human and AI pair-programmers.
- [`docs/PLAN-module-federation-and-native.md`](../../PLAN-module-federation-and-native.md) — the eight-section plan that produced the current shape.
- [`docs/GODOT_DEVELOPER_GUIDE.md`](../../GODOT_DEVELOPER_GUIDE.md) — slug contract, presets, upload flow.
- [`docs/publishing/architecture/build/architecture.pdf`](../../publishing/architecture/) — full typeset architecture reference (`make docs-pdf`).
- [`docs/publishing/javadoc-output/index.html`](../../publishing/javadoc-output/) — branded Javadoc with marble-style references (`make docs-javadocs`).
