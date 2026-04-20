import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Subject } from 'rxjs';
import { ControlEvent, GameAction } from '../models/game-state.model';

@Injectable({ providedIn: 'root' })
export class ControlService {
  private readonly apiUrl = '/api/controls';
  readonly controlPressed$ = new Subject<ControlEvent>();

  constructor(private http: HttpClient) {}

  sendControl(action: GameAction, pressed: boolean): void {
    const event: ControlEvent = { action, pressed, timestamp: Date.now() };
    this.controlPressed$.next(event);
    this.http.post(this.apiUrl, event).subscribe({
      error: err => console.warn('Control event failed:', err)
    });
  }
}
