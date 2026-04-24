/** Umbrella game metadata. Levels (with assetUrl/sceneType) live under it — see Level model. */
export interface GameListing {
  id: number;
  name: string;
  description: string;
  slug: string;
  thumbnailUrl: string | null;
  sortOrder: number;
}
