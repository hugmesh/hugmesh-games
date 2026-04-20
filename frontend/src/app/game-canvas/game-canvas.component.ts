import { Component, AfterViewInit, OnDestroy, ElementRef, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { GameStateService } from '../services/game-state.service';
import { GameState } from '../models/game-state.model';

@Component({
  selector: 'app-game-canvas',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './game-canvas.component.html',
  styleUrl: './game-canvas.component.scss'
})
export class GameCanvasComponent implements AfterViewInit, OnDestroy {
  @ViewChild('gameFrame') gameFrame!: ElementRef<HTMLIFrameElement>;

  gameLoaded = false;
  private _sub: Subscription | null = null;

  constructor(private gameState: GameStateService) {}

  // Subscribe only after the view (and iframe element) is ready
  ngAfterViewInit(): void {
    this._sub = this.gameState.state$.subscribe(state => this._postStateToGame(state));
  }

  ngOnDestroy(): void {
    this._sub?.unsubscribe();
  }

  onFrameLoad(): void {
    this.gameLoaded = true;
  }

  private _postStateToGame(state: GameState): void {
    const frame = this.gameFrame?.nativeElement;
    if (frame?.contentWindow) {
      frame.contentWindow.postMessage({ type: 'GAME_STATE', payload: state }, '*');
    }
  }
}
