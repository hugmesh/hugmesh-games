import { Component, EventEmitter, Input, Output } from '@angular/core';
import { ApiFailure } from './games-api.service';

/**
 * The shared "the games service is not reachable" panel. Rendered for any
 * network / 5xx failure on a games-BFF call. The fe-gateway route for
 * /games/api/** only resolves when the `games` profile is up, so this is the
 * honest state when the BFF is absent on a deployment.
 */
@Component({
  selector: 'hug-games-unreachable',
  standalone: true,
  template: `
    <div class="hg-notice hg-notice--error" role="alert" data-testid="games-unreachable">
      <p>The games service is not reachable on this deployment.</p>
      <button type="button" class="hg-btn" (click)="retry.emit()" data-testid="games-retry">
        Retry
      </button>
    </div>
  `,
})
export class GamesUnreachableComponent {
  @Output() readonly retry = new EventEmitter<void>();
}

/**
 * The member-side forbidden panel (M1/M2): the gateway or BFF refused the
 * caller with 401/403. Shows the X-Route-Denied / X-Resource-Denied value when
 * the gateway sent one and links back to the shell's home.
 */
@Component({
  selector: 'hug-games-forbidden',
  standalone: true,
  template: `
    <div class="hg-notice hg-notice--warning" role="alert" data-testid="games-forbidden">
      <p>Your account does not hold games access at the gateway.</p>
      @if (failure?.denied; as d) {
        <p class="hg-muted">Denied: {{ d }}</p>
      }
      <!-- A plain anchor, not a routerLink: /home is the HOST shell's route, which
           this remote must never address through its own (mount-relative) router. -->
      <a href="/home" data-testid="games-back-home">Back to home</a>
    </div>
  `,
})
export class GamesForbiddenComponent {
  @Input() failure: ApiFailure | null = null;
}

/**
 * The admin-side forbidden panel (A0): every 401/403 from /games/api/admin/**
 * or /games/api/sessions/** lands here, with the role rule spelled out so an
 * admin knows exactly which derived role the gateway wanted.
 */
@Component({
  selector: 'hug-games-admin-forbidden',
  standalone: true,
  template: `
    <div class="hg-notice hg-notice--warning" role="alert" data-testid="games-admin-forbidden">
      <p>
        The gateway did not accept your session for the games admin API
        (ROLE_GAMES_ADMIN = games AND (admin OR mod)); X-Route-Denied:
        {{ failure?.denied || 'not sent' }}.
      </p>
    </div>
  `,
})
export class GamesAdminForbiddenComponent {
  @Input() failure: ApiFailure | null = null;
}
