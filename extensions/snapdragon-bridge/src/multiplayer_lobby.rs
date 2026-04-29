//! Rust replacement for `multiplayer_lobby.gd`. Owns the per-slot
//! avatar lifecycle and the remote-position handler.
//!
//! Generic across N-player Snapdragon games. Drop this node as a
//! sibling of `HostBridge` + `BridgeGlue` plus an optional
//! `Wizards` / `Players` parent that the avatars get attached to.
//!
//! Behaviour:
//!   1. Subscribes to `HostBridge::slot_registered` /
//!      `slot_released` so any slot bookkeeping the parent does flows
//!      into the scene.
//!   2. Fetches `/api/sessions/{id}` via the bridge to learn about
//!      remote slots, then calls `register_player_slot` for each.
//!   3. Spawns a `CharacterBody3D` per registered slot — slot 0 is
//!      the local player; slots 1+ are remote and driven by movement
//!      events whose payload's `slot` doesn't match the local.
//!
//! Game-side customisation: a future `[exposed_class]` subclass hook
//! will let games override the avatar shape; today the default is a
//! palette-coloured wizard capsule + cone hat so a fresh project gets
//! a working visual without writing code.

use std::collections::HashMap;
use std::f32::consts::TAU;

use godot::classes::{
    CapsuleMesh, CapsuleShape3D, CharacterBody3D, CollisionShape3D, CylinderMesh, INode,
    MeshInstance3D, Node, Node3D, StandardMaterial3D,
};
use godot::prelude::*;

use crate::bridge_api_client::BridgeApiClient;
use crate::host_bridge::HostBridge;

const SLOT_PALETTES: &[Color] = &[
    Color { r: 0.42, g: 0.31, b: 0.78, a: 1.0 }, // 0 purple
    Color { r: 0.92, g: 0.45, b: 0.20, a: 1.0 }, // 1 orange
    Color { r: 0.20, g: 0.72, b: 0.55, a: 1.0 }, // 2 green
    Color { r: 0.85, g: 0.75, b: 0.30, a: 1.0 }, // 3 yellow
    Color { r: 0.33, g: 0.58, b: 0.92, a: 1.0 }, // 4 blue
    Color { r: 0.85, g: 0.30, b: 0.65, a: 1.0 }, // 5 pink
    Color { r: 0.55, g: 0.55, b: 0.55, a: 1.0 }, // 6 grey
    Color { r: 0.95, g: 0.95, b: 0.95, a: 1.0 }, // 7 white
];

#[derive(GodotClass)]
#[class(base=Node)]
pub struct SnapdragonMultiplayerLobby {
    base: Base<Node>,

    /// Path (relative to this node) for the parent that avatars get
    /// attached to. Defaults to the sibling `Wizards` so the existing
    /// scene contracts keep working; falls back to the parent node when
    /// missing.
    #[export]
    avatar_parent_path: NodePath,

    host_bridge: Option<Gd<HostBridge>>,
    api: Option<Gd<BridgeApiClient>>,
    avatars: HashMap<i64, Gd<Node3D>>,
}

#[godot_api]
impl INode for SnapdragonMultiplayerLobby {
    fn init(base: Base<Node>) -> Self {
        Self {
            base,
            avatar_parent_path: NodePath::from("../Wizards"),
            host_bridge: None,
            api: None,
            avatars: HashMap::new(),
        }
    }

    fn ready(&mut self) {
        let Some(host_bridge) = find_host_bridge(&self.base().clone().upcast()) else {
            godot_warn!("[snapdragon_bridge] no HostBridge — multiplayer lobby disabled");
            return;
        };
        self.host_bridge = Some(host_bridge.clone());

        let api = BridgeApiClient::new_alloc();
        self.base_mut().add_child(&api);
        self.api = Some(api);

        let self_gd = self.to_gd();
        let mut hb_node = host_bridge.upcast::<Node>();
        hb_node.connect(
            "slot_registered",
            &Callable::from_object_method(&self_gd, "_on_slot_registered"),
        );
        hb_node.connect(
            "slot_released",
            &Callable::from_object_method(&self_gd, "_on_slot_released"),
        );
        hb_node.connect(
            "movement_received",
            &Callable::from_object_method(&self_gd, "_on_movement_received"),
        );

        if godot::classes::Os::singleton().has_feature("web") {
            self.fetch_session_members();
        }
    }
}

#[godot_api]
impl SnapdragonMultiplayerLobby {
    /// Slot bookkeeping: spawn the avatar when a slot lands.
    #[func]
    fn _on_slot_registered(&mut self, slot: i64, character_id: i64, is_local: bool) {
        if self.avatars.contains_key(&slot) {
            return;
        }
        let av = self.spawn_avatar(slot, character_id, is_local);
        self.avatars.insert(slot, av);
    }

