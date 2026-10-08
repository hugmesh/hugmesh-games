# Run + extend multi-user e2e specs

How to add or fix a Playwright spec that drives two browser contexts through the lobby and chapter UI and checks what each iframe receives over SSE.

## Prereqs

- `make e2e` builds the images (including `e2e`), brings the full stack up under the `integration-test` Spring profile (TLS, verbose multiplayer logging), runs the suite in Docker, then tears the stack down (`down -v`). No host-side Playwright install needed.
- Every seed user shares one per-run password, `E2E_PASSWORD`. `make` generates it and saves it in `.e2e-credentials`. It is not a fixed value.
- The e2e image copies `e2e/tests/` in at build time. `make e2e` rebuilds the image on every run, so a spec edit needs nothing extra. To `docker compose run` against a stack that is already up, rebuild first with `docker compose -f docker-compose.yml -f docker-compose.e2e.yml build e2e`. Export `E2E_PASSWORD` first (`set -a; . .e2e-credentials; set +a`), because the compose file refuses to parse without it.
- Specs live in [`e2e/tests/`](../../../e2e/tests/). Helpers live in [`e2e/tests/helpers/`](../../../e2e/tests/helpers/).

## The two race conditions you have to navigate

Multi-user specs have failed on two races. Helpers handle both. Know them so you do not bring the bug back.

### 1. The capture listener attaches AFTER the iframe has already drained

The wrapper around the WASM iframe relays SSE events as `postMessage({ type: 'SD_SSE_EVENT' })`. If your test adds its `window.addEventListener('message', …)` after the wrapper has posted, it misses those events.

Fix: install the listener with `BrowserContext.addInitScript`, which runs in every iframe BEFORE the iframe's own scripts. Call [`installSseCapture(context)`](../../../e2e/tests/helpers/sse-bridge.ts) right after you create the context:

```ts
const ctx1 = await newE2eContext(browser);
await installSseCapture(ctx1);   // ← before newPage()
const p1  = await ctx1.newPage();
```

The capture writes events to `window.__captureSseEvents`. `drainCapturedEvents(page)` reads and clears that queue. It also reads `window._twSseEventQueue` (BridgeGlue's own queue) in case our listener missed a message.

### 2. The wrapper subscribes AFTER WASM compile finishes

The iframe's wrapper opens its session-scoped `EventSource` only after `game_bridge.gd` posts `SD_SSE_SUBSCRIBE`. That happens once the WASM compile finishes, which takes 15–30 s on a cold image. If you call `sendControl()` before the subscription opens, the receiver's queue is empty for a reason that has nothing to do with your assertion.

Fix: wait for the wrapper's GET to `/api/game/stream?sessionId=<id>` with `waitForResponse` before every `sendControl`:

```ts
await Promise.all([
  pA.waitForResponse(r => r.url().includes(`/api/game/stream?sessionId=${sessionAId}`), { timeout: 60_000 }),
  pB.waitForResponse(r => r.url().includes(`/api/game/stream?sessionId=${sessionBId}`), { timeout: 60_000 }),
]);
// Wrappers are now hot. Safe to sendControl.
```

## The lobby flow

Do not create sessions with direct API calls and then open `/`. The home component gets `activeSessionId` from its own session-creation flow. Without it, the chapter launcher mounts the iframe with no `?session_id=`, and the wrapper subscribes to the unscoped global stream. That defeats every per-session isolation assertion.

Create sessions through the lobby UI:

```ts
const sessionId = await createSessionViaLobby(p1, sessionName, /* characterId */ 1);
// Modal stays open with the chapter list visible.
await launchFirstChapter(p1);
```

`createSessionViaLobby` and `launchFirstChapter` live inline in both `multiplayer-same-session.spec.ts` and `cross-session-isolation.spec.ts`. `joinSessionViaLobby` exists only in the first. They are spec-local because the modal markup is specific to the home component.

## Distinct character IDs per player

Two players in one session need distinct `character_id`s. Otherwise the BFF's `findActiveOwnerOfCharacter` (`LIMIT 1`, no `ORDER BY`) returns one of the two owners and answers `403` to the other. The home component reads `?characterId=` from the URL as an override, so:

```ts
await createSessionViaLobby(p1, name, 1);   // P1 = character 1
await joinSessionViaLobby(p2, name, 2);     // P2 = character 2
```

In **cross-session** specs (different sessions, same game), distinct character IDs are not required, because `findActiveOwnerInSession(session_id, character_id)` looks up by session. Use them anyway.

## Strict assertions, not soft warnings

Both multiplayer specs assert strictly:

- `multiplayer-same-session.spec.ts` — P2's iframe MUST receive `>= 1` `SD_SSE_EVENT` after P1 moves.
- `cross-session-isolation.spec.ts` — session A MUST hear its own movement; session B MUST NOT receive any event carrying session A's character.

Neither spec has a "soft warn" branch for an iframe without a session-scoped subscription. The lobby flow and `waitForResponse` make that observable, so a missing subscription fails the test. (Some comments in `cross-session-isolation.spec.ts` still describe the old warn-and-continue behaviour. The assertions do not.)

The JUnit test [`SessionIsolationTest`](../../../backend/src/test/java/games/hugmesh/bff/service/SessionIsolationTest.java) covers the contract in the BFF. The e2e pair covers every hop: sink fan-out, SSE, the Angular wrapper relay, the iframe consumer.

## Adding a new multi-user spec

1. Copy the import block + two-context setup from `cross-session-isolation.spec.ts`. Always `installSseCapture` before `newPage`.
2. Drive sessions through the lobby UI (`createSessionViaLobby` / `joinSessionViaLobby`). Pass distinct character IDs.
3. `await Promise.all([pX.waitForResponse(...), pY.waitForResponse(...)])` before any `sendControl`.
4. Drain captured events with `drainCapturedEvents(page)` after the action, then assert with `toBeGreaterThan(0)` (positive) or `toEqual([])` (negative).
5. Run `make e2e`. It rebuilds the e2e image itself.

## Where to look next

- [`e2e/tests/helpers/sse-bridge.ts`](../../../e2e/tests/helpers/sse-bridge.ts) — the capture listener and drain helpers.
- [`e2e/tests/helpers/roles.ts`](../../../e2e/tests/helpers/roles.ts) — the `SEED_USERS` catalogue, `login`, `sendControl`, `newE2eContext`.
- [Decision 010 — Per-session SSE sink isolation](../decisions/010-per-session-sse-sinks.md): the BFF contract these specs check from outside.
- The [maintainer runbook](../guides/maintainer-runbook.md): what to do when the contract breaks in production.
