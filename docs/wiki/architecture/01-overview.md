# 01 — Overview

> Mirror of [`chapters/01-overview.tex`](../../publishing/architecture/chapters/01-overview.tex).

**Snapdragon Games** is a browser-native multi-game portal: one Angular shell loads an 18-title catalog on demand, with a Spring Boot BFF streaming game state and controls over Server-Sent Events. Authentication runs through Casdoor (OAuth2 / OIDC) behind a federated login remote.

The catalog ships **two delivery models** (see [The two game families](#the-two-game-families)): **Godot 4 WASM** titles (*Three Wizards In A Trenchcoat*, *Dodecahedron Demo*, *Chess (Godot)*, *Connect Four (Godot)*) and **web-native Rust→wasm32** titles (14 board / puzzle / duel games) that run host-authoritative over the SSE relay.

## Three sentences

The host shell is a **native-federation** Angular host that loads its `login`, `admin`, and per-game routes from sibling bundles served by per-remote nginx containers on the compose network.

The BFF compiles to either a fat JAR or a **GraalVM native image**, exposes the SSE stream, the federation manifest, the upload pipeline, and the per-game launch / access endpoints, and persists state in PostgreSQL with a Cassandra cross-pod cache.

Each game lives in its own project under [`games/<slug>/`](../../../games/), exports a self-contained WASM bundle, and is delivered to the browser through the dedicated `wasm-fragment` nginx remote (or the BFF asset path) with the COOP / COEP / CORP headers WASM games need — whether it is a Godot 4 export or a web-native Rust→wasm32 canvas bundle.

## The two game families

| Family | Titles | Engine + delivery | Authority |
|---|---|---|---|
| **Godot 4 WASM** | *Three Wizards In A Trenchcoat*, *Dodecahedron Demo*, *Chess (Godot)*, *Connect Four (Godot)* | Godot 4.6 project → self-contained WASM bundle, loaded via a per-game `remote-game` Angular Web Component (or legacy iframe); *Chess (Godot)* / *Connect Four (Godot)* federate via a `wasm-game` row (changelogs 053 / 055) and each reuses its matching deterministic Rust core through the bridge (*Connect Four (Godot)*'s own merge commit flags the WASM export as not yet verified) | server (BFF) holds canonical game state |
| **Web-native Rust→wasm32** | chess, connect-four, reversi, gomoku, dots-and-boxes, go, battleship, tron, bomberman, asteroids-duel, tetris, minesweeper, sudoku, match-three (14) | pure-Rust engine in `extensions/snapdragon-<game>/` + a `-wasm` shim compiled to `wasm32-unknown-unknown` (zero host imports; runs identically in the browser, in-JVM via Chicory, and under Node), driving a `games/<slug>/` canvas frontend on the [`games/_shared/`](../../../games/_shared/README.md) `GAME` interface. **No per-game Angular remote** — a `federation_modules` row of `kind='wasm-game'` (changelog 051) points the host straight at the `wasm-fragment` bundle | **host-authoritative** — host browser runs the engine, BFF relays `control`/`world` over the per-session SSE stream, guest re-validates ([decision 016](../decisions/016-rust-extension-and-fragment-relay.md)) |

## What's in the box

| Component | Where | What it does |
|---|---|---|
| **Angular host** | [`frontend/`](../../../frontend/) | Federation host plus twelve sibling remotes (`login`, `registration`, `producer-registration`, `banned`, `admin`, `mod`, `publisher`, `api-docs`, `games-three-wizards`, `games-dodecahedron`, `games-rpg-maker`, `games-vn-maker`) and the asset-tier `wasm-fragment`. |
| **Spring Boot BFF** | [`backend/`](../../../backend/) | WebFlux + R2DBC, Liquibase via JDBC, Casdoor JWT verification, GraalVM-ready. |
| **Per-game Godot projects** | [`games/`](../../../games/) | `three-wizards-in-a-trenchcoat` (cave + field), `dodecahedron-demo` (chrome-less single-scene tech demo), `chess-godot` (Godot reference game reusing the Rust chess core via the bridge), and `connect-four-godot` (same recipe, ported second — WASM export not yet verified). |
| **Web-native game projects** | [`games/`](../../../games/) + [`extensions/`](../../../extensions/) | 14 titles (chess, connect-four, reversi, gomoku, dots-and-boxes, go, battleship, tron, bomberman, asteroids-duel, tetris, minesweeper, sudoku, match-three). Each is a pure-Rust engine under `extensions/snapdragon-<game>/` + a `-wasm` shim crate compiled to `wasm32-unknown-unknown`, driving a vanilla-JS canvas frontend under `games/<slug>/` that implements the [`games/_shared/`](../../../games/_shared/README.md) `GAME` interface. **Host-authoritative**: the host browser runs the engine, the BFF only relays over the SSE stream. |
| **Reusable Rust GDExtension** | [`extensions/snapdragon-bridge/`](../../../extensions/snapdragon-bridge/) | Native `.so` for the editor, `wasm32-unknown-emscripten` for the web build, with a typed signal bus that any new Godot game can consume. |
| **wasm-fragment nginx** | [`wasm-fragment/`](../../../wasm-fragment/) | Bakes [`backend/src/main/resources/assets/levels/`](../../../backend/src/main/resources/assets/levels/) and serves Godot bundles at `/_remotes/wasm-fragment/<slug>/index.html` with COOP `same-origin`, COEP `require-corp`, CORP `cross-origin`. |
| **Casdoor** | [`casdoor/`](../../../casdoor/) | Never exposed to the host; the BFF reverse-proxies the login flow so the admin UI stays internal. See [decision 008](../decisions/008-casdoor-internal.md). |
| **PostgreSQL + Cassandra** | `compose.yml` services | Postgres holds the canonical relational state; Cassandra is the cross-pod movement / session cache and a write-through audit log. |

## The plan that produced this shape

The post-federation topology landed under [`docs/PLAN-module-federation-and-native.md`](../../PLAN-module-federation-and-native.md), an eight-section plan:

| § | Topic |
|---|---|
| **A** | Module Federation refactor — kill the iframe |
| **B** | Godot editor plugin — "Export to Snapdragon" |
| **C** | Hot-upload API + admin UI — drag a zip, ship a game |
| **D** | GraalVM native-image build for the BFF |
| **E** | JWT roles + per-game access policy |
| **F** | DB-backed federation discovery |
| **G** | Per-game Godot project layout |
| **H** | Cryptographic launch-UIDs |

Every section is at a deliberate end state at the head of the federation branch. This page explains why each piece exists; the rest of this chapter set explains how they fit.

## Audience for this reference

For engineers who need to extend, deploy, or debug the portal. Other audiences are served by the [`guides/`](../guides/) tree.

---

Next: [02 — Runtime architecture](02-runtime.md).
