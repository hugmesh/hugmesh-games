# 01 — Overview

> Mirror of [`chapters/01-overview.tex`](../../publishing/architecture/chapters/01-overview.tex).

**Snapdragon Games** is a browser-native multi-game portal: one Angular shell loads multiple Godot 4 WebAssembly titles on demand, with a Spring Boot BFF streaming game state and controls over Server-Sent Events. Authentication runs through Casdoor (OAuth2 / OIDC) behind a federated login remote.

## Three sentences

The host shell is a **native-federation** Angular host that loads its `login`, `admin`, and per-game routes from sibling bundles served by per-remote nginx containers on the compose network.

The BFF compiles to either a fat JAR or a **GraalVM native image**, exposes the SSE stream, the federation manifest, the upload pipeline, and the per-game launch / access endpoints, and persists state in PostgreSQL with a Cassandra cross-pod cache.

Each Godot game lives in its own project under [`games/<slug>/`](../../../games/), exports a self-contained WASM bundle, and is delivered to the browser either through the BFF asset path or through the dedicated `wasm-fragment` nginx remote with the COOP / COEP / CORP headers WASM games need.

## What's in the box

| Component | Where | What it does |
|---|---|---|
| **Angular host** | [`frontend/`](../../../frontend/) | Federation host plus four sibling remotes (`login`, `admin`, `games-three-wizards`, `games-dodecahedron`) and the asset-tier `wasm-fragment`. |
| **Spring Boot BFF** | [`backend/`](../../../backend/) | WebFlux + R2DBC, Liquibase via JDBC, Casdoor JWT verification, GraalVM-ready. |
| **Per-game Godot projects** | [`games/`](../../../games/) | `three-wizards-in-a-trenchcoat` (cave + field) and `dodecahedron-demo` (chrome-less single-scene tech demo). |
| **Reusable Rust GDExtension** | [`extensions/snapdragon-bridge/`](../../../extensions/snapdragon-bridge/) | Native `.so` for the editor, `wasm32-unknown-emscripten` for the web build, with a typed signal bus that any new game can consume. |
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
