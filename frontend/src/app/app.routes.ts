import { Routes } from '@angular/router';
import { Type } from '@angular/core';
import { HomeComponent } from './home/home.component';
import { AboutComponent } from './about/about.component';
import { PlayerDashboardComponent } from './player-dashboard/player-dashboard.component';
import { BannedPageComponent } from './banned-page/banned-page.component';
import { FriendsComponent } from './friends/friends.component';
import { SessionLogPageComponent } from './session-log/session-log-page.component';
import { AdminSessionLogPageComponent } from './session-log/admin-session-log-page.component';
import { authGuard } from './auth.guard';

// Federated route loaders. main.ts stashes
// `loadRemoteModule` from @angular-architects/native-federation on the
// global so route resolvers can reach it without statically importing
// the federation package (karma's webpack parser can't read its ESM
// index.js — see RemoteLoaderService for the longer note). If the
// global isn't present (federation init failed), the route activation
// rejects and the router emits a NavigationError; the user lands back
// on the previous URL. Keeping the inline login form alive as a
// degraded fallback is deferred until we wire a global error
// boundary that can swap the route at runtime.
type LoadRemoteModuleFn = (opts: { remoteName: string; exposedModule: string }) => Promise<Record<string, unknown>>;
// Exported for the unit spec — the helper is the single
// integration point with the federation runtime, so its error paths
// (missing global, missing class export) deserve direct coverage.
// Routes below are the only production callers.
export function loadRemote<T>(remoteName: string, exposedModule: string, exportName: string): Promise<Type<T>> {
  const fn = (globalThis as { __HUGMESH_LOAD_REMOTE_MODULE__?: LoadRemoteModuleFn }).__HUGMESH_LOAD_REMOTE_MODULE__;
  if (!fn) return Promise.reject(new Error(`[routes] loadRemoteModule missing — initFederation didn't run`));
  return fn({ remoteName, exposedModule }).then(mod => {
    const cls = mod[exportName];
    if (typeof cls !== 'function') {
      throw new Error(`[routes] ${remoteName}/${exposedModule}: export "${exportName}" not a class`);
    }
    return cls as Type<T>;
  });
}

export const routes: Routes = [
  { path: '', component: HomeComponent, canActivate: [authGuard] },
  { path: 'dashboard', component: PlayerDashboardComponent, canActivate: [authGuard] },
  { path: 'friends', component: FriendsComponent, canActivate: [authGuard] },
  { path: 'sessions/log', component: SessionLogPageComponent, canActivate: [authGuard] },
  { path: 'login', loadComponent: () => loadRemote('login', './LoginComponent', 'LoginRemoteComponent') },
  { path: 'about', component: AboutComponent },
  { path: 'banned', component: BannedPageComponent },
  { path: 'admin', loadComponent: () => loadRemote('admin', './AdminShell', 'AdminRemoteComponent'), canActivate: [authGuard] },
  { path: 'admin/games', loadComponent: () => loadRemote('admin', './UploadShell', 'UploadRemoteComponent'), canActivate: [authGuard] },
  // Per-session live observation including per-player view SSE — admin
  // SessionsShell ships from the same admin remote bundle.
  { path: 'admin/sessions', loadComponent: () => loadRemote('admin', './SessionsShell', 'SessionsRemoteComponent'), canActivate: [authGuard] },
  // Admin session-log tree: every open multiplayer session grouped by
  // game, searchable. Lives in the host shell (not federated) since it
  // shares the GameLibraryService + auth interceptor with the player
  // session-log page.
  { path: 'admin/session-log', component: AdminSessionLogPageComponent, canActivate: [authGuard] },
  { path: 'admin/docs', loadComponent: () => loadRemote('api-docs', './DocsShell', 'DocsShellComponent'), canActivate: [authGuard] },
  // Per-game moderator console — ROLE_MOD or ROLE_ADMIN. The BFF
  // controllers @PreAuthorize so the route mounts for any authenticated
  // user; non-mod / non-admin callers see a 403 banner inside the shell.
  { path: 'mod', loadComponent: () => loadRemote('mod', './ModShell', 'ModRemoteComponent'), canActivate: [authGuard] },
  // Per-game publisher console — ROLE_PUBLISHER or ROLE_ADMIN. Same
  // BFF-side enforcement pattern as /mod.
  { path: 'publisher', loadComponent: () => loadRemote('publisher', './PublisherShell', 'PublisherRemoteComponent'), canActivate: [authGuard] },
  // Mudlet-shaped RPG maker — federated remote that drives the
  // rpg-builder side service. ROLE_PUBLISHER or ROLE_ADMIN; the
  // BFF rejects unauthorised callers behind /api/rpg/*.
  { path: 'rpg-maker', loadComponent: () => loadRemote('games-rpg-maker', './RpgMakerShell', 'RpgMakerRemoteComponent'), canActivate: [authGuard] },
  // godot-2d-visual-novel-shaped VN maker — federated remote that
  // drives the vn-builder side service.
  { path: 'vn-maker',  loadComponent: () => loadRemote('games-vn-maker',  './VnMakerShell',  'VnMakerRemoteComponent'),  canActivate: [authGuard] },
  { path: '**', redirectTo: '' },
];
