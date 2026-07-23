//! JavaScriptBridge ↔ HostBridge plumbing in Rust.
//!
//! Owns the postMessage contract between the iframe and its Angular
//! wrapper. Two halves:
//!
//!   * **Outbound** — game scripts emit on HostBridge signals
//!     (`input_egress`, `view_egress`, `api_request_egress`); we
//!     postMessage them to the parent so the wrapper can subscribe.
//!
//!   * **Inbound** — the wrapper postMessages SD_API_RESPONSE +
//!     SD_SSE_EVENT into the iframe; we install a JS listener on
//!     `window.message` that buckets payloads onto two queues, then
//!     drain both per frame and route through HostBridge:
//!       - SD_API_RESPONSE → `dispatch_api_response`
//!       - SD_SSE_EVENT    → `dispatch_event(type, payload)`
//!
//! The Angular wrapper is the one place that opens an `EventSource`
//! and the one place that performs `HttpClient` calls; the iframe
//! stays a passive Rust-side data consumer. This concentrates the
//! auth / cookie / interceptor concerns at the host tier.

use godot::classes::{Engine, INode, Node};
use godot::prelude::*;

use crate::host_bridge::HostBridge;

#[derive(GodotClass)]
#[class(base=Node)]
pub struct BridgeGlue {
    base: Base<Node>,
    host_bridge: Option<Gd<HostBridge>>,
    web: bool,
}

#[godot_api]
impl INode for BridgeGlue {
    fn init(base: Base<Node>) -> Self {
        Self {
            base,
            host_bridge: None,
            web: false,
        }
    }

    fn ready(&mut self) {
        self.web = os_has_feature("web");
        let Some(host_bridge) = find_host_bridge(&self.base().clone().upcast()) else {
            godot_warn!("[hugmesh_bridge] no HostBridge sibling — egress relay disabled");
            return;
        };

        if self.web {
            let mut hb = host_bridge.clone();
            hb.bind_mut().set_streaming_mode(true);
            install_message_listener();
        }

        let self_gd = self.to_gd();
        let mut hb_node = host_bridge.clone().upcast::<Node>();
        hb_node.connect(
            "input_egress",
            &Callable::from_object_method(&self_gd, "_on_input_egress"),
        );
        hb_node.connect(
            "view_egress",
            &Callable::from_object_method(&self_gd, "_on_view_egress"),
        );
        hb_node.connect(
            "api_request_egress",
            &Callable::from_object_method(&self_gd, "_on_api_request_egress"),
        );

        self.host_bridge = Some(host_bridge);
    }

    fn process(&mut self, _delta: f64) {
        if !self.web {
            return;
        }
        self.drain_api_responses();
        self.drain_sse_events();
    }
}

#[godot_api]
impl BridgeGlue {
    /// Outbound input — push onto the wrapper's input queue.
    #[func]
    fn _on_input_egress(&self, payload: GString) {
        if !self.web {
            return;
        }
        js_eval(&format!(
            "(window._twInputQueue ||= []).push({})",
            payload.to_string()
        ));
    }

    /// Outbound per-player camera/orientation sample — postMessage
    /// the wrapper so its `ApiBridgeService` can fire `/api/view`.
    #[func]
    fn _on_view_egress(&self, payload: GString) {
        if !self.web {
            return;
        }
        js_eval(&format!(
            "window.parent.postMessage({{type:'SD_VIEW_EGRESS', payload: {}}}, '*')",
            payload.to_string()
        ));
    }

    /// Outbound API request — postMessage the wrapper. The wrapper's
    /// `ApiBridgeService` performs the HttpClient call and posts the
    /// response back via `SD_API_RESPONSE`.
    #[func]
    fn _on_api_request_egress(&self, payload: GString) {
        if !self.web {
            return;
        }
        js_eval(&format!(
            "window.parent.postMessage({{type:'SD_API_REQUEST', payload: {}}}, '*')",
            payload.to_string()
        ));
    }
}

