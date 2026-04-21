export interface GameListing {
  id: number;
  name: string;
  description: string;
  slug: string;
  thumbnailUrl: string | null;
  sceneConfig: string | null;
}
