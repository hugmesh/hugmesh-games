# `games/_shared/` — the reusable web-native game shell

Every **web-native** HugMesh game frontend (chess first, then 13 more) reuses
the three ES modules in this directory. They are **vanilla JS, no build step** —
served as static files straight out of the bundle. Adding a new game requires
implementing one `GAME` object and adding four files.

This directory implements the **host-authoritative** model from
[`docs/multiplayer-chess-design.md`](../../docs/multiplayer-chess-design.md). In
this model, **the host browser runs the wasm engine and is the single source of
truth for game state**. The BFF does not run game logic — it only **relays**
input and world events over the per-session SSE stream
(`GameStateService.reemitToSession`). The guest browser receives those relayed
events, re-applies them against its own local wasm engine, and re-validates the
result.

```mermaid
sequenceDiagram
    participant H as Host browser<br/>(slot_index=0)
    participant B as BFF (relay only)
    participant G as Guest browser<br/>(slot_index>0)

    H->>H: onLocalInput → wasm engine → new state
    H->>B: POST /api/input/{characterId}<br/>(world channel payload)
    B->>G: SSE relay — world event
    G->>G: onRemoteEvent → re-apply in<br/>local wasm → re-validate → render
    note over B: BFF stores no game state.<br/>reemitToSession fans the event<br/>to all session subscribers.
```

## The three shared modules

| File | Class / fn | Responsibility |
|---|---|---|
| [`wasm-host.js`](wasm-host.js) | `WasmHost` | Loads the game's `.wasm` (`instantiateStreaming`, **empty imports**) and marshals JSON across the boundary: `call(fnName, jsonStringOrNull) -> parsedJson`. |
| [`sse-host-client.js`](sse-host-client.js) | `SessionClient` | Opens the per-session SSE relay (via the host-shell bridge **or** a direct `EventSource`), exposes `on(channel, handler)`, sends controls/inputs/world. Degrades to pure-local single-player when there's no `session_id`. |
| [`game-frame.js`](game-frame.js) | `mountGame` | Lifecycle glue: parse URL params, boot `WasmHost` + `SessionClient`, wire local-input→apply→render→relay and remote-event→apply→render. |

### `WasmHost` — the wasm marshalling contract

The marshalling contract is the **exact inverse** of the freestanding Rust shim
([`extensions/hugmesh-fps-core-wasm/src/lib.rs`](../../extensions/hugmesh-fps-core-wasm/src/lib.rs))
and **agrees byte-for-byte** with the JVM driver
([`WasmSimCore.java`](../../backend/src/test/java/games/hugmesh/bff/service/realtime/wasm/WasmSimCore.java)).
Because all three callers (browser JS, tests, JVM) implement the same protocol,
any `.wasm` built against the Rust shim works identically in all three contexts.

The protocol in both directions:

- **Bytes IN** (`host → module`): allocate a buffer in the module's linear
  memory with `alloc(len)` → write the UTF-8 payload into `[ptr, ptr+len)` →
  call the named export, passing `(ptr, len)` as the **last two arguments** →
  free the buffer with `dealloc(ptr, len)`.
- **Bytes OUT** (`module → host`): the export returns a **packed `i64`** encoding
  `(ptr << 32) | (len & 0xFFFF_FFFF)`. JS unpacks it via
  `BigInt.asUintN(64, …)` — this handles sign extension the same way the JVM's
  `& 0xFFFF_FFFFL` masking does — then reads `ptr = packed >> 32n` and
  `len = packed & 0xFFFF_FFFFn`, decodes the UTF-8 bytes as JSON, and **frees
  the buffer with `dealloc(ptr, len)`** (the Rust side leaked a `Box<[u8]>` for
  the host to reclaim). A return value of `packed == 0` means `null`.

