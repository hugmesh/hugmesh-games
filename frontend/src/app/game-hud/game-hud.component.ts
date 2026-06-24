import { Component, OnInit, inject, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { GameStateService } from '../services/game-state.service';
import { CharacterService } from '../services/character.service';
import { ControlService } from '../services/control.service';
import { GameState } from '../models/game-state.model';
import { GameTime } from '../models/game-time.model';
import { GameMenuComponent } from '../game-menu/game-menu.component';

@Component({
  selector: 'app-game-hud',
  standalone: true,
  imports: [CommonModule, GameMenuComponent],
  templateUrl: './game-hud.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './game-hud.component.scss'
})
export class GameHudComponent implements OnInit {
  private readonly gameState = inject(GameStateService);
  private readonly characterSvc = inject(CharacterService);
  private readonly controlService = inject(ControlService);

  state$ = this.gameState.state$;
  connected$ = this.gameState.connected$;
  /** Single source of truth for the character — CharacterService now forwards
   *  SSE `character` events into its BehaviorSubject so HP/MP, level, XP all
   *  stay in sync without depending on the composed game-state pipeline. */
  character$ = this.characterSvc.character$;

  menuOpen = false;

  ngOnInit(): void {
    // Hydrate the initial character snapshot so the HUD doesn't sit on
    // DEFAULT_PLAYER until the first SSE character event arrives.
    this.characterSvc.load(1).subscribe({ error: () => {} });
  }

  toggleMenu(): void {
    this.menuOpen = !this.menuOpen;
    // Suppress player input while menu is open so keys don't leak through
    // and accidental backdrop clicks don't reach the iframe.
    this.controlService.setInputSuppressed(this.menuOpen);
  }

  closeMenu(): void {
    this.menuOpen = false;
    this.controlService.setInputSuppressed(false);
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
