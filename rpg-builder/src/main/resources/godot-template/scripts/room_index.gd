# Placeholder — hugmesh-rpg-builder overwrites this at render time with the real
# room-UUID → scene-path map (RpgGodotProjectRenderer.writeRoomIndex). Kept in the
# template so scripts/world.gd's `preload("res://scripts/room_index.gd")` resolves
# standalone (before a build renders the project).
const ROOM_SCENES := {}
