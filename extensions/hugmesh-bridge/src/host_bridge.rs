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

    /// Multiplayer state. Single-player builds leave `local_slot=-1` and
    /// the rest of the surface degrades to legacy single-player. When a
    /// game scene boots inside an iframe with `?slot_index=N`, the
    /// GDScript glue calls [`register_player_slot`] with `is_local=true`
    /// for its own slot and `is_local=false` for each remote-player slot
    /// it knows about. The bridge tracks the local slot's character_id
    /// so [`send_input`] knows which character the local controller is
    /// driving.
    local_slot: i64,
    local_character_id: i64,
    /// Multiplayer session id, threaded from the iframe URL via the
    /// GDScript glue's [`set_session_id`] call. Forwarded back on
    /// `input_egress` payloads so the BFF can scope the control write
    /// to one session's per-session sinks.
    session_id: GString,
    /// Monotonic counter for `request_api` correlation.
    next_request_id: i64,
}

#[godot_api]
impl INode for HostBridge {
    fn init(base: Base<Node>) -> Self {
        Self {
            base,
            streaming: false,
            local_slot: -1,
            local_character_id: -1,
            session_id: GString::new(),
            next_request_id: 1,
        }
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

    /// Per-player camera / orientation egress for admin observation.
    /// The local-player camera script calls
    /// `bridge.report_view(slot, x, y, z, yaw, pitch, fov)` at ~10 Hz;
    /// the GDScript glue forwards each payload to the parent which
    /// POSTs `/api/view`. The BFF fans out on the per-session
    /// `view` SSE channel admins watch via `/api/admin/sessions/{id}/stream`.
    /// JSON payload: `{slot,characterId,sessionId,x,y,z,yaw,pitch,fov}`.
    #[signal] fn view_egress(payload: GString);

    /// Slot bookkeeping notifications. The GDScript scene script can
    /// connect to repaint the lobby UI when remote players join or
    /// leave during the session.
    #[signal] fn slot_registered(slot: i64, character_id: i64, is_local: bool);
    #[signal] fn slot_released(slot: i64);

    /// Session lifecycle pause / resume / close. Routed in from the
    /// Angular wrapper when the BFF's `session-control` SSE channel
    /// fires (decision: session-pause feature).
    ///
    /// `action` is one of
    /// `SESSION_PAUSED` / `SESSION_RESUMED` / `PLAYER_PAUSED` /
    /// `PLAYER_RESUMED` / `SESSION_CLOSED`. `payload` is the full
    /// JSON event from the BFF
    /// (`{sessionId, action, casdoorId, launchUid, reason, occurredAt}`),
    /// so game scripts can implement per-game pause semantics — e.g.
    /// `get_tree().paused = true` on SESSION_PAUSED, stop physics on
    /// PLAYER_PAUSED that targets the local slot, or tear down the
    /// scene on SESSION_CLOSED.
    ///
    /// Connect from GDScript:
    /// ```gdscript
    /// bridge.session_control_received.connect(_on_session_control)
    /// func _on_session_control(action: String, payload: String) -> void:
    ///     match action:
    ///         "SESSION_PAUSED":  get_tree().paused = true
    ///         "SESSION_RESUMED": get_tree().paused = false
    ///         "SESSION_CLOSED":  queue_free()
    /// ```
    #[signal] fn session_control_received(action: GString, payload: GString);

    /// Outbound API request — `BridgeGlue` postMessages each payload
    /// to the Angular wrapper, which performs the actual HTTP call (so
    /// the host's HttpClient interceptor + auth header flow apply
    /// uniformly) and posts the response back via
    /// `api_response_received`. Game scripts use the addon's
    /// `BridgeApiClient` rather than touching this signal directly.
    ///
    /// Payload JSON: `{requestId, method, path, sessionId, body}`.
    #[signal] fn api_request_egress(payload: GString);

    /// Inbound API response from the Angular wrapper. Payload JSON:
    /// `{requestId, status, body}`. `BridgeApiClient` indexes pending
    /// requests by id and dispatches to the matching caller callback.
    #[signal] fn api_response_received(payload: GString);

    /// Inbound SSE event from the Angular wrapper. The wrapper owns
    /// the `EventSource` connection (one per mounted wrapper) and
    /// postMessages each event into the iframe; `BridgeGlue` routes
    /// the postMessage in via this signal. Payload JSON shape matches
    /// the BFF's per-channel DTO. The wrapper owns the SSE plumbing
    /// so the iframe stays a passive data consumer — Rust handles
    /// the processing.
    #[signal] fn sse_event_received(event_type: GString, payload: GString);

    /// Called by the GDScript glue once it confirms a parent module is
    /// present. Flips the bridge into streaming mode so [`send_input`]
    /// stops echoing locally and instead emits `input_egress`.
    #[func]
    pub fn set_streaming_mode(&mut self, on: bool) {
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
    pub fn is_streaming(&self) -> bool {
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
    pub fn dispatch_event(&mut self, event_type: GString, payload: GString) {
        // session-control is a two-argument signal (action + full
        // payload); every other signal is one-argument (payload only).
        // Parse the action from the JSON and emit accordingly.
        let event_type_str = event_type.to_string();
        if event_type_str == "session-control" {
            let action = parse_json_string(&payload.to_string(), "action")
                .unwrap_or_else(|| "UNKNOWN".to_string());
            self.base_mut().emit_signal(
                "session_control_received",
                &[GString::from(action.as_str()).to_variant(), payload.to_variant()]);
            return;
        }
        let signal = match event_type_str.as_str() {
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
    ///
    /// Multiplayer enrichment: the emitted JSON now also carries the
    /// local slot's `slot` and `characterId` plus the active `sessionId`
    /// (when set by [`set_session_id`]) so the GDScript glue can post
    /// to `/api/controls/{characterId}` with the `X-Session-Id` header
    /// — that's what tells the BFF to route the control through the
    /// per-session sinks instead of the legacy global path.
    #[func]
    fn send_input(&mut self, action: GString, value_json: GString) {
        let action_str = action.to_string().replace('"', "\\\"");
        let session_str = self.session_id.to_string().replace('"', "\\\"");
        let combined = format!(
            "{{\"action\":\"{}\",\"value\":{},\"slot\":{},\"characterId\":{},\"sessionId\":\"{}\"}}",
            action_str, value_json, self.local_slot, self.local_character_id, session_str
        );
        let payload = GString::from(combined.as_str());
        if self.streaming {
            self.base_mut()
                .emit_signal("input_egress", &[payload.to_variant()]);
        } else {
            self.base_mut()
                .emit_signal("movement_received", &[payload.to_variant()]);
        }
    }

    // ── Multiplayer slot registry ──────────────────────────────────────

    /// Registers a player slot. Called by the GDScript scene script on
    /// scene boot for the local slot (`is_local=true`) and once per
    /// remote slot it learns about (e.g. via the parent's session-detail
    /// payload). The local registration also stamps `local_slot` and
    /// `local_character_id` so [`send_input`] can build a session-aware
    /// payload.
    ///
    /// Re-registering the same slot updates the character_id (covers
    /// the lobby flow where a player picks their wizard before launch).
    #[func]
    pub fn register_player_slot(
        &mut self,
        slot: i64,
        character_id: i64,
        is_local: bool,
    ) {
        if is_local {
            self.local_slot = slot;
            self.local_character_id = character_id;
        }
        godot_print!(
            "[HostBridge] slot {} registered: characterId={}, local={}",
            slot, character_id, is_local
        );
        self.base_mut().emit_signal(
            "slot_registered",
            &[slot.to_variant(), character_id.to_variant(), is_local.to_variant()],
        );
    }

    /// Marks a slot as no longer present — emitted by the parent when a
    /// remote player leaves the session. Game scripts subscribe to
    /// `slot_released` to despawn the visible avatar.
    #[func]
    fn release_player_slot(&mut self, slot: i64) {
        if slot == self.local_slot {
            self.local_slot = -1;
            self.local_character_id = -1;
        }
        godot_print!("[HostBridge] slot {} released", slot);
        self.base_mut()
            .emit_signal("slot_released", &[slot.to_variant()]);
    }

    /// Returns the local-player's slot (or -1 if not registered).
    /// GDScript reads this on scene start to figure out which wizard
    /// to attach the camera + input handling to.
    #[func]
    pub fn local_slot(&self) -> i64 { self.local_slot }

    /// Returns the local-player's bound character_id (or -1).
    #[func]
    fn local_character_id(&self) -> i64 { self.local_character_id }

    /// Sets the active multiplayer session id. The GDScript glue calls
    /// this once on scene boot from the `?session_id=` query param;
    /// every subsequent [`send_input`] payload carries the value so
    /// the BFF routes the control on the per-session sinks.
    #[func]
    fn set_session_id(&mut self, sid: GString) {
        self.session_id = sid;
    }

    /// Returns the active session id (empty string when single-player).
    #[func]
    pub fn session_id(&self) -> GString { self.session_id.clone() }

    /// Per-player camera / orientation sample. The local-player camera
    /// script calls this at ~10 Hz; the GDScript glue forwards the
    /// payload to the parent which POSTs `/api/view`.
    ///
    /// The payload includes the local slot + bound character + session
    /// so the BFF can authorize and route without an extra round-trip.
    #[func]
    fn report_view(
        &mut self,
        slot: i64,
        x: f64, y: f64, z: f64,
        yaw: f64, pitch: f64, fov: f64,
    ) {
        let session_str = self.session_id.to_string().replace('"', "\\\"");
        let payload = format!(
            "{{\"slot\":{},\"characterId\":{},\"sessionId\":\"{}\",\"x\":{},\"y\":{},\"z\":{},\"yaw\":{},\"pitch\":{},\"fov\":{}}}",
            slot, self.local_character_id, session_str, x, y, z, yaw, pitch, fov
        );
        self.base_mut()
            .emit_signal("view_egress", &[GString::from(payload.as_str()).to_variant()]);
    }

    // ── API bridge ─────────────────────────────────────────────────────
    //
    // The Angular wrapper component (`<app-{slug}-remote>`) owns both
    // the SSE subscription and the API call transport. The wasm /
    // Godot / Rust iframe is a data consumer only: it receives SSE
    // events + API responses via postMessage, routed through
    // `BridgeGlue` into typed signals here. Rust handles the actual
    // data processing.

    /// Emit an outbound API request on the egress channel. Returns
    /// the request id so the caller correlates the eventual
    /// `api_response_received` signal back to its callback.
    ///
    /// `body_json` is the literal JSON value (`"null"`, `"{\"x\":1}"`,
    /// `"[1,2,3]"`); `BridgeApiClient` does the variant-to-JSON
    /// stringify before calling.
    #[func]
    pub fn request_api(&mut self, method: GString, path: GString, body_json: GString) -> i64 {
        let id = self.next_request_id;
        self.next_request_id += 1;

        let method_s = method.to_string().replace('"', "\\\"");
        let path_s   = path.to_string().replace('"', "\\\"");
        let session_s = self.session_id.to_string().replace('"', "\\\"");
        let body_s   = body_json.to_string();
        let body_eff = if body_s.is_empty() { "null".to_string() } else { body_s };

        let payload = format!(
            "{{\"requestId\":{},\"method\":\"{}\",\"path\":\"{}\",\"sessionId\":\"{}\",\"body\":{}}}",
            id, method_s, path_s, session_s, body_eff
        );
        self.base_mut().emit_signal(
            "api_request_egress",
            &[GString::from(payload.as_str()).to_variant()],
        );
        id
    }

    /// Called by `BridgeGlue` when an `SD_API_RESPONSE` payload
    /// arrives from the Angular wrapper. Re-emits as
    /// `api_response_received` so `BridgeApiClient`'s pending map
    /// can dispatch to the matching callback.
    #[func]
    pub fn dispatch_api_response(&mut self, payload: GString) {
        self.base_mut().emit_signal(
            "api_response_received",
            &[payload.to_variant()],
        );
    }
}

/// Tiny ad-hoc JSON field extractor — pulls the string value of one
/// top-level key out of a JSON object. We avoid pulling in a full JSON
/// parser dependency here because the only consumer is the
/// `session-control` action dispatch (one specific key, well-formed
/// payload from our own BFF). Returns None on any structural mismatch.
fn parse_json_string(json: &str, key: &str) -> Option<String> {
    let needle = format!("\"{}\":", key);
    let i = json.find(&needle)?;
    let rest = &json[i + needle.len()..];
    let rest = rest.trim_start();
    if !rest.starts_with('"') { return None; }
    let body = &rest[1..];
    let end = body.find('"')?;
    Some(body[..end].to_string())
}
