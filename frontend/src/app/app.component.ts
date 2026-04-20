import { Component } from '@angular/core';
import { GameCanvasComponent } from './game-canvas/game-canvas.component';
import { GameControlsComponent } from './game-controls/game-controls.component';
import { GameHudComponent } from './game-hud/game-hud.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [GameCanvasComponent, GameControlsComponent, GameHudComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent {}
