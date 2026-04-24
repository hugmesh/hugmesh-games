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

/** Umbrella game with its levels nested — returned by /api/games/available/{charId}. */
export interface GameWithLevels {
  id: number;
  name: string;
  description: string;
  slug: string;
  thumbnailUrl: string | null;
  sortOrder: number;
  levels: AvailableGame[];
}