    /// Slot bookkeeping: tear down the avatar when a slot leaves.
    #[func]
    fn _on_slot_released(&mut self, slot: i64) {
        if let Some(mut av) = self.avatars.remove(&slot) {
            av.queue_free();
        }
    }

    /// Wired-up callback for the BridgeApiClient session GET. Walks
    /// the session's `members` array and registers every non-local
    /// slot so the avatars spawn for the rest of the lobby.
    #[func]
    fn _on_session_loaded(&mut self, status: i64, body: Variant) {
        if status != 200 {
            return;
        }
        let blob = godot::classes::Json::stringify(&body).to_string();
        let local = self
            .host_bridge
            .as_ref()
            .map(|hb| hb.bind().local_slot())
            .unwrap_or(-1);
        for member_blob in extract_members(&blob) {
            let slot = scan_int(&member_blob, "\"slotIndex\"").unwrap_or(-1);
            let cid = scan_int(&member_blob, "\"characterId\"").unwrap_or(-1);
            if slot < 0 || slot == local {
                continue;
            }
            if let Some(hb) = self.host_bridge.clone() {
                let mut hb_mut = hb;
                hb_mut.bind_mut().register_player_slot(slot, cid, false);
            }
        }
    }

    /// Movement handler — apply incoming position to the matching
    /// remote slot's avatar. The local slot's movement is ignored
    /// because the local controller already owns its position.
    #[func]
    fn _on_movement_received(&mut self, payload_json: GString) {
        let blob = payload_json.to_string();
        let slot = match scan_int(&blob, "\"slot\"") {
            Some(n) => n,
            None => return,
        };
        let local = self
            .host_bridge
            .as_ref()
            .map(|hb| hb.bind().local_slot())
            .unwrap_or(-1);
        if slot < 0 || slot == local {
            return;
        }
        let Some(av) = self.avatars.get_mut(&slot) else {
            return;
        };
        let pos = av.get_position();
        let x = scan_f32(&blob, "\"x\"").unwrap_or(pos.x);
        let y = scan_f32(&blob, "\"y\"").unwrap_or(pos.y);
        let z = scan_f32(&blob, "\"z\"").unwrap_or(pos.z);
        av.set_position(Vector3::new(x, y, z));
    }
}

impl SnapdragonMultiplayerLobby {
    fn spawn_avatar(&mut self, slot: i64, character_id: i64, is_local: bool) -> Gd<Node3D> {
        let mut av = CharacterBody3D::new_alloc();
        av.set_name(&format!("Player_{}", slot));

        let mat = make_material(slot);

        // Body
        let mut body = MeshInstance3D::new_alloc();
        let mut capsule = CapsuleMesh::new_gd();
        capsule.set_radius(0.25);
        capsule.set_height(1.4);
        body.set_mesh(&capsule);
        body.set_material_override(&mat);
        av.add_child(&body);

        // Wizard hat (cone)
        let mut hat = MeshInstance3D::new_alloc();
        let mut cone = CylinderMesh::new_gd();
        cone.set_top_radius(0.0);
        cone.set_bottom_radius(0.18);
        cone.set_height(0.45);
        hat.set_mesh(&cone);
        hat.set_position(Vector3::new(0.0, 0.95, 0.0));
        hat.set_material_override(&mat);
        av.add_child(&hat);

        // Collision
        let mut collision = CollisionShape3D::new_alloc();
        let mut shape = CapsuleShape3D::new_gd();
        shape.set_radius(0.25);
        shape.set_height(1.4);
        collision.set_shape(&shape);
        av.add_child(&collision);

        av.set_meta("slot", &slot.to_variant());
        av.set_meta("character_id", &character_id.to_variant());
        av.set_meta("is_local", &is_local.to_variant());
        av.set_position(starting_position_for_slot(slot));

        // Attach to the configured parent, falling back to our own
        // parent when the path doesn't resolve so a fresh project
        // doesn't crash.
        let parent_node = self
            .base()
            .get_node_or_null(&self.avatar_parent_path.clone())
            .or_else(|| self.base().get_parent());
        if let Some(mut parent_node) = parent_node {
            parent_node.add_child(&av);
        }

        av.upcast::<Node3D>()
    }

    fn fetch_session_members(&mut self) {
        let Some(hb) = self.host_bridge.clone() else { return };
        let sid = hb.bind().session_id().to_string();
        if sid.is_empty() {
            return;
        }
        let path = format!("/api/sessions/{}", sid);
        let Some(mut api) = self.api.clone() else { return };

        let cb = Callable::from_object_method(&self.to_gd(), "_on_session_loaded");
        api.bind_mut().http_get(GString::from(path.as_str()), cb);
    }
}

fn make_material(slot: i64) -> Gd<StandardMaterial3D> {
    let mut mat = StandardMaterial3D::new_gd();
    let idx = (slot.rem_euclid(SLOT_PALETTES.len() as i64)) as usize;
    mat.set_albedo(SLOT_PALETTES[idx]);
    mat
}

