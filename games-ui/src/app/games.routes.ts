import { Routes } from '@angular/router';
import { GamesCatalogComponent } from './catalog.component';
import { GamePlayComponent } from './play.component';

/**
 * MEMBER routes exposed by the Games native-federation remote as './routes'.
 * The hugmesh-host-shell mounts them at /games behind gamesGuard
 * (games || admin || superadmin):
 *   ''            -> M1 catalog (every game the member can play)
 *   'play/:slug'  -> M2 play (launch + same-origin iframe / new-tab opener)
 * Every link inside is relative to the mount route; the remote never assumes
 * a base href and never calls bare /api/* (only /games/api/**).
 */
export const GAMES_ROUTES: Routes = [
  { path: '', component: GamesCatalogComponent },
  { path: 'play/:slug', component: GamePlayComponent },
];
