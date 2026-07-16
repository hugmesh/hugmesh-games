//! Snapdragon Bridge GDExtension.
//!
//! The reusable translation + streaming layer between the Snapdragon Games
//! federated host (Angular) and any embedded Godot WASM bundle. All game
//! code (scenes, player controllers, level scripts) lives in plain GDScript
//! inside the host project (`games/<slug>/`) and consumes signals exposed
//! by [`HostBridge`]. The bridge has two modes:
//!
//! - **Streaming**: running inside a browser tab where the parent module has
//!   wired up `window._twSceneEvents` (a JS-side queue populated by the
//!   parent's EventSource). The bridge drains that queue every frame and
//!   re-emits each event as a typed Godot signal. Player input fed via
//!   `HostBridge.send_input(...)` is pushed into `window._twInputQueue`
//!   for the parent module to forward back to the BFF.
//!
//! - **Standalone**: running inside the Godot editor's local-play (F5). No
//!   JavaScriptBridge, no parent module. The bridge synthesises minimal
//!   stub events so a level designer can drive the player + camera with
//!   keyboard input and verify their scene without spinning up the full
//!   stack. Input passes straight through to the same signals.
//!
//! Mode is auto-detected on `_ready()` from `OS.has_feature("web")`. Game
//! code never branches on mode — it just connects to bridge signals.

use godot::prelude::*;

mod bridge_api_client;
mod bridge_glue;
mod chess_engine;
mod connect_four_engine;
mod host_bridge;
mod multiplayer_lobby;

pub use bridge_api_client::BridgeApiClient;
pub use bridge_glue::BridgeGlue;
pub use chess_engine::ChessEngine;
pub use connect_four_engine::ConnectFourEngine;
pub use host_bridge::HostBridge;
pub use multiplayer_lobby::SnapdragonMultiplayerLobby;

struct SnapdragonBridge;

#[gdextension]
unsafe impl ExtensionLibrary for SnapdragonBridge {}

#[cfg(test)]
mod tests {
    // Most of this crate is wired to the Godot runtime and can't be
    // exercised in a plain `cargo test` (no engine to host the classes).
    // End-to-end coverage of the gdext bindings runs in the Playwright
    // suite (`make e2e`).
    #[test]
    fn crate_compiles_against_host_target() {
        assert_eq!(2 + 2, 4);
    }
}
