import { GameTime } from './game-time.model';

export type GameAction =
  | 'MOVE_FORWARD'
  | 'MOVE_BACKWARD'
  | 'MOVE_LEFT'
  | 'MOVE_RIGHT'
  | 'JUMP'
  | 'CAST_SPELL_1'
  | 'CAST_SPELL_2'
  | 'CAST_SPELL_3'
  | 'INTERACT';

export interface ControlEvent {
  action: GameAction;
  pressed: boolean;
  timestamp: number;
}

export interface PlayerState {
  x: number;
  y: number;
  z: number;
  rotationY: number;
  health: number;
  mana: number;
  level: number;
  xp: number;
  xpToNext: number;
  maxHp: number;
  maxMana: number;
  attack: number;
  defense: number;
}

export interface GameState {
  sessionId: string;
  player: PlayerState;
  action: GameAction | null;
  pressed: boolean;
  timestamp: number;
  tick: number;
  gameTime?: GameTime;
}