impl BridgeGlue {
    /// Pull every queued SD_API_RESPONSE off the JS-side queue and
    /// dispatch each through HostBridge so `BridgeApiClient` fires
    /// the matching pending callback.
    fn drain_api_responses(&mut self) {
        let Some(hb) = self.host_bridge.as_mut() else {
            return;
        };
        let raw = js_eval(
            "JSON.stringify((window._twApiResponseQueue && window._twApiResponseQueue.splice(0, \
             window._twApiResponseQueue.length)) || [])",
        );
        let raw_str = match raw {
            Some(s) if !s.is_empty() && s != "null" => s,
            _ => return,
        };
        let parsed = match parse_json_array(&raw_str) {
            Some(v) => v,
            None => return,
        };
        for entry_str in parsed {
            hb.bind_mut()
                .dispatch_api_response(GString::from(entry_str.as_str()));
        }
    }

    /// Pull every queued SD_SSE_EVENT off the JS-side queue and
    /// dispatch each through HostBridge.dispatch_event so the typed
    /// per-channel signals fire (movement_received, world_received,
    /// character_received, time_received, scene_received).
    ///
    /// Each entry shape: `{type:"movement", payload:{…}}`.
    fn drain_sse_events(&mut self) {
        let Some(hb) = self.host_bridge.as_mut() else {
            return;
        };
        let raw = js_eval(
            "JSON.stringify((window._twSseEventQueue && window._twSseEventQueue.splice(0, \
             window._twSseEventQueue.length)) || [])",
        );
        let raw_str = match raw {
            Some(s) if !s.is_empty() && s != "null" => s,
            _ => return,
        };
        let parsed = match parse_json_array(&raw_str) {
            Some(v) => v,
            None => return,
        };
        for entry_str in parsed {
            // Pull the event_type field and re-stringify the payload.
            let Some(event_type) = scan_string(&entry_str, "\"type\"") else {
                continue;
            };
            let payload_str = scan_value(&entry_str, "\"payload\"")
                .unwrap_or_else(|| "{}".to_string());
            hb.bind_mut().dispatch_event(
                GString::from(event_type.as_str()),
                GString::from(payload_str.as_str()),
            );
        }
    }
}

fn os_has_feature(name: &str) -> bool {
    godot::classes::Os::singleton().has_feature(name)
}

fn find_host_bridge(self_node: &Gd<Node>) -> Option<Gd<HostBridge>> {
    let parent = self_node.get_parent()?;
    let candidate = parent.get_node_or_null("HostBridge").or_else(|| {
        let current = self_node.get_tree().get_current_scene()?;
        current.get_node_or_null("HostBridge")
    })?;
    candidate.try_cast::<HostBridge>().ok()
}

fn js_eval(code: &str) -> Option<String> {
    let engine = Engine::singleton();
    let mut js = engine.get_singleton("JavaScriptBridge")?;
    let result: Variant = js.call("eval", &[Variant::from(code)]);
    if result.is_nil() {
        return None;
    }
    let s: GString = result.try_to().ok()?;
    Some(s.to_string())
}

/// Install a single `window.message` listener that buckets each
/// inbound postMessage onto the matching JS-side queue. Idempotent —
/// guarded by `window._twInboundInstalled`.
fn install_message_listener() {
    js_eval(
        r#"
(function() {
  window._twApiResponseQueue = window._twApiResponseQueue || [];
  window._twSseEventQueue    = window._twSseEventQueue    || [];
  if (window._twInboundInstalled) return;
  window._twInboundInstalled = true;
  window.addEventListener('message', function(ev) {
    var d = ev && ev.data;
    if (!d || !d.type) return;
    if (d.type === 'SD_API_RESPONSE' && d.payload) {
      window._twApiResponseQueue.push(d.payload);
    } else if (d.type === 'SD_SSE_EVENT' && d.payload && d.payload.type) {
      window._twSseEventQueue.push(d.payload);
    }
  });
})();
"#,
    );
}

