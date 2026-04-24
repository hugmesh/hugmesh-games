import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { GameStateService } from '../services/game-state.service';
import { CharacterService } from '../services/character.service';
import { GameState } from '../models/game-state.model';
import { GameTime } from '../models/game-time.model';
import { GameMenuComponent } from '../game-menu/game-menu.component';

@Component({
  selector: 'app-game-hud',
  standalone: true,
  imports: [CommonModule, GameMenuComponent],
  templateUrl: './game-hud.component.html',
  styleUrl: './game-hud.component.scss'
})
export class GameHudComponent {
  state$ = this.gameState.state$;
  connected$ = this.gameState.connected$;
  character$ = this.characterSvc.character$;

  menuOpen = false;

  constructor(
    private gameState: GameStateService,
    private characterSvc: CharacterService
  ) {}

  toggleMenu(): void {
    this.menuOpen = !this.menuOpen;
  }

  positionStr(s: GameState): string {
    const p = s.player;
    return `(${p.x.toFixed(1)}, ${p.y.toFixed(1)}, ${p.z.toFixed(1)})`;
  }

  xpPct(xp: number, xpToNext: number): number {
    return xpToNext > 0 ? Math.min(100, Math.round((xp / xpToNext) * 100)) : 0;
  }

  formatTime(gt: GameTime | undefined): string {
    if (!gt) return '--:--';
    const h = String(gt.hour).padStart(2, '0');
    const m = String(gt.minute).padStart(2, '0');
    return `${h}:${m}`;
  }
}
