# P4 — wiring the Rust sim cores into the BFF (decision record)

P2 gave the BFF a genre-agnostic `SimCore` / `SimCoreFactory` boundary and a
deterministic Java reference sim that proves the authoritative tick loop. P4
puts the real engines behind that same boundary: `hugmesh-rts-core` and
`hugmesh-fps-core` (in `extensions/`, committed 5173c8e; fixed-point,
bit-exact `replay(seed, log)`).

## Status

A decision record costs nothing to write. This one has a spike behind it, and
no more.

- **Built, test scope only:** the FPS core runs in the JVM through Chicory 1.7.5
  (`backend/pom.xml`, `<scope>test</scope>`). `WasmSimCore` and
  `WasmSimCoreFactory` (kind `fps-arena-wasm`) live under
  `backend/src/test/java/games/hugmesh/bff/service/realtime/wasm/`. The shim
  crate is `extensions/hugmesh-fps-core-wasm`. Its `.wasm` and a golden-vector
  file are committed under `backend/src/test/resources/wasm/`.
- **Not built:** an RTS shim crate (`extensions/hugmesh-rts-core-wasm` does not
  exist). Nothing in `src/main` loads a `.wasm`. The only `SimCoreFactory` the
  running BFF registers is `ReferenceArenaSimFactory` (`reference-arena`, the
  Java reference sim).
- **Not built:** lag-comp rewind, the replay viewer, and the e2e from step 4.
  No FPS or RTS title is in the catalog.

## Options considered

| | How | Verdict |
|---|---|---|
| **A. WASM-in-JVM (Chicory)** | Build the crate's existing `wasm32` target. Run it server-side in [Chicory](https://github.com/dylibso/chicory), a pure-Java WASM runtime with no native dependencies. | **Chosen** |
| B. Java FFM (Panama) to the cdylib | Native `.so` via cargo; `java.lang.foreign` bindings | Rejected for now. FFM was preview on the Java 21 target when this was decided (`--enable-preview` everywhere; the BFF now targets Java 25, `backend/pom.xml`, where FFM is final). It also couples the Maven image build to a cargo toolchain, and a native crash takes the pod down. |
| C. Sidecar process | Sim runs in its own container, BFF talks IPC | Rejected: per-match process management, IPC latency inside a 30 Hz tick, and an extra deployable. None is needed at current scale. |

## Why A

- **One artifact, one truth.** The browser already runs the `wasm32` build for
  prediction. With Chicory the server runs the *same bytes*. Server authority
  and client prediction cannot drift, which is the whole point of the one-sim
  principle.
- **No native risk.** A pure-JVM interpreter turns a sim panic into a Java
  exception (the tick loop's error path), not a JVM crash. There is no JNI, no
  per-arch build, and no cargo step in the backend Maven build. The `.wasm`
  ships as a build artifact alongside the catalog upload, through the same
  pipeline that serves it to browsers via wasm-fragment.
- **Deterministic and sandboxed.** WASM is deterministic for our integer-only
  sims and has no capabilities by default. A hostile or buggy engine cannot
  touch the pod.
- **Throughput.** Interpreted WASM at 30 Hz is well within budget for sims this
  small. If profiling ever says otherwise, Chicory's AOT mode or option B can
  replace it behind the unchanged `SimCore` boundary.

## Shape of the work

1. Add a thin C-ABI/wasm export layer to each core crate: JSON over linear
   memory, matching the wire payloads the engines already emit. The FPS shim
   exports `alloc`/`dealloc` plus `fps_new(seed, config)`, `fps_step(commands)`,
   `fps_snapshot`, `fps_is_terminal`, `fps_result` and `fps_restore(replay)`.
   `fps_restore` rebuilds a match from a replay (`{config, log}`), not from a
   snapshot. The RTS shim does not exist yet.
2. `WasmSimCoreFactory implements SimCoreFactory` (planned `kind` = e.g.
   `rts-skirmish`, `fps-arena`) loads the `.wasm` from the same store
   wasm-fragment serves and instantiates one module per match. The spike loads
   the `.wasm` from the test classpath and uses kind `fps-arena-wasm`.
3. Differential tests: Java-side `WasmSimCore` against native `cargo test`
   expectations. The same seed and command log must give identical snapshot
   JSON (the gnuchess-style differential oracle, now across runtimes). The FPS
   version exists as `WasmSimCoreDifferentialTest`, against
   `golden-fps-vectors.json`.
4. Then the rest of P4, per the design doc: lag-comp rewind, a replay viewer
   built from the command log, and e2e.

Blocked on: nothing server-side (Chicory is a Maven dependency). Building a
core to `wasm32` needs the `wasm32-unknown-unknown` target, which the original
sandbox lacked. The repo now has a dockerised build for the web-native shims
(`make build-games-wasm`), but its crate list does not include
`hugmesh-fps-core-wasm`, so the committed FPS `.wasm` was built outside it.
