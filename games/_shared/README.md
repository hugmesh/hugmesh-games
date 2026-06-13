# `games/_shared/` — the reusable web-native game shell

Every **web-native** Snapdragon game frontend (chess first, then 13 more) reuses
the three ES modules in this directory. They are **vanilla JS, no build step** —
served as static files straight out of the bundle. Adding a new game is
mechanical: implement one `GAME` object and add four files.

This is the **host-authoritative** model from
[`docs/multiplayer-chess-design.md`](../../docs/multiplayer-chess-design.md): the
**host browser runs the wasm engine and is authoritative**; the BFF only
**relays** input/world events over the per-session SSE stream
(`GameStateService.reemitToSession`); the **guest re-applies and re-validates**.
The server runs no game logic.

## The three shared modules

| File | Class / fn | Responsibility |
|---|---|---|
| [`wasm-host.js`](wasm-host.js) | `WasmHost` | Loads the game's `.wasm` (`instantiateStreaming`, **empty imports**) and marshals JSON across the boundary: `call(fnName, jsonStringOrNull) -> parsedJson`. |
| [`sse-host-client.js`](sse-host-client.js) | `SessionClient` | Opens the per-session SSE relay (via the host-shell bridge **or** a direct `EventSource`), exposes `on(channel, handler)`, sends controls/inputs/world. Degrades to pure-local single-player when there's no `session_id`. |
| [`game-frame.js`](game-frame.js) | `mountGame` | Lifecycle glue: parse URL params, boot `WasmHost` + `SessionClient`, wire local-input→apply→render→relay and remote-event→apply→render. |

### `WasmHost` — the wasm marshalling contract

The contract is the **exact inverse** of the freestanding Rust shim
([`extensions/snapdragon-fps-core-wasm/src/lib.rs`](../../extensions/snapdragon-fps-core-wasm/src/lib.rs))
and **agrees byte-for-byte** with the JVM driver
([`WasmSimCore.java`](../../backend/src/test/java/games/snapdragon/bff/service/realtime/wasm/WasmSimCore.java)),
so JS, the browser, and the JVM all drive the same `.wasm` identically:

- **Bytes IN** (`host → module`): `ptr = alloc(len)` → write UTF-8 JSON into
  `[ptr, ptr+len)` → call the export with the `(ptr, len)` pair as the **last
  two args** → `dealloc(ptr, len)`.
- **Bytes OUT** (`module → host`): the export returns a **packed `i64`**
  `(ptr << 32) | (len & 0xFFFF_FFFF)`. JS reads it through
  `BigInt.asUintN(64, …)` (so a sign-extended return decodes the same way the
  JVM's `& 0xFFFF_FFFFL` masking does), takes `ptr = packed >> 32`,
  `len = packed & 0xFFFF_FFFF`, decodes the UTF-8 JSON, then **hands the buffer
  back via `dealloc(ptr, len)`** (the module leaked a `Box<[u8]>`). `packed == 0`
  ⇒ `null`.

```js
import { WasmHost } from '../_shared/wasm-host.js';

const host = new WasmHost('./wasm/snapdragon_chess.wasm');
await host.load();                              // instantiateStreaming, empty imports

host.callStatus('chess_new');                   // -> 1  (plain status int)
host.callPacked('chess_legal_moves');           // -> ["e2e4", "d2d4", …]  (packed JSON)
host.call('chess_apply_uci', 'e2e4');           // -> { type:'chess.state', … } (raw UCI token)
host.callRaw('fps_new', configJson, [seed]);    // leading args BEFORE (ptr,len)
```

Use `callPacked` / `callStatus` when you know the export's return shape;
`call` auto-detects (a value that decodes to a readable region → JSON, otherwise
a status int).

The string argument is written **verbatim** as UTF-8 — `WasmHost` does not
JSON-encode it for you. Pass exactly the bytes the export parses: a raw token
(`'e2e4'` for `chess_apply_uci`, `'…fen…'` for `chess_from_fen`) or a full JSON
document (the FPS `config`/`commands` payloads). The marshalling (alloc → write →
`(ptr,len)` → dealloc) is identical either way.

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

It auto-selects its transport:
- **Embedded** (under the Angular host shell, the normal case): asks the parent
  for the EventSource via `SD_SSE_SUBSCRIBE` and receives events as
  `SD_SSE_EVENT`; sends via `SD_API_REQUEST`. This reuses the existing
  [`ApiBridgeService`](../../frontend/src/app/services/api-bridge.service.ts) /
  [`snapdragon-shim.js`](../../wasm-fragment/snapdragon-shim.js) transport, so
  the JWT + `X-Session-Id` are attached uniformly by the host.
- **Standalone** (no distinct parent window — local dev / tests): opens its own
  `EventSource('/api/game/stream?sessionId=…')` and POSTs controls directly,
  attaching a bearer via the optional `tokenProvider`.

Relay channels (`CHANNELS`): `movement, world, character, time, scene, input,
audio, wasm-console, session-control` — the exact set
[`GameStreamController`](../../backend/src/main/java/games/snapdragon/bff/controller/GameStreamController.java)
fans out.

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
plus `handleLocalInput(evt)` — feed every captured DOM event into that so the
frame runs apply → render → relay for you.

### URL params the frame parses

The host shell appends these to the iframe `src` (Explore-confirmed):

| param | meaning |
|---|---|
| `session_id` | multiplayer session id (absent ⇒ single-player hot-seat) |
| `launch_uid` | the launch token minted for this iframe instance |
| `chapter_id` | the playable chapter (chess → the chessboard chapter) |
| `slot_index` | this slot (`0` = host/authoritative, `>0` = guest) |
| `character_id` | the character this slot drives (the relay target) |

## The four files a new game adds

Drop a `games/<slug>/` directory with **exactly four** artifacts — no build step,
all static:

| File | Purpose |
|---|---|
| `index.html` | imports `../_shared/game-frame.js`, builds the `GAME` object, calls `mountGame(GAME, { wasmUrl: './wasm/<engine>.wasm' })`, and forwards DOM events into `ctx.handleLocalInput`. |
| `<game>.js` | the `GAME` implementation (board/scene rendering + the wasm calls). Can be inlined in `index.html` for a small game. |
| `wasm/<engine>.wasm` | the freestanding `wasm32-unknown-unknown` engine (zero host imports). e.g. `chess/wasm/snapdragon_chess.wasm`. |
| `icon.svg` (or `.png`) | the catalog/launcher icon. |

The chapter row points `asset_url` at the bundle's `index.html`
(`/api/assets/levels/<slug>/index.html`) and a `federation_modules` / catalog
row registers the game — exactly the seed shape in
[`044-chess-game.xml`](../../backend/src/main/resources/db/changelog/044-chess-game.xml).

## Tests

`games/_shared/test/chess-wasm.test.mjs` already drives the real
`chess/wasm/snapdragon_chess.wasm` through the marshalling contract with
`node`. Syntax-check the shared modules with `node --check games/_shared/*.js`.
Chess wiring itself lands in the next phase — these modules are the
dependency-free foundation it builds on.
