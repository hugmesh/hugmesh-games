import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterOutlet, RouterLink, RouterLinkActive,
         NavigationError, NavigationStart } from '@angular/router';
import { AuthService } from './services/auth.service';
import { GamepadService } from './services/gamepad.service';
import { WasmConsoleForwarderService } from './services/wasm-console-forwarder.service';
import { AdminActionsListenerService } from './services/admin-actions-listener.service';
import { ApiBridgeService } from './services/api-bridge.service';
import { NotificationService } from './services/notification.service';

// Routes whose component is fetched at runtime via loadRemoteModule.
// A NavigationError on one of these is almost always a federation
// runtime problem (manifest fetch failed, remote 404'd on
// remoteEntry.json, exposed module renamed). Mapping them by URL
// prefix lets the error banner surface a route-specific hint
// without coupling AppComponent to app.routes.ts's loadComponent
// callbacks. Updating this when a new federated route lands keeps
// the user-visible message accurate.
const FEDERATED_ROUTE_LABELS: ReadonlyArray<{ prefix: string; label: string }> = [
  { prefix: '/login',           label: 'Login' },
  { prefix: '/admin/games',     label: 'Game uploads' },
  { prefix: '/admin/sessions',  label: 'Live sessions' },
  { prefix: '/admin/docs',      label: 'API docs' },
  { prefix: '/admin',           label: 'Admin console' },
  { prefix: '/mod',             label: 'Moderator console' },
  { prefix: '/publisher',       label: 'Publisher console' },
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
  private readonly gamepads = inject(GamepadService);
  private readonly wasmConsoleForwarder = inject(WasmConsoleForwarderService);
  private readonly apiBridge = inject(ApiBridgeService);
  private readonly adminActions = inject(AdminActionsListenerService);
  readonly notify = inject(NotificationService);

  /**
   * Set when the router fails to activate a federated route — the
   * loadComponent promise rejected, usually because the federation
   * runtime couldn't reach the remote (manifest fetch failed, remote
   * service down, exposed module renamed). Cleared on the next
   * NavigationStart so a refresh / retry recovers cleanly.
   */
  readonly federationLoadError = signal<{ url: string; label: string } | null>(null);

  get isLoggedIn(): boolean  { return this.auth.isAuthenticated(); }
  get isAdmin(): boolean     { return this.auth.isAdmin(); }
  get isMod(): boolean       { return this.auth.isMod() || this.auth.isAdmin(); }
  get isPublisher(): boolean { return this.auth.isPublisher() || this.auth.isAdmin(); }
  get displayName(): string | null { return this.auth.displayName(); }

  ngOnInit(): void {
    // Browser-tier services that should run as long as the host shell
    // is mounted. Both are idempotent — re-calling start() is a no-op.
    //   - GamepadService polls navigator.getGamepads() every animation
    //     frame, diffs, and emits canonical input-events to the active
    //     game iframe + the BFF.
    //   - WasmConsoleForwarderService catches SD_WASM_CONSOLE
    //     postMessages from the WASM iframe, mirrors to host devtools,
    //     and forwards to /api/diagnostics/wasm-console.
    this.gamepads.start();
    this.wasmConsoleForwarder.start();
    //   - ApiBridgeService catches SD_API_REQUEST + SD_VIEW_EGRESS
    //     postMessages from any Godot iframe, performs the matching
    //     HttpClient call (so the auth interceptor + X-Session-Id
    //     header flow uniformly), and posts SD_API_RESPONSE back.
    //     This is the host-side seam for the snapdragon_bridge addon's
    //     BridgeApiClient.
    this.apiBridge.start();
    // AdminActionsListener subscribes to the per-user SSE stream and
    // closes the game iframe / redirects to /banned on a kick or ban.
    // Idempotent — safe to re-call on auth state changes.
    this.adminActions.start();

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
