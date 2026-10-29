import { Routes } from '@angular/router';
import { GAMES_ROUTES } from './games.routes';
import { GAMES_ADMIN_ROUTES } from './games-admin.routes';

/**
 * Dev-shell routes for standalone development (`ng serve games`). Mirrors the
 * host's two mounts — `games/admin` (GAMES_ADMIN_ROUTES) BEFORE `games`
 * (GAMES_ROUTES), the same ordering rule the host's REMOTE_ROUTES has — at the
 * document base, so with `--base-href=/games/` the standalone URLs are the
 * production ones (/games, /games/play/chess, /games/admin/sessions). The host
 * shell ignores this file and loads the two exposed route constants directly.
 */
export const routes: Routes = [
  { path: 'admin', children: GAMES_ADMIN_ROUTES },
  { path: '', children: GAMES_ROUTES },
];
