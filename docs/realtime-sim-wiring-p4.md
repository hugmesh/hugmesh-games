# P4 — wiring the Rust sim cores into the BFF (decision record)

P2 gives the BFF a genre-agnostic `SimCore` / `SimCoreFactory` boundary with a
deterministic Java reference sim proving the authoritative tick loop. P4 swaps
the real engines in behind that same boundary: `hugmesh-rts-core` and
`hugmesh-fps-core` (extensions/, committed 5173c8e — fixed-point,
bit-exact `replay(seed, log)`).

## Status

This is a decision record, and only a spike of it exists.

- **Built, test scope only:** the FPS core runs in-JVM through Chicory 1.7.5
  (`backend/pom.xml`, `<scope>test</scope>`). `WasmSimCore` and
  `WasmSimCoreFactory` (kind `fps-arena-wasm`) live under
  `backend/src/test/java/games/hugmesh/bff/service/realtime/wasm/`. The shim
  crate is `extensions/hugmesh-fps-core-wasm`; its `.wasm` and a golden-vector
  file are committed under `backend/src/test/resources/wasm/`.
- **Not built:** there is no RTS shim crate (`extensions/hugmesh-rts-core-wasm`
  does not exist). Nothing in `src/main` loads a `.wasm`. The only
  `SimCoreFactory` the running BFF registers is `ReferenceArenaSimFactory`
  (`reference-arena`, the Java reference sim).
- **Not built:** lag-comp rewind, the replay viewer, and the e2e from step 4.
  No FPS or RTS title is in the catalog.

## Options considered

| | How | Verdict |
|---|---|---|
| **A. WASM-in-JVM (Chicory)** | Build the crate's existing `wasm32` target; run it server-side in [Chicory](https://github.com/dylibso/chicory), a pure-Java zero-native-dependency WASM runtime | **Chosen** |
| B. Java FFM (Panama) to the cdylib | Native `.so` via cargo; `java.lang.foreign` bindings | Rejected for now: FFM was preview on the Java 21 target when this was decided (`--enable-preview` everywhere; the BFF now targets Java 25, `backend/pom.xml`, where FFM is final), couples the Maven image build to a cargo toolchain, native crash takes the pod down |
| C. Sidecar process | Sim runs in its own container, BFF talks IPC | Rejected: per-match process management, IPC latency inside a 30 Hz tick, an extra deployable — none needed at current scale |

## Why A

- **One artifact, one truth.** The browser already runs the `wasm32` build for
  prediction. With Chicory the server executes the *same bytes* — server
  authority and client prediction cannot drift by construction, which is the
  whole point of the one-sim principle.
- **No native risk.** Pure-JVM interpreter: a sim panic is a Java exception
  (the tick loop's error path), not a JVM crash; no JNI, no per-arch builds,
  no cargo step in the backend Maven build — the `.wasm` ships as a build
  artifact alongside the catalog upload (same pipeline that serves it to
  browsers via wasm-fragment).
- **Deterministic + sandboxed.** WASM is deterministic for our integer-only
  sims and capability-less by default — a hostile/buggy engine can't touch the
  pod.
- Throughput: interpreted WASM at 30 Hz for fixed-point sims this small is
  comfortably within budget; if profiling ever says otherwise, Chicory's AOT
  mode or option B are drop-in escalations behind the unchanged `SimCore`
  boundary.

## Shape of the work

1. Add a thin C-ABI/wasm export layer to each core crate — JSON over linear
   memory, matching the wire payloads the engines already emit. The FPS shim
   exports `alloc`/`dealloc` plus `fps_new(seed, config)`, `fps_step(commands)`,
   `fps_snapshot`, `fps_is_terminal`, `fps_result` and `fps_restore(replay)`.
   `fps_restore` rebuilds a match from a replay (`{config, log}`), not from a
   snapshot. The RTS shim does not exist yet.
2. `WasmSimCoreFactory implements SimCoreFactory` (planned `kind` = e.g.
   `rts-skirmish`, `fps-arena`): loads the `.wasm` from the same store
   wasm-fragment serves, instantiates one module per match. The spike version
   loads the `.wasm` from the test classpath and uses kind `fps-arena-wasm`.
3. Differential tests: Java-side `WasmSimCore` vs native `cargo test`
   expectations — same seed + command log ⇒ identical snapshot JSON (the
   gnuchess-style differential oracle, now cross-runtime). The FPS version
   exists as `WasmSimCoreDifferentialTest` against
   `golden-fps-vectors.json`.
4. Then the rest of P4 per the design doc: lag-comp rewind, replay viewer from
   the command log, e2e.

Blocked on: nothing server-side (Chicory is a Maven dependency). Building a
core to `wasm32` needs the `wasm32-unknown-unknown` target, which the original
sandbox lacked. The repo now has a dockerised build for the web-native shims
(`make build-games-wasm`), but its crate list does not include
`hugmesh-fps-core-wasm`, so the committed FPS `.wasm` was built outside it.
