export interface QuestObjective {
  id: number;
  description: string;
  objectiveType: string;
  requiredCount: number;
}

export interface Quest {
  id: number;
  name: string;
  description: string;
  questType: string;
  requiredLevel: number;
  rewardXp: number;
  rewardGold: number;
  objectives: QuestObjective[];
}

export interface CharacterQuest {
  id: number;
  quest: Quest;
  status: string;
  progress: number;
}
