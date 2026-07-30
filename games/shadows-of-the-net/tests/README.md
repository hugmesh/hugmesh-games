# `shadows-of-the-net` headless regression suites

```bash
make test-godot                       # from the repo root (part of `make test`)
# or directly:
godot4 --headless --path games/shadows-of-the-net --script res://tests/run_tests.gd
```

Exit code 0 = every suite green. 1 = a failing assertion, a suite that
asserted nothing, a suite that failed to parse, or the 180 s deadline lapsing.

## Why these exist rather than only `make e2e`

`make e2e` loads the exported WASM bundle in a real browser, which is the right
place for boot / asset / iframe coverage but a hopeless place to assert that a
*forged peer message* is rejected. Every suite here drives the room's transport
handlers directly — `_on_movement`, `_on_world`, `_dispatch` — which is exactly
what the SSE and postMessage drains call at runtime, so a hostile payload can
be injected without a browser, a session, or a BFF.

## Conventions

* `test_case.gd` is the assertion base. Subclasses override `suite_name()` and
  `run()`; `run()` may `await tree.process_frame` — `queue_free()` is deferred
  in Godot, so several of the structural regressions only become observable one
  frame later.
* Every assertion increments `checks`. A suite that silently stops asserting
  shows up as a dropped check count instead of a pass, and a suite with **zero**
  checks fails the run outright (`run_tests.gd` treats vacuity as a failure).
* `make_room()` instantiates the real `scenes/room.tscn` with all its scripts.
  Headless runs are non-web, so both bridges stay dormant and the room boots in
  NATIVE mode; a suite that wants session or matrix wiring calls
  `_setup_session()` / `_setup_matrix()` itself after setting `mode`.
* Outbound traffic is observed by swapping a recording stub into the room's
  `_session` slot **after** `_setup_session()` (see `RecordingBridge` in
  `test_seat_authority.gd`), so the real bridge still drives the signal wiring
  and the host election.
* A new suite is picked up automatically: name the file `tests/test_*.gd`.

## What each suite pins

| Suite | Regression |
|---|---|
| `test_pose_hardening.gd` | a peer's pose floats are finite and chamber-clamped before they reach a transform — `"1e400".to_float()` is `+INF`, and one non-finite Vector3 makes the renderer assert every frame *and* permanently NaNs the lerp target |
| `test_seat_authority.gd` | the host's seat map wins over a peer's self-declared seat — so `SOTN_POSE` ships none at all (a legacy five-field pose is still parsed, its trailing seat still ignored) — and a seat change (including standing up by *walking*, which emits no `seat_clicked`) reaches the host over the arbitrated channel |
| `test_session_membership.gd` | departed characterIds are pruned off the authoritative `GET /api/sessions/{id}` membership read — member churn used to fill `_avatars` to `MAX_AVATARS` with ghosts and then refuse real newcomers, because no SSE channel the iframe receives announces a departure |
| `test_stage_grant.gd` | `SOTN_STAGE` has a producer at all: the stage platform is clickable, the host arbitrates one holder at a time, and the authoritative echo lights the spot and moves the local rig |
| `test_session_world_state.gd` | session-mode media placements exist, forged surface ids are refused on both the request and the apply side, and a Gate 0b 403 demotes the false host instead of looping `SD_SESSION_EXPIRED` — dropping the owed broadcast with the state it described, so a future promotion path cannot flush the maps the demotion just emptied |
| `test_avatar_lifecycle.gd` | the nametag and speaking ring survive an `assemble()` rebuild (they are siblings of the rig, not children), and going over `MAX_AVATARS` degrades instead of throwing on every tick |
| `test_chat3d_contract.gd` | the `ready` handshake is emitted and re-announced, and the signature/alias table maps matrix-ui's named fields onto DESIGN §5's argument order |
| `test_chat3d_convergence.gd` | the ten host/game divergences a code-to-code convergence audit found after both halves were fixed independently: extended coat-block regions accepted + derived + painted, `DEFAULT_MATERIALS` pinned to the BFF validator's palette, JPEG/WebP placements decode, `MAX_MEDIA_BYTES` enforced on the transport that actually ships, nametags from the host-supplied display name, the server's bounded part-id / short-string patterns and display-name sanitisation, the server's hex range and flat `snoutLength` default, billboard avatars representable, and `expression`/`stage` kept wired |

## The JS half

`chat3d_bridge.gd` injects a block of JavaScript with
`JavaScriptBridge.eval` — the envelope router, the origin allow-list and the
ArrayBuffer→base64 normaliser. A headless Godot run can never execute it, so it
is covered separately by a plain-node functional test:

```bash
node games/_shared/test/chat3d-envelope.test.mjs    # or: make test-games-js
```

That test extracts the JS **from the `.gd` source** rather than re-typing it,
so the two cannot drift.
