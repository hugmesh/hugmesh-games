//! Streaming-vs-standalone bridge node. Drop one of these into the root
//! of every Godot scene under `games/<slug>/`; the rest of the game code
//! (movement, camera, HUD, prop spawn) connects to its signals and never
//! has to know whether it's running in the federated host or the Godot
//! editor's local-play.
//!
//! Topology
//! --------
//! ```
//!   GDScript bridge_glue.gd        (per project, calls JavaScriptBridge.eval)
//!     └─ on each parent SSE event   ──► HostBridge.dispatch_event(type, json_payload)
//!     └─ on each player input        ──► HostBridge.send_input(action, json_value)
//!
//!   HostBridge (this file)
//!     • detects mode (Streaming once GDScript glue calls set_streaming_mode(true))
//!     • dispatches typed signals every game script connects to:
//!         movement_received, world_received, character_received,
//!         time_received, scene_received, scene_ready
//!     • Standalone send_input echoes back through movement_received so
//!       editor F5 still drives the player without the BFF
//! ```
//!
//! Why stringly-typed JSON payloads instead of Dictionary: the gdext
//! `Dictionary` API requires a typed `Dictionary<K, V>` on master, and
//! the parameter-pass conventions drift across snapshots. JSON strings
//! cross every gdext / Godot version; the GDScript layer JSON-parses
//! once on receipt and that's it.

use godot::classes::{INode, Node};
use godot::prelude::*;

#[derive(GodotClass)]
#[class(base=Node)]
pub struct HostBridge {
    base: Base<Node>,
    /// `true` once the GDScript glue has detected a parent module via
    /// `JavaScriptBridge.eval("typeof window._twSceneEvents !== 'undefined'")`
    /// and called [`set_streaming_mode`]. Defaults to standalone so the
    /// editor F5 path works even if the glue forgets to flip it.
    streaming: bool,
}

#[godot_api]
impl INode for HostBridge {
    fn init(base: Base<Node>) -> Self {
        Self { base, streaming: false }
    }

    fn ready(&mut self) {
        // Standalone fires a one-shot scene_ready so the game's bootstrap
        // path runs without waiting for the parent's prefetch.
        if !self.streaming {
            self.base_mut()
                .emit_signal("scene_ready", &[GString::from("{}").to_variant()]);
        }
    }
}

#[godot_api]
impl HostBridge {
    /// Per-channel signals — connect one slot per channel from GDScript:
    ///   `bridge.movement_received.connect(_on_movement)`
    /// The `payload` is a JSON string the game script parses.
    #[signal] fn movement_received(payload: GString);
    #[signal] fn world_received(payload: GString);
    #[signal] fn character_received(payload: GString);
    #[signal] fn time_received(payload: GString);
    #[signal] fn scene_received(payload: GString);
    /// Fired exactly once per scene boot. In Streaming mode the glue
    /// forwards the parent's scene_ready event; in Standalone the bridge
    /// fakes it from `_ready`.
    #[signal] fn scene_ready(payload: GString);
    /// Streaming-mode-only outbound channel. The GDScript glue connects
    /// one slot to this and forwards each payload to `window._twInputQueue`.
    #[signal] fn input_egress(payload: GString);

    // ── Generalised input + audio + diagnostics signals ────────────────
    //
    // Each carries a JSON payload matching the corresponding row in the
    // BFF's input_event_types table. Game scripts connect to whichever
    // they care about; HostBridge stays type-agnostic so a new event
    // type lands by adding a row to input_event_types + a slot in the
    // game without re-publishing the GDExtension.
    //
    // The dispatch_event() match below routes incoming "input" events
    // to the per-kind signal so the same publish-side code that works
    // with the legacy movement / world / character channels also works
    // here.
    #[signal] fn gamepad_received(payload: GString);
    #[signal] fn mouse_received(payload: GString);
    #[signal] fn keyboard_received(payload: GString);
    #[signal] fn touch_received(payload: GString);
    #[signal] fn audio_received(payload: GString);
    /// Outbound audio cue — a Godot AudioStreamPlayer can emit a JSON
    /// payload (sourceId, bus, volumeDb, loop) that the GDScript glue
    /// forwards to the host so the host devtools can mirror what's
    /// playing. Subscribe with `bridge.audio_egress.connect(...)`.
    #[signal] fn audio_egress(payload: GString);
    /// Outbound console line — the game can call
    /// `bridge.report_error(level, message, source, line)` from any
    /// GDScript handler that catches an exception; the glue forwards
    /// to `window.parent.postMessage({type:'SD_WASM_CONSOLE', ...})`.
    /// Subscribe to mirror locally; the GDScript glue ALWAYS emits.
    #[signal] fn console_egress(payload: GString);

