/** One playable chapter = one Godot WASM bundle. Belongs to an umbrella Game. */
export interface Chapter {
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
