export interface Skill {
  id: number;
  name: string;
  description: string;
  skillType: string;
  mpCost: number;
  damage: number;
  cooldown: number;
  requiredLevel: number;
  iconKey: string;
}

export interface SkillSlots {
  [slot: number]: Skill | null;
}
