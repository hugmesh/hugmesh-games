# P4 — wiring the Rust sim cores into the BFF (decision record)

P2 gives the BFF a genre-agnostic `SimCore` / `SimCoreFactory` boundary with a
deterministic Java reference sim proving the authoritative tick loop. P4 swaps
the real engines in behind that same boundary: `hugmesh-rts-core` and
`hugmesh-fps-core` (extensions/, committed 5173c8e — fixed-point,
bit-exact `replay(seed, log)`).

## Options considered

| | How | Verdict |
|---|---|---|
| **A. WASM-in-JVM (Chicory)** | Build the crate's existing `wasm32` target; run it server-side in [Chicory](https://github.com/dylibso/chicory), a pure-Java zero-native-dependency WASM runtime | **Chosen** |
| B. Java FFM (Panama) to the cdylib | Native `.so` via cargo; `java.lang.foreign` bindings | Rejected for now: FFM is preview on the Java 21 target (`--enable-preview` everywhere), couples the Maven image build to a cargo toolchain, native crash takes the pod down |
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

1. Add a thin C-ABI/wasm export layer to each core crate (`init(seed, config)`,
   `step(commands_json) -> snapshot_json`, `restore(snapshot_json)`,
   `is_terminal`, `result_json`) mirroring `SimCore` — JSON over linear memory,
   matching the wire payloads the engines already emit.
2. `WasmSimCoreFactory implements SimCoreFactory` (`kind` = e.g. `rts-skirmish`,
   `fps-arena`): loads the `.wasm` from the same store wasm-fragment serves,
   instantiates one module per match.
3. Differential tests: Java-side `WasmSimCore` vs native `cargo test`
   expectations — same seed + command log ⇒ identical snapshot JSON (the
   gnuchess-style differential oracle, now cross-runtime).
4. Then the rest of P4 per the design doc: lag-comp rewind, replay viewer from
   the command log, e2e.

Blocked on: nothing server-side (Chicory is a Maven dependency); the `wasm32`
builds of the cores need `rustup target add wasm32-unknown-unknown`, absent in
the current sandbox — produce the `.wasm` in the toolchain environment that
also does the Godot P3 exports.
