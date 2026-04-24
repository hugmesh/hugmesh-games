/** One playable level = one Godot WASM bundle. Belongs to an umbrella Game. */
export interface Level {
  id: number;
  gameId: number;
  name: string;
  description: string;
  slug: string;
  sceneType: string;
  assetUrl: string | null;
  thumbnailUrl: string | null;
  sortOrder: number;
}
