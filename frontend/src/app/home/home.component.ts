import {
  Component, ElementRef, HostListener, OnInit, ViewChild, inject
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { GameLibraryService } from '../services/game-library.service';
import { GameListing } from '../models/game-listing.model';
import { GameCanvasComponent } from '../game-canvas/game-canvas.component';
import { GameHudComponent } from '../game-hud/game-hud.component';
import { GameControlsComponent } from '../game-controls/game-controls.component';
import { Observable, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule, GameCanvasComponent, GameHudComponent, GameControlsComponent],
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss',
})
export class HomeComponent implements OnInit {
  @ViewChild('canvasContainer') canvasContainer!: ElementRef<HTMLDivElement>;
  @ViewChild('canvasSection') canvasSection!: ElementRef<HTMLElement>;

  private readonly library = inject(GameLibraryService);

  games$: Observable<GameListing[]> = of([]);
  activeGame: GameListing | null = null;
  isFullscreen = false;

  ngOnInit(): void {
    this.games$ = this.library.listGames().pipe(catchError(() => of([])));
  }

  selectGame(game: GameListing): void {
    this.activeGame = game;
    setTimeout(() => {
      this.canvasSection?.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }, 50);
  }

  closeGame(): void {
    if (this.isFullscreen) document.exitFullscreen();
    this.activeGame = null;
    this.isFullscreen = false;
  }

  toggleFullscreen(): void {
    if (!this.isFullscreen) {
      this.canvasContainer.nativeElement.requestFullscreen();
    } else {
      document.exitFullscreen();
    }
  }

  @HostListener('document:fullscreenchange')
  onFullscreenChange(): void {
    this.isFullscreen = !!document.fullscreenElement;
  }
}