fn starting_position_for_slot(slot: i64) -> Vector3 {
    let radius = 1.5_f32;
    let angle = (slot as f32) * (TAU / 3.0);
    Vector3::new(angle.cos() * radius, 0.0, angle.sin() * radius)
}

fn find_host_bridge(self_node: &Gd<Node>) -> Option<Gd<HostBridge>> {
    let parent = self_node.get_parent()?;
    let candidate = parent.get_node_or_null("HostBridge").or_else(|| {
        let current = self_node.get_tree().get_current_scene()?;
        current.get_node_or_null("HostBridge")
    })?;
    candidate.try_cast::<HostBridge>().ok()
}

/// Reuses the same scan_int from bridge_api_client without making a
/// third copy — duplicated here verbatim to avoid a public re-export
/// dance for two helpers that fit on one screen.
fn scan_int(blob: &str, key: &str) -> Option<i64> {
    let key_pos = blob.find(key)?;
    let after = &blob[key_pos + key.len()..];
    let colon = after.find(':')?;
    let mut chars = after[colon + 1..].chars().skip_while(|c| c.is_whitespace());
    let mut buf = String::new();
    if let Some(c) = chars.next() {
        if c == '-' || c.is_ascii_digit() {
            buf.push(c);
        } else {
            return None;
        }
    }
    for c in chars {
        if c.is_ascii_digit() {
            buf.push(c);
        } else {
            break;
        }
    }
    buf.parse().ok()
}

fn scan_f32(blob: &str, key: &str) -> Option<f32> {
    let key_pos = blob.find(key)?;
    let after = &blob[key_pos + key.len()..];
    let colon = after.find(':')?;
    let mut chars = after[colon + 1..].chars().skip_while(|c| c.is_whitespace());
    let mut buf = String::new();
    if let Some(c) = chars.next() {
        if c == '-' || c.is_ascii_digit() || c == '.' {
            buf.push(c);
        } else {
            return None;
        }
    }
    for c in chars {
        if c.is_ascii_digit() || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-' {
            buf.push(c);
        } else {
            break;
        }
    }
    buf.parse().ok()
}

/// Pull each `{...}` member off the top-level `"members":[...]` array.
/// Robust enough for the `GameSessionDto` payload shape; not a JSON
/// parser. Returns the raw member-substrings so callers re-scan_int
/// individual fields.
fn extract_members(blob: &str) -> Vec<String> {
    let Some(start) = blob.find("\"members\"") else {
        return Vec::new();
    };
    let after = &blob[start..];
    let Some(open) = after.find('[') else {
        return Vec::new();
    };
    let inner_start = start + open + 1;
    let bytes = blob.as_bytes();
    let mut depth = 1i32;
    let mut idx = inner_start;
    let mut in_str = false;
    let mut escape = false;
    while idx < bytes.len() && depth > 0 {
        let c = bytes[idx] as char;
        if escape {
            escape = false;
        } else if in_str {
            if c == '\\' {
                escape = true;
            } else if c == '"' {
                in_str = false;
            }
        } else if c == '"' {
            in_str = true;
        } else if c == '[' {
            depth += 1;
        } else if c == ']' {
            depth -= 1;
        }
        idx += 1;
    }
    if depth != 0 {
        return Vec::new();
    }
    let inner = &blob[inner_start..idx - 1];

    let mut members = Vec::new();
    let mut depth_obj = 0i32;
    let mut in_str = false;
    let mut escape = false;
    let mut start_obj = None;
    for (i, &b) in inner.as_bytes().iter().enumerate() {
        let c = b as char;
        if escape {
            escape = false;
            continue;
        }
        if in_str {
            if c == '\\' {
                escape = true;
            } else if c == '"' {
                in_str = false;
            }
            continue;
        }
        if c == '"' {
            in_str = true;
        } else if c == '{' {
            if depth_obj == 0 {
                start_obj = Some(i);
            }
            depth_obj += 1;
        } else if c == '}' {
            depth_obj -= 1;
            if depth_obj == 0 {
                if let Some(s) = start_obj.take() {
                    members.push(inner[s..=i].to_string());
                }
            }
        }
    }
    members
}

#[cfg(test)]
mod tests {
    use super::extract_members;

    #[test]
    fn extract_members_picks_each_object() {
        let blob = r#"{"id":"x","members":[{"slotIndex":0,"characterId":1},{"slotIndex":1,"characterId":2}]}"#;
        let parsed = extract_members(blob);
        assert_eq!(parsed.len(), 2);
        assert!(parsed[0].contains("\"slotIndex\":0"));
        assert!(parsed[1].contains("\"characterId\":2"));
    }

    #[test]
    fn extract_members_handles_empty_array() {
        let blob = r#"{"members":[]}"#;
        assert!(extract_members(blob).is_empty());
    }
}
