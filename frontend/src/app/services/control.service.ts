import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Subject } from 'rxjs';
import { ControlEvent, GameAction } from '../models/game-state.model';

@Injectable({ providedIn: 'root' })
export class ControlService {
  private readonly http = inject(HttpClient);

  private _characterId = 1;
  readonly controlPressed$ = new Subject<ControlEvent>();

  /** When true, sendControl() drops every event before it touches the API.
   *  Lets the game menu / dashboard suppress player input while a modal is
   *  open without reaching into every key-handler component to add gates. */
  readonly inputSuppressed$ = new BehaviorSubject<boolean>(false);

  setCharacterId(id: number): void {
    this._characterId = id;
  }

  setInputSuppressed(suppressed: boolean): void {
    this.inputSuppressed$.next(suppressed);
  }

  sendControl(action: GameAction, pressed: boolean): void {
    if (this.inputSuppressed$.value) return;

    const event: ControlEvent = { action, pressed, timestamp: Date.now() };
    this.controlPressed$.next(event);

    // No manual X-Casdoor-Id header anymore — that was a custom-trust
    // channel any client could spoof. http-auth.interceptor adds
    // `Authorization: Bearer <jwt>` for every /api/* request, and the
    // BFF's ControlController reads `sub` from the verified JWT.
    this.http.post(`/api/controls/${this._characterId}`, event).subscribe({
      error: err => console.warn('Control event failed:', err),
    });
  }
}
