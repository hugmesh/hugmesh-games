import { inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';
import { AuthService } from './services/auth.service';

/**
 * Auth guard — first checks the JWT, then asks the BFF whether the
 * caller is currently banned. Banned users get redirected to /banned
 * regardless of which protected route they tried to reach.
 *
 * <p>The HTTP probe is fail-open: a transient network error defaults
 * to allowing entry. The on-tab listener
 * ({@code AdminActionsListenerService}) catches a ban that lands
 * while the user is already navigating and redirects then; this
 * guard just blocks fresh entry on a stale ban.
 */
export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const http = inject(HttpClient);

  if (!auth.isAuthenticated()) {
    router.navigate(['/login']);
    return false;
  }

  return http.get<{ banned: boolean }>('/api/me/ban-status').pipe(
    map(s => {
      if (s?.banned) {
        router.navigate(['/banned']);
        return false;
      }
      return true;
    }),
    catchError(() => of(true)),
  );
};
