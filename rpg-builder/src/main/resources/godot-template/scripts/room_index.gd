# Placeholder — hugmesh-rpg-builder overwrites this at render time with the
# real room-UUID → scene-path map + the area → start-room-scene map
# (RpgGodotProjectRenderer.writeRoomIndex). Kept in the template so
# scripts/world.gd's `preload("res://scripts/room_index.gd")` resolves
# standalone (before a build renders the project).
const ROOM_SCENES := {}
# area slug → the scene path world.gd boots when that area's chapter loads.
const AREA_START := {}
