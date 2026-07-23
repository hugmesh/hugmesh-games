//! API client — egress through the Angular wrapper.
//!
//! Architecture (corrected): the Angular wrapper component
//! (`<app-{slug}-remote>`) is the SSE consumer + HTTP transport. The
//! wasm / Godot / Rust iframe is a data consumer only.
//!
//! Outbound flow (game script → BFF):
//!
//! ```text
//!   BridgeApiClient.http_get(path, cb)
//!     └─► HostBridge.request_api  → emits api_request_egress
//!         └─► BridgeGlue postMessages SD_API_REQUEST to parent
//!             └─► Angular wrapper performs HttpClient call (auth +
//!                 X-Session-Id header attached uniformly there)
//! ```
//!
//! Inbound flow (BFF → game):
//!
//! ```text
//!   Angular wrapper postMessages SD_API_RESPONSE
//!     └─► BridgeGlue queues + drains per frame
//!         └─► HostBridge.dispatch_api_response → emits api_response_received
//!             └─► BridgeApiClient fires the matching callback
//! ```
//!
//! Standalone (editor F5) is unchanged: a Godot `HttpRequest` against
//! `API_BASE_DEV` so a level designer can iterate without the host
//! shell running.

use std::collections::HashMap;

use godot::classes::{HttpRequest, INode, Node};
use godot::prelude::*;

use crate::host_bridge::HostBridge;

const API_BASE_DEV: &str = "http://localhost:8080";

#[derive(GodotClass)]
#[class(base=Node)]
pub struct BridgeApiClient {
    base: Base<Node>,
    host_bridge: Option<Gd<HostBridge>>,
    /// `request_id` → caller callback.
    pending: HashMap<i64, Callable>,
}

#[godot_api]
impl INode for BridgeApiClient {
    fn init(base: Base<Node>) -> Self {
        Self {
            base,
            host_bridge: None,
            pending: HashMap::new(),
        }
    }

    fn ready(&mut self) {
        self.host_bridge = find_host_bridge(&self.base().clone().upcast());
        if let Some(hb) = self.host_bridge.clone() {
            let mut hb_node = hb.upcast::<Node>();
            hb_node.connect(
                "api_response_received",
                &Callable::from_object_method(&self.to_gd(), "_on_api_response"),
            );
        }
    }
}

#[godot_api]
impl BridgeApiClient {
    /// `GET path` — `on_done(status, body)`. Named `http_get` (not
    /// `get`) so it doesn't collide with `Node.get(property)`.
    #[func]
    pub fn http_get(&mut self, path: GString, on_done: Callable) {
        self.request("GET", path.to_string(), Variant::nil(), on_done);
    }

    /// `POST path` with a JSON-encoded body.
    #[func]
    pub fn http_post(&mut self, path: GString, body: Variant, on_done: Callable) {
        self.request("POST", path.to_string(), body, on_done);
    }

    /// `PUT path` with a JSON-encoded body.
    #[func]
    pub fn http_put(&mut self, path: GString, body: Variant, on_done: Callable) {
        self.request("PUT", path.to_string(), body, on_done);
    }

    /// `DELETE path`.
    #[func]
    pub fn http_delete(&mut self, path: GString, on_done: Callable) {
        self.request("DELETE", path.to_string(), Variant::nil(), on_done);
    }

    /// Inbound bridge response from the Angular wrapper. Payload
    /// shape: `{"requestId":int, "status":int, "body":<any JSON>}`.
    /// Looks up the matching pending callback and fires it.
    #[func]
    fn _on_api_response(&mut self, payload_json: GString) {
        let raw = payload_json.to_string();
        let request_id = match scan_int(&raw, "\"requestId\"") {
            Some(n) => n,
            None => return,
        };
        let status = scan_int(&raw, "\"status\"").unwrap_or(0);
        let body_str = scan_value(&raw, "\"body\"").unwrap_or_else(|| "null".to_string());
        let Some(cb) = self.pending.remove(&request_id) else {
            return;
        };
        let body_variant = parse_json_to_variant(&body_str);
        cb.call(&[Variant::from(status), body_variant]);
    }

