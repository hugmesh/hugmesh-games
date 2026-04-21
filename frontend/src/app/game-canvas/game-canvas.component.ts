import { Component, Input, OnChanges, OnDestroy, ElementRef, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { GameStateService } from '../services/game-state.service';
import { GameState } from '../models/game-state.model';

@Component({
  selector: 'app-game-canvas',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './game-canvas.component.html',
  styleUrl: './game-canvas.component.scss',
})
export class GameCanvasComponent implements OnChanges, OnDestroy {
  @Input() gameId: number = 1;
  @ViewChild('gameFrame') gameFrame!: ElementRef<HTMLIFrameElement>;

  gameLoaded = false;
  frameSrc = 'assets/game/index.html?game_id=1';

  private _sub: Subscription | null = null;

  constructor(private gameState: GameStateService) {}

  ngOnChanges(): void {
    this.gameLoaded = false;
    this.frameSrc = `assets/game/index.html?game_id=${this.gameId}`;
    if (!this._sub) {
      this._sub = this.gameState.state$.subscribe(s => this._postState(s));
    }
  }

  ngOnDestroy(): void {
    this._sub?.unsubscribe();
  }

  onFrameLoad(): void {
    this.gameLoaded = true;
  }

  private _postState(state: GameState): void {
    const frame = this.gameFrame?.nativeElement;
    if (frame?.contentWindow) {
      frame.contentWindow.postMessage({ type: 'GAME_STATE', payload: state }, '*');
    }
  }
}
