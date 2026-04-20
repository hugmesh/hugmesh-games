import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { GameStateService } from '../services/game-state.service';
import { GameState } from '../models/game-state.model';

@Component({
  selector: 'app-game-hud',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './game-hud.component.html',
  styleUrl: './game-hud.component.scss'
})
export class GameHudComponent {
  state$ = this.gameState.state$;
  connected$ = this.gameState.connected$;

  constructor(private gameState: GameStateService) {}

  positionStr(s: GameState): string {
    const p = s.player;
    return `(${p.x.toFixed(1)}, ${p.y.toFixed(1)}, ${p.z.toFixed(1)})`;
  }
}