    /// Wired-up handler for the standalone HTTPRequest fallback path.
    /// `bindv` in `request()` prepends `(http, on_done)`; the engine
    /// adds `(result, code, headers, body)`.
    #[func]
    fn _on_standalone_response(
        &mut self,
        http: Gd<HttpRequest>,
        on_done: Callable,
        _result: i64,
        code: i64,
        _headers: godot::builtin::PackedStringArray,
        body: godot::builtin::PackedByteArray,
    ) {
        let mut http_mut = http;
        http_mut.queue_free();
        let parsed = if body.is_empty() {
            Variant::nil()
        } else {
            let s = String::from_utf8(body.to_vec()).unwrap_or_default();
            parse_json_to_variant(&s)
        };
        if on_done.is_valid() {
            on_done.call(&[Variant::from(code), parsed]);
        }
    }
}

impl BridgeApiClient {
    fn request(&mut self, method: &str, path: String, body: Variant, on_done: Callable) {
        // Streaming mode: route through the bridge so the Angular
        // wrapper does the actual HTTP with the auth interceptor
        // attached uniformly at the host tier.
        if let Some(hb) = self.host_bridge.clone() {
            if hb.bind().is_streaming() {
                let body_json = if body.is_nil() {
                    GString::from("null")
                } else {
                    godot::classes::Json::stringify(&body)
                };
                let id = {
                    let mut hb_mut = hb;
                    let mut bound = hb_mut.bind_mut();
                    bound.request_api(
                        GString::from(method),
                        GString::from(path.as_str()),
                        body_json,
                    )
                };
                if on_done.is_valid() {
                    self.pending.insert(id, on_done);
                }
                return;
            }
        }

        // Standalone fallback: direct HTTPRequest against the dev BFF.
        let mut http = HttpRequest::new_alloc();
        let http_owner = http.clone();
        self.base_mut().add_child(&http);

        let self_gd = self.to_gd();
        let bound_callable = Callable::from_object_method(&self_gd, "_on_standalone_response")
            .bindv(&godot::builtin::VarArray::from(&[
                http_owner.to_variant(),
                on_done.to_variant(),
            ]));
        http.connect("request_completed", &bound_callable);

        let url = format!("{}{}", API_BASE_DEV, path);
        let headers = godot::builtin::PackedStringArray::from(&[
            GString::from("Accept: application/json"),
            GString::from("Content-Type: application/json"),
        ]);
        let body_str = if body.is_nil() {
            GString::new()
        } else {
            godot::classes::Json::stringify(&body)
        };
        let http_method = match method {
            "GET" => godot::classes::http_client::Method::GET,
            "POST" => godot::classes::http_client::Method::POST,
            "PUT" => godot::classes::http_client::Method::PUT,
            "DELETE" => godot::classes::http_client::Method::DELETE,
            "PATCH" => godot::classes::http_client::Method::PATCH,
            _ => godot::classes::http_client::Method::GET,
        };
        let err = http
            .request_ex(&GString::from(url.as_str()))
            .custom_headers(&headers)
            .method(http_method)
            .request_data(&body_str)
            .done();
        if err != godot::global::Error::OK {
            http.queue_free();
            if on_done.is_valid() {
                on_done.call(&[Variant::from(0i64), Variant::nil()]);
            }
        }
    }
}

fn find_host_bridge(self_node: &Gd<Node>) -> Option<Gd<HostBridge>> {
    let parent = self_node.get_parent()?;
    let candidate = parent.get_node_or_null("HostBridge").or_else(|| {
        let current = self_node.get_tree().get_current_scene()?;
        current.get_node_or_null("HostBridge")
    })?;
    candidate.try_cast::<HostBridge>().ok()
}

fn parse_json_to_variant(s: &str) -> Variant {
    if s.is_empty() {
        return Variant::nil();
    }
    godot::classes::Json::parse_string(&GString::from(s))
}

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
    use super::{scan_int, scan_value};

    #[test]
    fn scan_int_picks_request_id() {
        let blob = r#"{"requestId":42,"status":200,"body":null}"#;
        assert_eq!(scan_int(blob, "\"requestId\""), Some(42));
        assert_eq!(scan_int(blob, "\"status\""), Some(200));
    }

    #[test]
    fn scan_value_extracts_object_body() {
        let blob = r#"{"requestId":1,"status":200,"body":{"a":1,"b":[1,2]}}"#;
        assert_eq!(
            scan_value(blob, "\"body\""),
            Some(r#"{"a":1,"b":[1,2]}"#.to_string())
        );
    }
}
