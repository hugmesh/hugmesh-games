/**
 * A Level enriched with per-character unlock info. Kept under the `AvailableGame`
 * filename for backward-compat but semantically represents a Level in the
 * games → levels → scenes hierarchy.
 */
export interface AvailableGame {
  id: number;
  gameId: number;
  name: string;
  description: string;
  slug: string;
  sceneType: string;
  assetUrl: string | null;
  thumbnailUrl: string | null;
  sortOrder: number;
  unlocked: boolean;
  requiredQuestIds: number[];
  completedQuestIds: number[];
  totalQuests: number;
  completedQuests: number;
}

/** Umbrella game with its chapters nested — returned by
 *  /api/games/available/{characterId}. The home grid shows one card
 *  per game; clicking opens a modal listing the embedded chapters. */
export interface GameWithChapters {
  id: number;
  name: string;
  description: string;
  slug: string;
  thumbnailUrl: string | null;
  sortOrder: number;
  chapters: AvailableGame[];
}
