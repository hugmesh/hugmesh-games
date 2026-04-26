export interface CharacterState {
  id: number;
  name: string;
  level: number;
  xp: number;
  xpToNext: number;
  maxHp: number;
  currentHp: number;
  maxMp: number;
  currentMp: number;
  attack: number;
  defense: number;
  speed: number;
  magicPower: number;
  currentChapterId: number | null;
}
