import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { AuthService } from './auth.service';
import { BehaviorSubject, Subject } from 'rxjs';
import { ControlEvent, GameAction } from '../models/game-state.model';

@Injectable({ providedIn: 'root' })
export class ControlService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

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

    const claims = this.auth.getClaims() as { sub?: string } | null;
    const casdoorId = claims?.sub ?? '';
    const headers = new HttpHeaders({ 'X-Casdoor-Id': casdoorId });

    this.http.post(`/api/controls/${this._characterId}`, event, { headers }).subscribe({
      error: err => console.warn('Control event failed:', err),
    });
  }
}
