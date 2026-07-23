package games.hugmesh.rpgbuilder.entity;

/**
 * Mudlet-shaped direction set on a room exit. The build worker maps
 * each value to a coordinate offset (or, for TELEPORT, a scripted
 * scene-change call) when it generates the per-room GDScript.
 */
public enum Direction { N, S, E, W, NE, NW, SE, SW, UP, DOWN, TELEPORT }
