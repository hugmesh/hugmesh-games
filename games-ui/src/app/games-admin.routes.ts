import { Routes } from '@angular/router';
import { GamesAdminShellComponent } from './admin-shell.component';
import { GamesAdminOverviewComponent } from './admin-overview.component';
import { GamesAdminLiveComponent } from './admin-live.component';
import { GamesAdminLogsComponent } from './admin-logs.component';
import { GamesAdminAccessComponent } from './admin-access.component';
import { GamesAdminSettingsComponent } from './admin-settings.component';

/**
 * ADMIN routes exposed by the Games native-federation remote as
 * './admin-routes'. The hugmesh-host-shell mounts them at /games/admin behind
 * gamesAdminGuard (isGamesAdmin() || superadmin; isGamesAdmin = games &&
 * (admin || mod)). Everything the screens call lives under
 * /games/api/admin/** (ROLE_GAMES_ADMIN at the fe-gateway) or
 * /games/api/sessions/** (pause / resume).
 */
export const GAMES_ADMIN_ROUTES: Routes = [
  {
    path: '',
    component: GamesAdminShellComponent,
    children: [
      { path: '', pathMatch: 'full', component: GamesAdminOverviewComponent },
      { path: 'sessions', component: GamesAdminLiveComponent },
      { path: 'sessions/:id', component: GamesAdminLiveComponent },
      { path: 'logs', component: GamesAdminLogsComponent },
      { path: 'access', component: GamesAdminAccessComponent },
      { path: 'settings', component: GamesAdminSettingsComponent },
    ],
  },
];
