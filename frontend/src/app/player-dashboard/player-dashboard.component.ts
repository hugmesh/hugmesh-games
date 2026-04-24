import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Observable, Subscription, of } from 'rxjs';
import { catchError, tap } from 'rxjs/operators';
import { DashboardService } from '../services/dashboard.service';
import { QuestService } from '../services/quest.service';
import { GameStateService } from '../services/game-state.service';
import { Dashboard } from '../models/dashboard.model';
import { SessionHistory } from '../models/session-history.model';
import { AvailableGame } from '../models/available-game.model';
import { GameTime } from '../models/game-time.model';
import { CharacterState } from '../models/character.model';

@Component({
  selector: 'app-player-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './player-dashboard.component.html',
  styleUrl: './player-dashboard.component.scss',
})
export class PlayerDashboardComponent implements OnInit, OnDestroy {
  private readonly dashboardSvc = inject(DashboardService);
  private readonly questSvc     = inject(QuestService);
  private readonly gameState    = inject(GameStateService);

  characterId = 1;

  dashboard: Dashboard | null = null;
  history$: Observable<SessionHistory[]> = of([]);
  errorMessage = '';

  // Live values overlay the snapshot from SSE streams (no iframe needed)
  liveCharacter: CharacterState | null = null;
  liveTime: GameTime | null = null;

  private _subs = new Subscription();

  ngOnInit(): void {
    this.load();

    this._subs.add(this.gameState.character$.subscribe(c => this.liveCharacter = c));
    this._subs.add(this.gameState.time$.subscribe(t => this.liveTime = t));
  }

  ngOnDestroy(): void {
    this._subs.unsubscribe();
  }

  load(): void {
    this.errorMessage = '';
    this.dashboardSvc.getDashboard(this.characterId).pipe(
      tap(d => this.dashboard = d),
      catchError(err => {
        this.errorMessage = err.status === 401
          ? 'Please log in again.'
          : 'Failed to load dashboard.';
        return of(null);
      })
    ).subscribe();

    this.history$ = this.dashboardSvc.getSessionHistory().pipe(
      catchError(() => of([]))
    );
  }

  completeQuest(questId: number): void {
    this.questSvc.completeQuest(questId, this.characterId)
      .pipe(catchError(() => of(null)))
      .subscribe(() => this.load());
  }

  /** Display value: live SSE value first, otherwise the snapshot. */
  get character(): CharacterState | null {
    return this.liveCharacter ?? this.dashboard?.character ?? null;
  }

  get gameTime(): GameTime | null {
    return this.liveTime ?? this.dashboard?.gameTime ?? null;
  }

  formatTime(t: GameTime | null): string {
    if (!t) return '—';
    const hh = t.hour.toString().padStart(2, '0');
    const mm = t.minute.toString().padStart(2, '0');
    return `Day ${t.dayNumber}  ${hh}:${mm}`;
  }

  questCompleted(q: { status: string | null }): boolean {
    return q.status?.toLowerCase() === 'completed';
  }

  /** Flatten nested games→levels to a single list for the Levels section. */
  allLevels(): AvailableGame[] {
    return (this.dashboard?.games ?? []).flatMap(g => g.levels);
  }

  unlockedLevels(): AvailableGame[] {
    return this.allLevels().filter(l => l.unlocked);
  }

  lockedLevels(): AvailableGame[] {
    return this.allLevels().filter(l => !l.unlocked);
  }
}