/// Slice top-level JSON array entries — mirrors the JS-side
/// `JSON.stringify(arr)` shape so we don't need a JSON parser.
fn parse_json_array(blob: &str) -> Option<Vec<String>> {
    let trimmed = blob.trim();
    if !trimmed.starts_with('[') || !trimmed.ends_with(']') {
        return None;
    }
    let inner = &trimmed[1..trimmed.len() - 1];
    if inner.trim().is_empty() {
        return Some(Vec::new());
    }
    let mut out = Vec::new();
    let mut depth_obj = 0i32;
    let mut depth_arr = 0i32;
    let mut in_str = false;
    let mut escape = false;
    let mut start = 0usize;
    let bytes = inner.as_bytes();
    for (i, &b) in bytes.iter().enumerate() {
        if escape {
            escape = false;
            continue;
        }
        match b {
            b'\\' if in_str => escape = true,
            b'"' => in_str = !in_str,
            b'{' if !in_str => depth_obj += 1,
            b'}' if !in_str => depth_obj -= 1,
            b'[' if !in_str => depth_arr += 1,
            b']' if !in_str => depth_arr -= 1,
            b',' if !in_str && depth_obj == 0 && depth_arr == 0 => {
                out.push(inner[start..i].trim().to_string());
                start = i + 1;
            }
            _ => {}
        }
    }
    out.push(inner[start..].trim().to_string());
    Some(out)
}

/// Pull a JSON string field out of a flat object — the unescaped
/// inner contents, no surrounding quotes.
fn scan_string(blob: &str, key: &str) -> Option<String> {
    let raw = scan_value(blob, key)?;
    let trimmed = raw.trim();
    if !trimmed.starts_with('"') || !trimmed.ends_with('"') || trimmed.len() < 2 {
        return None;
    }
    Some(trimmed[1..trimmed.len() - 1].to_string())
}

/// Pull the verbatim substring of a value out of a flat-ish JSON
/// object. Used to extract `"payload":{...}` so we hand a valid JSON
/// blob to `dispatch_event` without needing a full parser.
fn scan_value(blob: &str, key: &str) -> Option<String> {
    let key_pos = blob.find(key)?;
    let after = &blob[key_pos + key.len()..];
    let colon = after.find(':')?;
    let rest = &after[colon + 1..];
    let trimmed = rest.trim_start();
    let bytes = trimmed.as_bytes();
    if bytes.is_empty() {
        return None;
    }
    let first = bytes[0] as char;
    if first == '"' {
        let mut idx = 1;
        let mut escape = false;
        while idx < trimmed.len() {
            let c = bytes[idx] as char;
            if escape {
                escape = false;
            } else if c == '\\' {
                escape = true;
            } else if c == '"' {
                return Some(trimmed[..idx + 1].to_string());
            }
            idx += 1;
        }
        None
    } else if first == '{' || first == '[' {
        let open = first;
        let close = if open == '{' { '}' } else { ']' };
        let mut depth = 0i32;
        let mut in_str = false;
        let mut escape = false;
        for (i, &b) in bytes.iter().enumerate() {
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
            } else if c == open {
                depth += 1;
            } else if c == close {
                depth -= 1;
                if depth == 0 {
                    return Some(trimmed[..i + 1].to_string());
                }
            }
        }
        None
    } else {
        let end = trimmed
            .find(|c: char| c == ',' || c == '}' || c == ']' || c.is_whitespace())
            .unwrap_or(trimmed.len());
        Some(trimmed[..end].to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::{parse_json_array, scan_string, scan_value};

    #[test]
    fn parse_json_array_top_level_objects() {
        let blob = r#"[{"a":1},{"b":2}]"#;
        let parsed = parse_json_array(blob).unwrap();
        assert_eq!(parsed, vec![r#"{"a":1}"#, r#"{"b":2}"#]);
    }

    #[test]
    fn parse_json_array_handles_nested_commas() {
        let blob = r#"[{"a":[1,2,3]},{"b":"x,y"}]"#;
        let parsed = parse_json_array(blob).unwrap();
        assert_eq!(parsed.len(), 2);
        assert!(parsed[0].contains("[1,2,3]"));
        assert!(parsed[1].contains("\"x,y\""));
    }

    #[test]
    fn scan_string_returns_unquoted() {
        let blob = r#"{"type":"movement","payload":{}}"#;
        assert_eq!(scan_string(blob, "\"type\""), Some("movement".to_string()));
    }

    #[test]
    fn scan_value_extracts_payload_object() {
        let blob = r#"{"type":"movement","payload":{"x":1,"y":2}}"#;
        assert_eq!(
            scan_value(blob, "\"payload\""),
            Some(r#"{"x":1,"y":2}"#.to_string())
        );
    }
}
