package com.threewizards.model;

/** Emitted on the per-scene SSE stream when a scene object changes state. */
public record SceneEvent(long levelId, String eventType, Long elementId, String payload) {}
