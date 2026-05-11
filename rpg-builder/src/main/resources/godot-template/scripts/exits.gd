# Direction keymap used by world.gd.
extends RefCounted

static func direction_for_keycode(k: int) -> String:
    match k:
        KEY_W, KEY_UP:    return "N"
        KEY_S, KEY_DOWN:  return "S"
        KEY_A, KEY_LEFT:  return "W"
        KEY_D, KEY_RIGHT: return "E"
        KEY_Q:            return "NW"
        KEY_E:            return "NE"
        KEY_Z:            return "SW"
        KEY_C:            return "SE"
        KEY_PAGEUP:       return "UP"
        KEY_PAGEDOWN:     return "DOWN"
        KEY_T:            return "TELEPORT"
        _: return ""
