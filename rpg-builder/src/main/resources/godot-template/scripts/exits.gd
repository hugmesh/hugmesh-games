# Direction helpers shared by generated rooms + world.gd.
#
# world.gd no longer does key-navigation (game_bridge.gd owns keyboard input),
# so this is NO LONGER a keycode map. It is the single source of direction
# reciprocals used when a player walks through a portal: the ARRIVAL spawn in
# the destination room is the reciprocal-direction doorway. Portal positions +
# entry spawns themselves are baked into each room's ROOM data by the renderer
# (RpgGodotProjectRenderer.Directions) so placement + spawns cannot disagree.
extends RefCounted

const RECIPROCAL := {
    "N": "S", "S": "N", "E": "W", "W": "E",
    "NE": "SW", "SW": "NE", "NW": "SE", "SE": "NW",
    "UP": "DOWN", "DOWN": "UP", "TELEPORT": "TELEPORT",
}

static func reciprocal(dir: String) -> String:
    return RECIPROCAL.get(dir, "TELEPORT")
