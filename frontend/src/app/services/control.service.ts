import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { AuthService } from './auth.service';
import { Subject } from 'rxjs';
import { ControlEvent, GameAction } from '../models/game-state.model';

@Injectable({ providedIn: 'root' })
export class ControlService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  private _characterId = 1;
  readonly controlPressed$ = new Subject<ControlEvent>();

  setCharacterId(id: number): void {
    this._characterId = id;
  }

  sendControl(action: GameAction, pressed: boolean): void {
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
