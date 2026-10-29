import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * Standalone dev shell for the Games remote. Only used when this remote is run
 * on its own (`ng serve games` / the screenshot harness); in production the
 * hugmesh-host-shell federates `./routes` (GAMES_ROUTES) and `./admin-routes`
 * (GAMES_ADMIN_ROUTES) directly and this shell is never loaded. It supplies a
 * skip link + a <main id="main"> landmark so the remote is keyboard-accessible
 * when served standalone.
 */
@Component({
  selector: 'hug-games-root',
  standalone: true,
  imports: [RouterOutlet],
  template: `
    <a class="skip" href="#main">Skip to content</a>
    <main id="main" class="hug-games-main">
      <router-outlet></router-outlet>
    </main>
  `,
})
export class AppComponent {}
