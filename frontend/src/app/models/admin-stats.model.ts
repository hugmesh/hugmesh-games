export interface PerUserStats {
  casdoorId: string;
  characterName: string | null;
  sessionCount: number;
  totalMinutes: number;
  online: boolean;
}

export interface AdminStats {
  totalUsers: number;
  totalSessions: number;
  currentlyActive: number;
  averageSessionMinutes: number;
  totalCharacters: number;
  perUser: PerUserStats[];
}
