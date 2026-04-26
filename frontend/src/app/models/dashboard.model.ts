import { CharacterState } from './character.model';
import { GameTime } from './game-time.model';
import { GameWithChapters } from './available-game.model';

export interface DashboardQuest {
  id: number;
  name: string;
  description: string;
  rewardXp: number;
  rewardGold: number;
  status: string | null;
}

export interface Dashboard {
  character: CharacterState;
  gameTime: GameTime;
  /** Umbrella games with their levels nested (games → levels → scenes hierarchy). */
  games: GameWithChapters[];
  quests: DashboardQuest[];
  totalPlayMinutes: number;
  totalSessions: number;
}
