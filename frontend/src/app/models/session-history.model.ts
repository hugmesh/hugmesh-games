export interface CoPlayer {
  casdoorId: string;
  characterName: string | null;
}

export interface SessionHistory {
  id: number;
  sessionToken: string;
  characterId: number;
  isController: boolean;
  connectedAt: string;
  disconnectedAt: string | null;
  durationMinutes: number;
  coPlayers: CoPlayer[];
}
