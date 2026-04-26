import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterOutlet, RouterLink, RouterLinkActive,
         NavigationError, NavigationStart } from '@angular/router';
import { AuthService } from './services/auth.service';

// Routes whose component is fetched at runtime via loadRemoteModule.
// A NavigationError on one of these is almost always a federation
// runtime problem (manifest fetch failed, remote 404'd on
// remoteEntry.json, exposed module renamed). Mapping them by URL
// prefix lets the error banner surface a route-specific hint
// without coupling AppComponent to app.routes.ts's loadComponent
// callbacks. Updating this when a new federated route lands keeps
// the user-visible message accurate.
const FEDERATED_ROUTE_LABELS: ReadonlyArray<{ prefix: string; label: string }> = [
  { prefix: '/login',        label: 'Login' },
  { prefix: '/admin/games',  label: 'Game uploads' },
  { prefix: '/admin/docs',   label: 'API docs' },
  { prefix: '/admin',        label: 'Admin console' },
];

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  /**
   * Set when the router fails to activate a federated route — the
   * loadComponent promise rejected, usually because the federation
   * runtime couldn't reach the remote (manifest fetch failed, remote
   * service down, exposed module renamed). Cleared on the next
   * NavigationStart so a refresh / retry recovers cleanly.
   */
  readonly federationLoadError = signal<{ url: string; label: string } | null>(null);

  get isLoggedIn(): boolean { return this.auth.isAuthenticated(); }
  get isAdmin(): boolean    { return this.auth.isAdmin(); }
  get displayName(): string | null { return this.auth.displayName(); }

  ngOnInit(): void {
    this.router.events.subscribe(ev => {
      if (ev instanceof NavigationError) {
        const label = this.federatedRouteLabel(ev.url);
        if (label) {
          // eslint-disable-next-line no-console
          console.error('[router] federated route load failed', {
            url: ev.url, label, error: ev.error,
          });
          this.federationLoadError.set({ url: ev.url, label });
        }
      } else if (ev instanceof NavigationStart) {
        // Clear on every nav attempt so the banner doesn't outlive
        // its fix — refresh / retry / a successful nav all dismiss it.
        this.federationLoadError.set(null);
      }
    });
  }

  /** Maps an attempted URL to a user-facing label, or null when the
   *  URL doesn't match a known federated route. */
  federatedRouteLabel(url: string): string | null {
    return FEDERATED_ROUTE_LABELS.find(r => url.startsWith(r.prefix))?.label ?? null;
  }

  retryFederationLoad(): void {
    const err = this.federationLoadError();
    if (!err) return;
    this.federationLoadError.set(null);
    // navigateByUrl re-runs the loadComponent — federation has a
    // chance to recover (manifest re-fetched, remote came back online).
    this.router.navigateByUrl(err.url, { replaceUrl: true });
  }

  logout(): void {
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}
