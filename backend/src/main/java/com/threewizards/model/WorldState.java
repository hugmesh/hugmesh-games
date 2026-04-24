package com.threewizards.model;

import java.util.List;

/** Emitted on the shared world SSE stream whenever any character moves. */
public record WorldState(List<PlayerMovement> characters, long tick) {}
