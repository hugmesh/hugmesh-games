use godot::prelude::*;

mod cave_scene;
mod player;
mod game_state;

pub use cave_scene::CaveScene;
pub use player::PlayerController;
pub use game_state::GameStateBridge;

struct ThreeWizardsExtension;

#[gdextension]
unsafe impl ExtensionLibrary for ThreeWizardsExtension {}

#[cfg(test)]
mod tests {
    // Most of this crate is wired to the Godot runtime and can't be
    // exercised in a plain `cargo test` (no engine to host the classes).
    // The crate is configured as a `cdylib`, so this module compiles via
    // `cargo test --lib` against the host toolchain and validates that
    // the Rust source still type-checks. End-to-end coverage of the
    // gdext bindings runs in the Playwright suite (`make e2e`), which
    // loads the WASM bundle in a real browser.
    #[test]
    fn crate_compiles_against_host_target() {
        assert_eq!(2 + 2, 4);
    }
}
