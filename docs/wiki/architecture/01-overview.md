# 01 — Overview

> Mirror of [`chapters/01-overview.tex`](../../publishing/architecture/chapters/01-overview.tex).

**HugMesh Games** is a multi-game portal that runs in the browser. One Angular shell loads a 20-entry catalog on demand: 19 titles, plus Character Forge, a 3D character maker that changelog 062 registers as a game. A Spring Boot BFF streams game state and controls over Server-Sent Events. Casdoor (OAuth2 / OIDC) handles sign-in behind a federated login remote.

The catalog ships **two delivery models** (see [The two game families](#the-two-game-families)):

- **Godot 4 WASM** titles: *Three Wizards In A Trenchcoat*, *Dodecahedron Demo*, *Chess (Godot)*, *Connect Four (Godot)*, *Shadows of the Net – CHAT* and *Character Forge*.
- **Web-native Rust→wasm32** titles: 14 board, puzzle and duel games. They run host-authoritative over the SSE relay.

## Three sentences

The host shell is a **native-federation** Angular host. It loads its `login`, `admin` and per-game routes from sibling bundles, each served by its own nginx container on the compose network.

The BFF builds as a fat JAR or as a **GraalVM native image**. It exposes the SSE stream, the federation manifest, the upload pipeline and the per-game launch and access endpoints. It keeps state in PostgreSQL, with a Cassandra cross-pod cache.

Each game lives in its own project under [`games/<slug>/`](../../../games/) and exports a self-contained WASM bundle. The browser gets that bundle from the `wasm-fragment` nginx remote (or the BFF asset path), with the COOP / COEP / CORP headers WASM needs. This holds for Godot 4 exports and for web-native Rust→wasm32 canvas bundles alike.

## The two game families

| Family | Titles | Engine + delivery | Authority |
|---|---|---|---|
| **Godot 4 WASM** | *Three Wizards In A Trenchcoat*, *Dodecahedron Demo*, *Chess (Godot)*, *Connect Four (Godot)*, *Shadows of the Net – CHAT*, *Character Forge* | Godot 4.6 project → self-contained WASM bundle, loaded through a per-game `remote-game` Angular Web Component (or the legacy iframe). *Chess (Godot)*, *Connect Four (Godot)*, *Shadows of the Net – CHAT* and *Character Forge* federate through a `wasm-game` row (changelogs 053 / 055 / 056 / 062). *Chess (Godot)* and *Connect Four (Godot)* reuse their matching deterministic Rust core through the bridge; the merge commit for *Connect Four (Godot)* flags its WASM export as not yet verified. *Shadows of the Net – CHAT* is pure GDScript with no Rust bridge. *Character Forge* is single-player: its row sets `sse:false` and `hostAuthoritative:false`. | The server (BFF) holds canonical game state. *Character Forge* has no shared state. |
| **Web-native Rust→wasm32** | chess, connect-four, reversi, gomoku, dots-and-boxes, go, battleship, tron, bomberman, asteroids-duel, tetris, minesweeper, sudoku, match-three (14) | A pure-Rust engine in `extensions/hugmesh-<game>/` plus a `-wasm` shim compiled to `wasm32-unknown-unknown`. The shim has zero host imports, so it runs the same in the browser, in-JVM via Chicory, and under Node. It drives a `games/<slug>/` canvas frontend built on the [`games/_shared/`](../../../games/_shared/README.md) `GAME` interface. **No per-game Angular remote**: a `federation_modules` row of `kind='wasm-game'` (changelog 051) points the host straight at the `wasm-fragment` bundle. | **Host-authoritative.** The host browser runs the engine, the BFF relays `control` / `world` over the per-session SSE stream, and the guest re-validates ([decision 016](../decisions/016-rust-extension-and-fragment-relay.md)). |

## What's in the box

| Component | Where | What it does |
|---|---|---|
| **Angular host** | [`frontend/`](../../../frontend/) | Federation host plus twelve sibling remotes (`login`, `registration`, `producer-registration`, `banned`, `admin`, `mod`, `publisher`, `api-docs`, `games-three-wizards`, `games-dodecahedron`, `games-rpg-maker`, `games-vn-maker`) and the asset-tier `wasm-fragment`. |
| **Spring Boot BFF** | [`backend/`](../../../backend/) | WebFlux + R2DBC, Liquibase via JDBC, Casdoor JWT verification, GraalVM-ready. |
| **Per-game Godot projects** | [`games/`](../../../games/) | `three-wizards-in-a-trenchcoat` (cave + field). `dodecahedron-demo` (chrome-less single-scene tech demo). `chess-godot` (Godot reference game that reuses the Rust chess core through the bridge). `connect-four-godot` (same recipe, ported second; WASM export not yet verified). `shadows-of-the-net` ("Shadows of the Net – CHAT": a pure-GDScript XR chat chamber with no Rust bridge, a 16-seat noir room, media surfaces, WebXR/OpenXR entry and a dual session/matrix transport). `character-forge` (the 3D character maker; single-player, and the host owns its controls). |
| **Web-native game projects** | [`games/`](../../../games/) + [`extensions/`](../../../extensions/) | 14 titles (chess, connect-four, reversi, gomoku, dots-and-boxes, go, battleship, tron, bomberman, asteroids-duel, tetris, minesweeper, sudoku, match-three). Each is a pure-Rust engine under `extensions/hugmesh-<game>/` plus a `-wasm` shim crate compiled to `wasm32-unknown-unknown`. A vanilla-JS canvas frontend under `games/<slug>/` implements the [`games/_shared/`](../../../games/_shared/README.md) `GAME` interface. **Host-authoritative**: the host browser runs the engine and the BFF only relays over the SSE stream. |
| **Reusable Rust GDExtension** | [`extensions/hugmesh-bridge/`](../../../extensions/hugmesh-bridge/) | A native `.so` for the editor and a `wasm32-unknown-emscripten` build for the web, with a typed signal bus any new Godot game can use. |
| **wasm-fragment nginx** | [`wasm-fragment/`](../../../wasm-fragment/) | Bakes in [`backend/src/main/resources/assets/levels/`](../../../backend/src/main/resources/assets/levels/) and serves the Godot and web-native bundles at `/_remotes/wasm-fragment/<slug>/index.html` with COOP `same-origin`, COEP `require-corp` and CORP `cross-origin`. |
| **Casdoor** | [`casdoor/`](../../../casdoor/) | Reachable only on the compose network. The BFF exchanges credentials for tokens at `POST /api/auth/token`, so the Casdoor admin UI stays internal. See [decision 008](../decisions/008-casdoor-internal.md). |
| **PostgreSQL + Cassandra** | `docker-compose.yml` services | Postgres holds the canonical relational state. Cassandra holds cross-pod state: the position cache, pod heartbeats, rate-limit events and the realtime match log (see [04 — Data model](04-data-model.md#cassandra-cache)). |

## The plan that produced this shape

The post-federation topology came out of [`docs/PLAN-module-federation-and-native.md`](../../PLAN-module-federation-and-native.md), a plan with eight sections:

| § | Topic |
|---|---|
| **A** | Module Federation refactor — kill the iframe |
| **B** | Godot editor plugin — "Export to HugMesh" |
| **C** | Hot-upload API + admin UI — drag a zip, ship a game |
| **D** | GraalVM native-image build for the BFF |
| **E** | JWT roles + per-game access policy |
| **F** | DB-backed federation discovery |
| **G** | Per-game Godot project layout |
| **H** | Cryptographic launch-UIDs |

Every section has reached its end state; the plan's status table records it. This page says why each piece exists. The rest of the chapters say how they fit.

## Audience for this reference

Engineers who extend, deploy or debug the portal. Other readers should start in the [`guides/`](../guides/) tree.

---

Next: [02 — Runtime architecture](02-runtime.md).
