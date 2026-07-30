# Session / control bridge for a produced RPG bundle (hugmesh-rpg-builder).
#
# Ported from three-wizards-in-a-trenchcoat/game_bridge.gd, re-indented to 4
# spaces, with the maker fixes:
#   * POST /api/controls/<character_id> (from the URL param, fallback 1) instead
#     of the hard-coded /api/controls/1;
#   * ENTER_ROOM:<uuid> control emitted on every room transition (world.gd calls
#     send_control) — this is also the e2e's server-side movement-through-levels
#     proof and how remote_avatars scopes avatars to rooms;
#   * SHOP_BUY/SHOP_SELL controls + an SD_SHOP_EVENT postMessage for the shop UI;
#   * BFF calls (inn rest / church heal) go through the HostBridge.request_api
#     seam (SD_API_REQUEST + host auth interceptor), never raw fetch;
#   * while the shop panel is open, movement keys are suppressed and any held
#     movement action gets a synthetic pressed=false release so the player does
#     not keep walking behind the panel.
extends Node

var _host_bridge: Node = null
var _character_id := 1
# Session id from the launch URL (?session_id=), threaded onto every control
# POST as X-Session-Id. ControlController scopes the echo to the per-session
# SSE sinks off THAT header; without it every maker control lands on the
# legacy global sink and the OTHER player in the session never sees it.
var _session_id := ""
var _shop_open := false
var _dialogue_open := false
var _held := {}             # action -> true while pressed
var _max_hp := 100
var _max_mp := 50

const KEY_ACTIONS := {
    KEY_W: "MOVE_FORWARD",  KEY_UP:    "MOVE_FORWARD",
    KEY_S: "MOVE_BACKWARD", KEY_DOWN:  "MOVE_BACKWARD",
    KEY_A: "MOVE_LEFT",     KEY_LEFT:  "MOVE_LEFT",
    KEY_D: "MOVE_RIGHT",    KEY_RIGHT: "MOVE_RIGHT",
    KEY_SPACE: "JUMP",
    KEY_E: "INTERACT",
}

const MOVE_ACTIONS := ["MOVE_FORWARD", "MOVE_BACKWARD", "MOVE_LEFT", "MOVE_RIGHT"]

func _ready() -> void:
    add_to_group("game_bridge")
    _host_bridge = get_parent().get_node_or_null("HostBridge")
    if _host_bridge != null:
        _host_bridge.session_control_received.connect(_on_session_control)
        _host_bridge.character_received.connect(_on_character)
    if OS.has_feature("web"):
        _thread_url_params()
        _request_sse_subscription()

func _thread_url_params() -> void:
    var raw: Variant = JavaScriptBridge.eval("""
(function() {
    var params = new URLSearchParams(window.location.search);
    return JSON.stringify({
        sessionId:   params.get('session_id')   || '',
        characterId: parseInt(params.get('character_id') || '1', 10),
        slotIndex:   parseInt(params.get('slot_index')   || '-1', 10)
    });
})();
    """)
    if not raw or raw == "null":
        return
    var parsed = JSON.parse_string(str(raw))
    if not (parsed is Dictionary):
        return
    var sid: String = parsed.get("sessionId", "")
    var cid: int = int(parsed.get("characterId", 1))
    var slot: int = int(parsed.get("slotIndex", -1))
    _character_id = cid if cid >= 0 else 1
    _session_id = _safe_sid(sid)
    if _host_bridge == null:
        return
    if sid != "":
        _host_bridge.set_session_id(sid)
    if slot >= 0 and cid >= 0:
        _host_bridge.register_player_slot(slot, cid, true)

# Session ids are Crypto.mintUid() output — URL-safe base64. Anything else is
# DROPPED rather than spliced into the eval'd JS in send_control(): the same
# rule SD_DIALOGUE_EVENT follows — never interpolate an unvalidated string
# into JS source, where a quote would break parsing and a crafted value could
# forge structured messages to the host page.
static func _safe_sid(sid: String) -> String:
    var re := RegEx.new()
    re.compile("^[A-Za-z0-9_-]{1,64}$")
    return sid if re.search(sid) != null else ""

