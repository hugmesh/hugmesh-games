/** Umbrella game metadata. Chapters (with assetUrl/sceneType) live under it — see Chapter model. */
export interface GameListing {
  id: number;
  name: string;
  description: string;
  slug: string;
  thumbnailUrl: string | null;
  sortOrder: number;
}