```js
import { WasmHost } from '../_shared/wasm-host.js';

const host = new WasmHost('./wasm/hugmesh_chess.wasm');
await host.load();                              // instantiateStreaming, empty imports

host.callStatus('chess_new');                   // -> 1  (plain status int)
host.callPacked('chess_legal_moves');           // -> ["e2e4", "d2d4", …]  (packed JSON)
host.call('chess_apply_uci', 'e2e4');           // -> { type:'chess.state', … } (raw UCI token)
host.callRaw('fps_new', configJson, [seed]);    // leading args BEFORE (ptr,len)
```

Use `callPacked` / `callStatus` when you know the export's return shape.
`call` auto-detects: if the packed return value points to a valid memory region
it decodes it as JSON; otherwise it treats the value as a plain status integer.
`callRaw` is for exports that take additional leading arguments before the
standard `(ptr, len)` pair — pass those leading values as the third argument array.

The string argument is written **verbatim** as UTF-8 bytes — `WasmHost` does not
JSON-encode it for you. Pass exactly what the export expects: a raw token
(`'e2e4'` for `chess_apply_uci`, a FEN string for `chess_from_fen`) or a full
JSON document (the FPS `config`/`commands` payloads). The alloc → write →
`(ptr,len)` → dealloc round-trip is identical regardless of the payload shape.

### `SessionClient` — transport

```js
import { SessionClient, CHANNELS, SESSION_CONTROL } from '../_shared/sse-host-client.js';

const client = new SessionClient(sessionId);     // null sessionId ⇒ single-player, no network
client.on('world', (w) => guestReapply(w));      // host's authoritative board
client.on('input', (i) => hostApplyGuestMove(i));// guest's relayed move
client.on('session-control', (e) => { /* PAUSED / RESUMED / CLOSED */ });
client.connect();

client.sendControl(characterId, { action: 'JUMP', pressed: true }); // JRPG verb path
client.sendInput(characterId, { gameSlug:'chess', kind:'control', type:'move', payload:{ uci:'e2e4' } });
client.sendWorld(characterId, 'chess', { fen, san, status, lastMove }); // host authoritative broadcast
```

It auto-selects its transport based on context:

- **Embedded** (running inside the Angular host shell — the normal production
  case): delegates to the parent window. It requests an `EventSource` via a
  `SD_SSE_SUBSCRIBE` postMessage and receives events as `SD_SSE_EVENT`; it sends
  via `SD_API_REQUEST`. This reuses the existing
  [`ApiBridgeService`](../../frontend/src/app/services/api-bridge.service.ts) /
  [`hugmesh-shim.js`](../../wasm-fragment/hugmesh-shim.js) transport, so
  the JWT and `X-Session-Id` header are attached uniformly by the host shell
  without any knowledge of them in the game iframe.
- **Standalone** (no distinct parent window — local dev or tests): opens its own
  `EventSource('/api/game/stream?sessionId=…')` and POSTs controls directly,
  attaching a bearer token via the optional `tokenProvider` callback.

Relay channels (`CHANNELS`): `movement, world, character, time, scene, input,
audio, wasm-console, session-control` — the exact set that
[`GameStreamController`](../../backend/src/main/java/games/hugmesh/bff/controller/GameStreamController.java)
fans out to session subscribers.

## The `GAME` interface a new game implements

```js
const GAME = {
  slug: 'chess',                              // catalog slug (used on the relay)

  // wasm loaded, container is the mount point. Return the initial state to render.
  async init(host, container, ctx) { /* host.callStatus('chess_new'); */ return state; },

  // Draw a state object into the container. Called after every apply.
  render(stateJson, ctx) { /* paint the board */ },

  // A captured DOM event (click/keydown/drag). Apply it AUTHORITATIVELY to the
  // wasm engine, then return what to render + what to relay (or null to ignore):
  //   { control?: {...},          // → POST /api/controls/{characterId}  (verb path)
  //     input?:   { kind, type, payload }, // → POST /api/input/{characterId} (relay)
  //     world?:   {...},          // host authoritative broadcast on the `world` channel
  //     state:    <stateJson> }   // what render() draws
  onLocalInput(evt, ctx) { const s = ctx.host.call('chess_apply_uci', uci); // raw UCI token
                           return { input: { kind:'control', type:'move', payload:{ uci } },
                                    world: s, state: s }; },

  // A relayed event arrived. Re-apply against wasm + re-validate; return the new
  // state to render (or null to ignore).
  onRemoteEvent(channel, json, ctx) { /* guest: re-apply json.payload, re-validate */ return state; },
};
```

