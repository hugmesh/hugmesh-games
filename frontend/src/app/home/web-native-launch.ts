import { AvailableGame } from '../models/available-game.model';
import { FederationRemote } from '../services/federation.service';

/**
 * Pure launch-decision helpers for web-native (plain-iframe) games.
 *
 * Extracted from HomeComponent so the launch decision is unit-testable
 * without a browser/karma. The whole point is that the decision is
 * DATA-DRIVEN off the federation manifest: a chapter is "web-native"
 * iff its owning game has an enabled `wasm-game` federation row. No
 * per-game slug list lives in the frontend — seed a `wasm-game` row and
 * the game launches as an iframe automatically.
 *
 * `webNativeByGameSlug` is the Map produced by
 * `FederationService.webNativeByGameSlug()` (gameSlug → row).
 *
 * A web-native game has exactly one chapter whose `slug` equals the
 * games.slug (see migrations 044-048: chapter.slug == game.slug ==
 * scene_type for every web game). We resolve the game slug from the
 * chapter via `chapter.slug` first, falling back to `chapter.sceneType`
 * — both are equal for these games, but keeping both keeps the helper
 * robust if a future multi-chapter web game gives each chapter a
 * distinct slug while sharing the parent scene_type.
 */
export function resolveWebNativeRemote(
  chapter: AvailableGame | null | undefined,
  webNativeByGameSlug: Map<string, FederationRemote>,
): FederationRemote | null {
  if (!chapter) return null;
  return (
    webNativeByGameSlug.get(chapter.slug) ??
    webNativeByGameSlug.get(chapter.sceneType) ??
    null
  );
}

/** True iff the chapter launches as a web-native iframe bundle. */
export function isWebNativeChapter(
  chapter: AvailableGame | null | undefined,
  webNativeByGameSlug: Map<string, FederationRemote>,
): boolean {
  return resolveWebNativeRemote(chapter, webNativeByGameSlug) !== null;
}

/** The games.slug the web-native chapter belongs to (for `/launch`). */
export function webNativeSlug(
  chapter: AvailableGame | null | undefined,
  webNativeByGameSlug: Map<string, FederationRemote>,
): string | null {
  return resolveWebNativeRemote(chapter, webNativeByGameSlug)?.gameSlug ?? null;
}

/** The wasm-fragment iframe base URL for the chapter (no query string). */
export function webNativeBaseUrl(
  chapter: AvailableGame | null | undefined,
  webNativeByGameSlug: Map<string, FederationRemote>,
): string | null {
  return resolveWebNativeRemote(chapter, webNativeByGameSlug)?.remoteEntryUrl ?? null;
}
