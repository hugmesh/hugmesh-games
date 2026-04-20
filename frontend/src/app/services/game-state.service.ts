import { Injectable, OnDestroy } from '@angular/core';
import { BehaviorSubject, Subject } from 'rxjs';
import { GameState, PlayerState } from '../models/game-state.model';
import { environment } from '../../environments/environment';

const DEFAULT_PLAYER: PlayerState = { x: 0, y: 0, z: 0, rotationY: 0, health: 100, mana: 100 };
const DEFAULT_STATE: GameState = {
  sessionId: '',
  player: DEFAULT_PLAYER,
  action: null,
  pressed: false,
  timestamp: 0,
  tick: 0,
};

@Injectable({ providedIn: 'root' })
export class GameStateService implements OnDestroy {
  private readonly _state$ = new BehaviorSubject<GameState>(DEFAULT_STATE);
  private readonly _error$ = new Subject<string | null>();
  private readonly _connected$ = new BehaviorSubject<boolean>(false);
  private _eventSource: EventSource | null = null;
  private _reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  readonly state$ = this._state$.asObservable();
  readonly error$ = this._error$.asObservable();
  readonly connected$ = this._connected$.asObservable();

  constructor() {
    this.connect();
  }

  connect(): void {
    this._closeExisting();
    // Use environment.apiBase so dev connects directly to Spring Boot (bypassing
    // the Vite proxy which injects connection:close and kills the SSE stream).
    const source = new EventSource(`${environment.apiBase}/api/game/stream`);

    source.onopen = () => {
      this._connected$.next(true);
      this._error$.next(null);
    };

    source.addEventListener('game-state', (ev) => {
      try {
        const state: GameState = JSON.parse((ev as MessageEvent).data);
        this._state$.next(state);
      } catch {
        this._error$.next('Failed to parse game state');
      }
    });

    source.onerror = () => {
      this._connected$.next(false);
      this._error$.next('API unreachable — reconnecting in 3s');
      this._closeExisting();
      this._reconnectTimer = setTimeout(() => this.connect(), 3000);
    };

    this._eventSource = source;
  }

  ngOnDestroy(): void {
    this._closeExisting();
  }

  private _closeExisting(): void {
    if (this._eventSource) {
      this._eventSource.close();
      this._eventSource = null;
    }
    if (this._reconnectTimer) {
      clearTimeout(this._reconnectTimer);
      this._reconnectTimer = null;
    }
  }
}