    /// Called by the GDScript glue once it confirms a parent module is
    /// present. Flips the bridge into streaming mode so [`send_input`]
    /// stops echoing locally and instead emits `input_egress`.
    #[func]
    fn set_streaming_mode(&mut self, on: bool) {
        self.streaming = on;
        let label = if on {
            "streaming (parent module attached)"
        } else {
            "standalone (no parent — local play)"
        };
        godot_print!("[HostBridge] mode = {}", label);
    }

    /// Reports whether the bridge is in streaming mode. Useful for
    /// HUD/debug overlays.
    #[func]
    fn is_streaming(&self) -> bool {
        self.streaming
    }

    /// Called by the GDScript glue with `(type, json_payload)` for each
    /// queued parent event. Re-emits as a typed signal.
    ///
    /// Also accepts the generalised input-event kinds (gamepad / mouse /
    /// keyboard / touch / audio) — these arrive on the parent's
    /// `input` SSE channel and the glue routes them in via the same
    /// dispatch_event call. The Godot scripts subscribe to
    /// `gamepad_received` / `audio_received` etc. without caring
    /// whether the event came from the BFF SSE or a host-tier
    /// gamepad poll relayed through postMessage.
    #[func]
    fn dispatch_event(&mut self, event_type: GString, payload: GString) {
        let signal = match event_type.to_string().as_str() {
            "movement"    => "movement_received",
            "world"       => "world_received",
            "character"   => "character_received",
            "time"        => "time_received",
            "scene"       => "scene_received",
            "scene_ready" => "scene_ready",
            "gamepad"     => "gamepad_received",
            "mouse"       => "mouse_received",
            "keyboard"    => "keyboard_received",
            "touch"       => "touch_received",
            "audio"       => "audio_received",
            other => {
                godot_warn!("[HostBridge] unknown event type '{}'", other);
                return;
            }
        };
        self.base_mut().emit_signal(signal, &[payload.to_variant()]);
    }

    /// Forward an audio cue to the host. Game scripts call this on
    /// AudioStreamPlayer transitions so the host devtools mirror
    /// what's playing. JSON payload follows the audio:* row schemas
    /// (sourceId / bus / volumeDb / loop).
    #[func]
    fn report_audio(&mut self, payload: GString) {
        self.base_mut()
            .emit_signal("audio_egress", &[payload.to_variant()]);
    }

    /// Forward a console line to the host's devtools subscribers.
    /// `level` is one of `error` / `warn` / `info` / `log`. Game
    /// scripts use this when their own exception handlers catch
    /// something Godot's default print pipeline would swallow inside
    /// a WASM iframe.
    #[func]
    fn report_error(
        &mut self,
        level: GString,
        message: GString,
        source: GString,
        line: i64,
    ) {
        let level_s   = level.to_string().replace('"', "\\\"");
        let message_s = message.to_string().replace('"', "\\\"");
        let source_s  = source.to_string().replace('"', "\\\"");
        let payload = format!(
            "{{\"level\":\"{}\",\"message\":\"{}\",\"source\":\"{}\",\"line\":{}}}",
            level_s, message_s, source_s, line
        );
        self.base_mut()
            .emit_signal("console_egress", &[GString::from(payload.as_str()).to_variant()]);
    }

    /// Player input — Streaming mode emits `input_egress` for the
    /// GDScript glue to forward onto `window._twInputQueue`; Standalone
    /// mode short-circuits onto `movement_received` so editor F5 still
    /// drives the player without the BFF. `value_json` is a JSON-encoded
    /// payload (e.g. `"\"left\""`, `"{\"x\":1.0}"`) — the game script
    /// JSON-parses on receipt.
    #[func]
    fn send_input(&mut self, action: GString, value_json: GString) {
        // Hand-rolled JSON for `{"action":"<action>","value":<value_json>}`
        // so we don't have to drag in serde just for the bridge.
        let action_str = action.to_string().replace('"', "\\\"");
        let combined = format!("{{\"action\":\"{}\",\"value\":{}}}",
                               action_str, value_json);
        let payload = GString::from(combined.as_str());
        if self.streaming {
            self.base_mut()
                .emit_signal("input_egress", &[payload.to_variant()]);
        } else {
            self.base_mut()
                .emit_signal("movement_received", &[payload.to_variant()]);
        }
    }
}
