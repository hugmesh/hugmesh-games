/**
 * Pure, framework-free navigation engine for the in-studio VN preview.
 *
 * ALL branching logic lives here as pure functions — given the project's
 * scenes / lines / choices it computes the next preview state. There are NO
 * Angular / DOM / HTTP imports: the component feeds it plain data (loaded
 * lazily per scene) and renders whatever state comes back, so every
 * traversal rule is unit-testable in isolation.
 *
 * Traversal model (mirrors the vn-builder data model):
 *   - start() begins at the scene whose entryPoint === true; if none is
 *     flagged it falls back to the lowest sortOrder scene.
 *   - within a scene we walk lines in ascending lineIndex order.
 *   - a line MAY carry choices — when the current line has choices the
 *     player picks one and choose(choiceId) jumps to that choice's
 *     targetSceneId (resetting to that scene's first line).
 *   - linear advance with no choice on the current line = next lineIndex.
 *   - reaching the end of a scene whose final line has no choices ends the
 *     preview gracefully ("The End").
 *
 * The engine is data-in / next-state-out: it never fetches. The driver is
 * responsible for having loaded the relevant scene's lines (and a line's
 * choices) before asking the engine to surface them — hasScene / hasLines
 * let the driver detect a not-yet-loaded scene and fetch on demand.
 */

/** Minimal shape the engine needs from a VnScene (component owns the full interface). */
export interface VnPlayerScene {
  id: string;
  backgroundId?: string;
  sortOrder: number;
  entryPoint: boolean;
}

/** Minimal shape the engine needs from a VnLine. */
export interface VnPlayerLine {
  id: string;
  sceneId: string;
  lineIndex: number;
  characterId?: string;
  text: string;
  kind?: string;
}

/** Minimal shape the engine needs from a VnChoice. */
export interface VnPlayerChoice {
  id: string;
  lineId: string;
  label: string;
  targetSceneId: string;
  sortOrder: number;
}

/**
 * The full preview state the driver renders. `done` true means the player
 * has walked off the end of a terminal scene — render "The End".
 */
export interface VnPlayerState {
  /** id of the scene currently being shown (null only before start()). */
  sceneId: string | null;
  /** id of the line currently being shown, or null when the scene has no lines / preview ended. */
  lineId: string | null;
  /** the line object currently shown (resolved from the data given to the engine). */
  line: VnPlayerLine | null;
  /** the choices on the current line (caller-supplied), sorted; empty = linear advance / end. */
  choices: VnPlayerChoice[];
  /** true once the preview has reached a graceful end. */
  done: boolean;
}

/** Lines for a single scene, ordered ascending by lineIndex (pure helper). */
export function orderLines(lines: VnPlayerLine[]): VnPlayerLine[] {
  return [...lines].sort((a, b) => a.lineIndex - b.lineIndex);
}

/** Choices for a line, ordered ascending by sortOrder (pure helper). */
export function orderChoices(choices: VnPlayerChoice[]): VnPlayerChoice[] {
  return [...choices].sort((a, b) => a.sortOrder - b.sortOrder);
}

/**
 * Pick the scene the preview should start at: the entryPoint scene if one is
 * flagged, otherwise the lowest sortOrder scene. Returns null when there are
 * no scenes at all.
 */
export function pickStartScene(scenes: VnPlayerScene[]): VnPlayerScene | null {
  if (scenes.length === 0) return null;
  const entry = scenes.find(s => s.entryPoint);
  if (entry) return entry;
  return [...scenes].sort((a, b) => a.sortOrder - b.sortOrder)[0];
}

const ENDED: VnPlayerState = { sceneId: null, lineId: null, line: null, choices: [], done: true };

/**
 * Build the state for the first line of a scene. `lines` is the (possibly
 * unsorted) set of lines for THAT scene. A scene with no lines is treated as
 * an immediate graceful end (nothing to show).
 */
export function enterScene(scene: VnPlayerScene, lines: VnPlayerLine[]): VnPlayerState {
  const ordered = orderLines(lines);
  if (ordered.length === 0) {
    return { sceneId: scene.id, lineId: null, line: null, choices: [], done: true };
  }
  const first = ordered[0];
  return { sceneId: scene.id, lineId: first.id, line: first, choices: [], done: false };
}

/**
 * Surface the choices for the currently-shown line. The driver loads a line's
 * choices lazily and hands them here so the engine can attach them to state.
 */
export function withChoices(state: VnPlayerState, choices: VnPlayerChoice[]): VnPlayerState {
  return { ...state, choices: orderChoices(choices) };
}

/**
 * Whether the current line is the last line of its scene (used by the driver
 * to decide whether `advance` will end the preview).
 */
export function isLastLineOfScene(state: VnPlayerState, sceneLines: VnPlayerLine[]): boolean {
  if (!state.line) return true;
  const ordered = orderLines(sceneLines);
  return ordered[ordered.length - 1]?.id === state.line.id;
}

/**
 * Advance linearly to the next line of the current scene. `sceneLines` is the
 * full set of lines for the current scene. If the current line is the last in
 * the scene the preview ends gracefully. Choices are NOT followed here — the
 * driver calls advance() only when the current line has no choices (Next), and
 * choose() when it does.
 */
export function advance(state: VnPlayerState, sceneLines: VnPlayerLine[]): VnPlayerState {
  if (state.done || !state.line) return ENDED;
  const ordered = orderLines(sceneLines);
  const idx = ordered.findIndex(l => l.id === state.line!.id);
  if (idx < 0 || idx >= ordered.length - 1) {
    // unknown line OR last line of the scene with no choices -> graceful end.
    return { sceneId: state.sceneId, lineId: null, line: null, choices: [], done: true };
  }
  const next = ordered[idx + 1];
  return { sceneId: state.sceneId, lineId: next.id, line: next, choices: [], done: false };
}

/**
 * Follow a choice: jump to the target scene's first line. `choice` is the
 * picked choice; `targetScene` is the resolved VnPlayerScene it points at and
 * `targetLines` are that scene's lines (the driver fetches them lazily before
 * calling). A choice whose target scene can't be resolved ends the preview
 * gracefully rather than throwing.
 */
export function choose(
  choice: VnPlayerChoice | undefined,
  targetScene: VnPlayerScene | null,
  targetLines: VnPlayerLine[],
): VnPlayerState {
  if (!choice || !targetScene) return ENDED;
  return enterScene(targetScene, targetLines);
}

/** The initial, pre-start() state. */
export function initialState(): VnPlayerState {
  return { sceneId: null, lineId: null, line: null, choices: [], done: false };
}
