import { Injectable, OnDestroy } from '@angular/core';
import { BehaviorSubject, Subject } from 'rxjs';
import { GameAction, GameState, PlayerState } from '../models/game-state.model';
import { CharacterState } from '../models/character.model';
import { environment } from '../../environments/environment';

const DEFAULT_PLAYER: PlayerState = {
  x: 0, y: 0, z: 0, rotationY: 0,
  health: 100, mana: 50,
  level: 1, xp: 0, xpToNext: 100,
  maxHp: 100, maxMana: 50,
  attack: 10, defense: 5,
};

const DEFAULT_STATE: GameState = {
  sessionId: '',
  player: DEFAULT_PLAYER,
  action: null,
  pressed: false,
  timestamp: 0,
  tick: 0,
};

export interface PlayerMovement {
  characterId: number;
  x: number; y: number; z: number; rotationY: number;
  action: GameAction; pressed: boolean; tick: number;
}

export interface WorldState {
  characters: PlayerMovement[];
  tick: number;
}

export interface GameTimeEvent {
  dayNumber: number;
  hour: number;
  minute: number;
}

export interface SceneEventData {
  gameId: number;
  eventType: string;
  elementId: number | null;
  payload: string;
}

@Injectable({ providedIn: 'root' })
export class GameStateService implements OnDestroy {
  // Backward-compat composed state (movement + character + time merged)
  private readonly _state$     = new BehaviorSubject<GameState>(DEFAULT_STATE);
  private readonly _error$     = new Subject<string | null>();
  private readonly _connected$ = new BehaviorSubject<boolean>(false);

  // Typed per-event-type streams
  readonly movement$ = new Subject<PlayerMovement>();
  readonly world$    = new Subject<WorldState>();
  readonly character$ = new Subject<CharacterState>();
  readonly time$     = new Subject<GameTimeEvent>();
  readonly scene$    = new Subject<SceneEventData>();

  readonly state$     = this._state$.asObservable();
  readonly error$     = this._error$.asObservable();
  readonly connected$ = this._connected$.asObservable();

  private _eventSource: EventSource | null = null;
  private _reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  // Track latest values so composed state$ stays coherent
  private _latestPlayer: PlayerState = DEFAULT_PLAYER;
  private _latestTick = 0;
  private _latestAction: GameAction | null = null;

  constructor() { this.connect(); }

  connect(): void {
    this._closeExisting();
    const source = new EventSource(`${environment.apiBase}/api/game/stream`);

    source.onopen = () => {
      this._connected$.next(true);
      this._error$.next(null);
    };

    source.addEventListener('movement', (ev) => {
      try {
        const m: PlayerMovement = JSON.parse((ev as MessageEvent).data);
        this.movement$.next(m);
        // Merge into composed state for backward compat
        this._latestPlayer = {
          ...this._latestPlayer,
          x: m.x, y: m.y, z: m.z, rotationY: m.rotationY,
        };
        this._latestAction = m.action;
        this._latestTick = m.tick;
        this._emitState();
      } catch { /* ignore malformed */ }
    });

    source.addEventListener('world', (ev) => {
      try { this.world$.next(JSON.parse((ev as MessageEvent).data)); }
      catch { /* ignore */ }
    });

    source.addEventListener('character', (ev) => {
      try {
        const c: CharacterState = JSON.parse((ev as MessageEvent).data);
        this.character$.next(c);
        // Merge stats into composed state
        this._latestPlayer = {
          ...this._latestPlayer,
          health: c.currentHp, mana: c.currentMp,
          level: c.level, xp: c.xp, xpToNext: c.xpToNext,
          maxHp: c.maxHp, maxMana: c.maxMp,
          attack: c.attack, defense: c.defense,
        };
        this._emitState();
      } catch { /* ignore */ }
    });

    source.addEventListener('time', (ev) => {
      try { this.time$.next(JSON.parse((ev as MessageEvent).data)); }
      catch { /* ignore */ }
    });

    source.addEventListener('scene', (ev) => {
      try { this.scene$.next(JSON.parse((ev as MessageEvent).data)); }
      catch { /* ignore */ }
    });

    source.onerror = () => {
      this._connected$.next(false);
      this._error$.next('API unreachable — reconnecting in 3 s');
      this._closeExisting();
      this._reconnectTimer = setTimeout(() => this.connect(), 3000);
    };

    this._eventSource = source;
  }

  ngOnDestroy(): void { this._closeExisting(); }

  private _emitState(): void {
    this._state$.next({
      sessionId: '',
      player: this._latestPlayer,
      action: this._latestAction,
      pressed: false,
      timestamp: Date.now(),
      tick: this._latestTick,
    });
  }

  private _closeExisting(): void {
    this._eventSource?.close();
    this._eventSource = null;
    if (this._reconnectTimer) {
      clearTimeout(this._reconnectTimer);
      this._reconnectTimer = null;
    }
  }
}