func _request_sse_subscription() -> void:
    var sid_raw: Variant = JavaScriptBridge.eval(
        "new URLSearchParams(window.location.search).get('session_id') || ''")
    var sid := str(sid_raw) if sid_raw != null else ""
    JavaScriptBridge.eval(
        "window.parent.postMessage({type:'SD_SSE_SUBSCRIBE', payload: {sessionId: '%s'}}, '*')" % sid)

func _on_session_control(action: String, _payload: String) -> void:
    print("[bridge] session-control: ", action)
    var tree := get_tree()
    if tree == null:
        return
    match action:
        "SESSION_PAUSED", "PLAYER_PAUSED":
            tree.paused = true
        "SESSION_RESUMED", "PLAYER_RESUMED":
            tree.paused = false
        "SESSION_CLOSED":
            tree.quit()

func _on_character(payload_json: String) -> void:
    var parsed: Variant = JSON.parse_string(payload_json)
    if parsed is Dictionary:
        _max_hp = int(parsed.get("maxHp", _max_hp))
        _max_mp = int(parsed.get("maxMp", _max_mp))

func _unhandled_input(event: InputEvent) -> void:
    if not (event is InputEventKey) or event.is_echo():
        return
    var key_ev := event as InputEventKey
    var action: String = KEY_ACTIONS.get(key_ev.keycode, "")
    if action == "":
        return
    # While the shop OR dialogue panel is open, swallow movement keys (INTERACT
    # still routes so E can toggle the panel from game code).
    if (_shop_open or _dialogue_open) and action in MOVE_ACTIONS:
        get_viewport().set_input_as_handled()
        return
    if action == "INTERACT" and key_ev.pressed:
        _on_interact()
        get_viewport().set_input_as_handled()
        return
    if key_ev.pressed:
        _held[action] = true
    else:
        _held.erase(action)
    send_control(action, key_ev.pressed)
    get_viewport().set_input_as_handled()

func _on_interact() -> void:
    # Shop wins when both triggers overlap; otherwise a nearby NPC's
    # dialogue panel toggles.
    var ui := _shop_ui()
    if ui != null and ui.has_method("has_active_shop") and ui.has_active_shop():
        _release_movement()
        ui.toggle()
        return
    var dlg := _dialogue_ui()
    if dlg != null and dlg.has_method("has_active_npc") and dlg.has_active_npc():
        _release_movement()
        dlg.toggle()

# Public — world.gd (ENTER_ROOM), shop_ui.gd all drive controls through here.
func send_control(action: String, pressed: bool) -> void:
    if not OS.has_feature("web"):
        # Local-play: synthesize the movement event the SSE echo would produce so
        # the Player still moves under the Godot editor / headless run.
        var ts0: int = int(Time.get_unix_time_from_system() * 1000)
        _dispatch_local(action, pressed, ts0)
        return
    var ts: int = int(Time.get_unix_time_from_system() * 1000)
    var chapter_id_js: String = JavaScriptBridge.eval(
        "new URLSearchParams(window.location.search).get('chapter_id') || ''")
    var chapter_id_str := str(chapter_id_js)
    var body: String
    if chapter_id_str.is_empty() or not chapter_id_str.is_valid_int():
        body = '{"action":"%s","pressed":%s,"timestamp":%d}' % [
            action, "true" if pressed else "false", ts]
    else:
        body = '{"action":"%s","pressed":%s,"timestamp":%d,"chapterId":%d}' % [
            action, "true" if pressed else "false", ts, int(chapter_id_str)]
    JavaScriptBridge.eval("""
(function(){
    var token = '';
    // The host AuthService persists the session in LOCALSTORAGE key 'tw_auth'
    // (deliberately not sessionStorage — open-in-new-tab keeps the login).
    // Reading sessionStorage here silently yielded token='' for every control,
    // so the POSTs went out anonymous and 401'd once the bridge went live.
    // Keep a sessionStorage fallback for embedders that scope auth per-tab.
    try {
        var raw = window.parent.localStorage.getItem('tw_auth')
               || window.parent.sessionStorage.getItem('tw_auth');
        if (raw) { var stored = JSON.parse(raw); token = stored.accessToken || ''; }
    } catch (e) {}
    var base = (window.location.host === 'localhost:4200') ? 'http://localhost:8080' : '';
    var headers = {'Content-Type': 'application/json'};
    if (token) headers['Authorization'] = 'Bearer ' + token;
    var sid = '%s';
    if (sid) headers['X-Session-Id'] = sid;
    fetch(base + '/api/controls/%d', { method: 'POST', headers: headers, body: '%s' })
        .then(function(r){
            if (r.status === 401 || r.status === 403) {
                try { window.parent.postMessage({ type: 'SD_SESSION_EXPIRED' }, '*'); } catch (e) {}
            }
        }).catch(function(){});
})();
""" % [_session_id, _character_id, body])

