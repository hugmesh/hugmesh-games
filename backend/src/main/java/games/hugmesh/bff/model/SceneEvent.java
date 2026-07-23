package games.hugmesh.bff.model;

/** Emitted on the per-scene SSE stream when a scene object changes state. */
public record SceneEvent(long chapterId, String eventType, Long elementId, String payload) {}
