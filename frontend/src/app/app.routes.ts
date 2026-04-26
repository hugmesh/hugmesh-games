import { Routes } from '@angular/router';
import { Type } from '@angular/core';
import { HomeComponent } from './home/home.component';
import { AboutComponent } from './about/about.component';
import { PlayerDashboardComponent } from './player-dashboard/player-dashboard.component';
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
  const fn = (globalThis as { __SNAPDRAGON_LOAD_REMOTE_MODULE__?: LoadRemoteModuleFn }).__SNAPDRAGON_LOAD_REMOTE_MODULE__;
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
  { path: 'login', loadComponent: () => loadRemote('login', './LoginComponent', 'LoginRemoteComponent') },
  { path: 'about', component: AboutComponent },
  { path: 'admin', loadComponent: () => loadRemote('admin', './AdminShell', 'AdminRemoteComponent'), canActivate: [authGuard] },
  { path: 'admin/games', loadComponent: () => loadRemote('admin', './UploadShell', 'UploadRemoteComponent'), canActivate: [authGuard] },
  { path: 'admin/docs', loadComponent: () => loadRemote('api-docs', './DocsShell', 'DocsShellComponent'), canActivate: [authGuard] },
  { path: '**', redirectTo: '' },
];