func _dispatch_local(action: String, pressed: bool, ts: int) -> void:
    if _host_bridge == null:
        return
    var local := {"action": action, "pressed": pressed, "timestamp": ts,
        "characterId": _character_id}
    _host_bridge.dispatch_event("movement", JSON.stringify(local))

func _release_movement() -> void:
    # Synthesize pressed=false for any held movement key so the last SSE echo
    # does not leave the player walking while the panel is up.
    for action in _held.keys():
        if action in MOVE_ACTIONS:
            send_control(action, false)
    _held.clear()

func set_shop_open(open: bool) -> void:
    _shop_open = open
    if open:
        _release_movement()

func set_dialogue_open(open: bool) -> void:
    _dialogue_open = open
    if open:
        _release_movement()

# NPC dialogue opened: server-observable control (mirrors SHOP_BUY /
# ENTER_ROOM — the BFF echoes the raw action on the movement SSE channel)
# plus a host postMessage. SD_DIALOGUE_EVENT carries ONLY controlled
# fields: the server-baked npc uuid and a bare portraitLoaded bool. The
# free-form NPC name must NEVER be %-spliced into this eval'd JS source —
# the renderer's bake-time escape() covers GDScript literals, not a live
# String re-spliced into JS, so an ordinary quote/backslash name would
# break eval parsing and a crafted one could forge structured messages to
# window.parent. If a future field needs a caller-supplied string, build
# the payload with JSON.stringify in GDScript and pass it as the sole
# already-encoded eval argument.
func talk_npc(npc_uuid: String, portrait_loaded: bool) -> void:
    send_control("TALK_NPC:" + npc_uuid, true)
    if OS.has_feature("web"):
        var payload := '{"type":"SD_DIALOGUE_EVENT","npc":"%s","portraitLoaded":%s}' % [
            npc_uuid, "true" if portrait_loaded else "false"]
        JavaScriptBridge.eval("""
(function(){ try { window.parent.postMessage(%s, '*'); } catch (e) {} })();
""" % payload)

# Shop transactions are game-local (v1) but ALSO posted as a control so the
# server observes them and player 2's iframe sees the event (the e2e assertion).
func shop_transaction(kind: String, item_slug: String, gold_before: int,
        gold_after: int, qty: int) -> void:
    send_control("SHOP_%s:%s" % [kind.to_upper(), item_slug], true)
    if OS.has_feature("web"):
        var payload := '{"type":"SD_SHOP_EVENT","action":"%s","item":"%s","goldBefore":%d,"goldAfter":%d,"qty":%d}' % [
            kind, item_slug, gold_before, gold_after, qty]
        JavaScriptBridge.eval("""
(function(){ try { window.parent.postMessage(%s, '*'); } catch (e) {} })();
""" % payload)

# Inn rest / church heal — the one genuinely BFF-live economy beat. Goes through
# the HostBridge.request_api seam (host auth interceptor) rather than raw fetch.
func rest_heal() -> void:
    if _host_bridge == null or not _host_bridge.has_method("request_api"):
        return
    var body := '{"currentHp":%d,"currentMp":%d}' % [_max_hp, _max_mp]
    _host_bridge.request_api("PUT", "/api/characters/%d" % _character_id, body)

func request_chapter_transition(chapter_slug: String, chapter_id: int = -1) -> void:
    if not OS.has_feature("web"):
        return
    var payload := '{"type":"TW_CHAPTER_TRANSITION","slug":"%s","chapterId":%d}' % [chapter_slug, chapter_id]
    JavaScriptBridge.eval("""
(function(){ try { window.parent.postMessage(%s, '*'); } catch (e) {} })();
""" % payload)

func _shop_ui() -> Node:
    var world := get_parent()
    if world == null:
        return null
    return world.get_node_or_null("ShopUI")

func _dialogue_ui() -> Node:
    var world := get_parent()
    if world == null:
        return null
    return world.get_node_or_null("DialogueUI")
