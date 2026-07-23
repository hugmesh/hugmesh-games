# Run + extend multi-user e2e specs

How to add or fix a Playwright spec that drives two browser contexts through the lobby + chapter UI and asserts on what each iframe sees over SSE.

## Prereqs

- `make e2e` brings the full stack up under the `integration-test` Spring profile (deterministic Casdoor seed passwords, TLS, verbose multiplayer logging) and runs the suite in Docker. No host-side Playwright install needed.
- The e2e image `tests/` directory is COPYed into the Playwright container at build time. After editing a spec, run `docker compose -f docker-compose.yml -f docker-compose.e2e.yml build e2e` before invoking the suite.
- All e2e specs live under [`e2e/tests/`](../../../e2e/tests/). Helpers under [`e2e/tests/helpers/`](../../../e2e/tests/helpers/).

## The two race conditions you have to navigate

Multi-user specs have failed twice in the past for boring race-condition reasons. Both are handled by helpers; understanding them keeps you from re-introducing the bug.

### 1. The capture listener attaches AFTER the iframe has already drained

The wrapper around the WASM iframe relays SSE events as `postMessage({ type: 'SD_SSE_EVENT' })`. If your test's `window.addEventListener('message', …)` runs after the wrapper has already posted, those events are lost.

Fix: install the listener via `BrowserContext.addInitScript`, which runs in every iframe BEFORE its own scripts. Use [`installSseCapture(context)`](../../../e2e/tests/helpers/sse-bridge.ts) right after creating the context:

```ts
const ctx1 = await newE2eContext(browser);
await installSseCapture(ctx1);   // ← before newPage()
const p1  = await ctx1.newPage();
```

The capture writes events to `window.__captureSseEvents`. `drainCapturedEvents(page)` reads + clears the queue, falling back to `window._twSseEventQueue` (BridgeGlue's own queue) when our listener missed a message.

### 2. The wrapper subscribes AFTER WASM compile finishes

The iframe's wrapper opens its session-scoped `EventSource` only after `game_bridge.gd` posts `SD_SSE_SUBSCRIBE`, which only fires once the WASM compile is done. On a cold image that's 15–30 s. If you `sendControl()` before the subscription opens, the receiver's queue is empty for boring reasons unrelated to your assertion.

Fix: gate every `sendControl` on `waitForResponse` matching the wrapper's GET to `/api/game/stream?sessionId=<id>`:

```ts
await Promise.all([
  pA.waitForResponse(r => r.url().includes(`/api/game/stream?sessionId=${sessionAId}`), { timeout: 60_000 }),
  pB.waitForResponse(r => r.url().includes(`/api/game/stream?sessionId=${sessionBId}`), { timeout: 60_000 }),
]);
// Wrappers are now hot. Safe to sendControl.
```

## The lobby flow

You CANNOT create sessions via direct API calls and then navigate to `/`. The home component reads `activeSessionId` from its own session-creation flow; without that, the chapter launcher mounts the iframe with no `?session_id=` and the wrapper subscribes to the unscoped global stream — which defeats every per-session isolation assertion.

Drive sessions through the lobby UI:

```ts
const sessionId = await createSessionViaLobby(p1, sessionName, /* characterId */ 1);
// Modal stays open with the chapter list visible.
await launchFirstChapter(p1);
```

Both helpers live inline in `multiplayer-same-session.spec.ts` and `cross-session-isolation.spec.ts`. They're spec-local because the modal markup is specific to the home component.

## Distinct character IDs per player

Two players in the same session must use distinct `character_id`s, or the BFF's `findActiveOwnerOfCharacter` returns the first one and 403s the other. The home component reads `?characterId=` from the URL as an override, so:

```ts
await createSessionViaLobby(p1, name, 1);   // P1 = character 1
await joinSessionViaLobby(p2, name, 2);     // P2 = character 2
```

In **cross-session** specs (different sessions, same game), distinct character IDs are NOT strictly required because `findActiveOwnerInSession(session_id, character_id)` is the scope-aware lookup — but it's still good hygiene.

## Strict assertions, not soft warnings

The two multiplayer specs both pin contracts strictly:

- `multiplayer-same-session.spec.ts` — P2's iframe MUST receive `>= 1` `SD_SSE_EVENT` after P1 moves.
- `cross-session-isolation.spec.ts` — session A MUST hear its own movement; session B MUST NOT receive any event carrying session A's character.

Neither spec has a "soft warn when the iframe lacks a session-scoped subscription" branch any more — the lobby flow + `waitForResponse` make that observable, and a missing subscription should fail loudly.

The `SessionIsolationTest` Junit unit pin under [`backend/src/test/java/games/hugmesh/bff/service/SessionIsolationTest.java`](../../../backend/src/test/java/games/hugmesh/bff/service/SessionIsolationTest.java) covers the contract at the BFF layer; the e2e pair is the through-every-hop counterpart (sink fan-out → SSE → Angular wrapper relay → iframe consumer).

## Adding a new multi-user spec

1. Copy the import block + two-context setup from `cross-session-isolation.spec.ts`. Always `installSseCapture` before `newPage`.
2. Drive sessions through the lobby UI (`createSessionViaLobby` / `joinSessionViaLobby`). Pass distinct character IDs.
3. `await Promise.all([pX.waitForResponse(...), pY.waitForResponse(...)])` before any `sendControl`.
4. Drain captured events with `drainCapturedEvents(page)` after the action, then assert with `toBeGreaterThan(0)` (positive) or `toEqual([])` (negative).
5. Rebuild the e2e image (`docker compose -f docker-compose.yml -f docker-compose.e2e.yml build e2e`) and run with `make e2e` or the targeted variant.

## Where to look next

- [`e2e/tests/helpers/sse-bridge.ts`](../../../e2e/tests/helpers/sse-bridge.ts) — the capture listener + drain helpers.
- [`e2e/tests/helpers/roles.ts`](../../../e2e/tests/helpers/roles.ts) — the `SEED_USERS` catalogue, `login`, `sendControl`, `newE2eContext`.
- [Decision 010 — Per-session SSE sink isolation](../decisions/010-per-session-sse-sinks.md): the BFF-side contract these specs pin from the outside.
- The [maintainer runbook](../guides/maintainer-runbook.md): incident response when the contract DOES break in production.