`ctx` (the `GameContext`) carries `{ host, client, params, isHost, container }`
plus `handleLocalInput(evt)`. Feed every captured DOM event into
`ctx.handleLocalInput(evt)` — the frame then runs the full apply → render →
relay cycle on your behalf.

### URL params the frame parses

The host shell appends these query parameters to the iframe `src` URL at launch
time. `game-frame.js` reads them on mount and makes them available on `ctx.params`.

| param | meaning |
|---|---|
| `session_id` | multiplayer session id (absent ⇒ single-player hot-seat) |
| `launch_uid` | the launch token minted for this iframe instance |
| `chapter_id` | the playable chapter (chess → the chessboard chapter) |
| `slot_index` | this slot (`0` = host/authoritative, `>0` = guest) |
| `character_id` | the character this slot drives (the relay target) |

## The four files a new game adds

Drop a `games/<slug>/` directory with **exactly four** artifacts — no build step,
all served as static files:

| File | Purpose |
|---|---|
| `index.html` | Imports `../_shared/game-frame.js`, defines the `GAME` object, calls `mountGame(GAME, { wasmUrl: './wasm/<engine>.wasm' })`, and routes DOM events to `ctx.handleLocalInput`. |
| `<game>.js` | The `GAME` implementation: board or scene rendering plus the wasm call logic. For small games this can be inlined directly in `index.html`. |
| `wasm/<engine>.wasm` | The freestanding `wasm32-unknown-unknown` engine binary (zero host imports). For example: `chess/wasm/hugmesh_chess.wasm`. |
| `icon.svg` (or `.png`) | The catalog and launcher icon. |

To wire the game into the catalog, add two database rows via a Liquibase changeset
(see [`044-chess-game.xml`](../../backend/src/main/resources/db/changelog/044-chess-game.xml)
for the exact seed shape):

1. A `chapters` row whose `asset_url` points to `/api/assets/levels/<slug>/index.html`.
2. A `federation_modules` row with `kind='wasm-game'` (and `exposed_module=NULL`) — this
   is what tells the host shell to treat the game as web-native rather than a Godot
   bundle, without requiring a new Angular remote or any frontend change.

```mermaid
flowchart TB
    A[Write GAME object<br/>games/&lt;slug&gt;/&lt;game&gt;.js] --> B[Build wasm engine<br/>wasm32-unknown-unknown]
    B --> C[Drop four files under<br/>games/&lt;slug&gt;/]
    C --> D[Copy bundle into<br/>backend/.../assets/levels/&lt;slug&gt;/]
    D --> E[Add Liquibase changeset:<br/>chapters + federation_modules rows]
    E --> F[Rebuild wasm-fragment image<br/>docker compose build wasm-fragment]
    F --> G[Game appears in<br/>host shell catalog]
```

## Tests

`games/_shared/test/chess-wasm.test.mjs` drives the real
`chess/wasm/hugmesh_chess.wasm` through the marshalling contract using `node`
— no browser required. Run it with:

```bash
node games/_shared/test/chess-wasm.test.mjs
```

Syntax-check the shared modules without executing them:

```bash
node --check games/_shared/*.js
```

When adding a new game, add a corresponding `games/_shared/test/<slug>-wasm.test.mjs`
that exercises at least `init`, one move application, and the packed-return decoding
for your engine. These modules are the dependency-free foundation all per-game
implementations build on.
