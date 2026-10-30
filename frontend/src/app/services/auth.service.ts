import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { map, Observable, tap } from 'rxjs';

interface TokenResponse {
  access_token: string;
  id_token?: string;
  token_type: string;
  expires_in: number;
}

interface StoredAuth {
  accessToken: string;
  idToken: string;
  expiry: number;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  // localStorage (not sessionStorage) so right-click → open-in-new-tab
  // on a menu link (`/admin`, `/admin/sessions`, `/rpg-maker`, …) keeps
  // the user signed in. sessionStorage is per-tab; a fresh tab would
  // see empty auth and the AuthGuard would bounce to /login on every
  // middle-click. Cross-tab logout is signalled via the `storage` DOM
  // event below.
  private readonly STORAGE_KEY = 'tw_auth';

  login(username: string, password: string): Observable<void> {
    return this.http.post<TokenResponse>('/api/auth/token', { username, password }).pipe(
      tap(resp => {
        const stored: StoredAuth = {
          accessToken: resp.access_token,
          idToken: resp.id_token ?? '',
          expiry: Date.now() + resp.expires_in * 1000,
        };
        localStorage.setItem(this.STORAGE_KEY, JSON.stringify(stored));
      }),
      map(() => void 0),
    );
  }

  logout(): void {
    localStorage.removeItem(this.STORAGE_KEY);
  }

  isAuthenticated(): boolean {
    const stored = this._stored();
    return !!stored && Date.now() < stored.expiry;
  }

  getAccessToken(): string | null {
    return this._stored()?.accessToken ?? null;
  }

  getClaims(): Record<string, unknown> | null {
    const token = this.getAccessToken();
    if (!token) return null;
    try {
      return JSON.parse(atob(token.split('.')[1]));
    } catch {
      return null;
    }
  }

  /**
   * True for the same roles the BFF treats as a games admin on a games-realm
   * token (GamesAdminPolicy): the realm's own `admin`, the derived `games_admin`,
   * and `mesh-games-admin`, the role the GM sync keeps. The legacy `isAdmin` claim
   * is not one of them: the BFF refuses it, so showing admin navigation for it
   * would only lead to 403s. UI decisions only; the BFF is the authority.
   */
  isAdmin(): boolean {
    return ['admin', 'games_admin', 'mesh-games-admin'].some(role => this.hasRole(role));
  }

  /** True when Casdoor's `mod` role is present in the JWT roles claim. */
  isMod(): boolean { return this.hasRole('mod'); }

  /** True when Casdoor's `publisher` role is present in the JWT roles claim. */
  isPublisher(): boolean { return this.hasRole('publisher'); }

  /**
   * Casdoor's default JWT carries `roles` as objects (`{name, owner, ...}`); a
   * custom token template can flatten them to strings. Accept both, and read names
   * the way the BFF does (Roles.canonical): one leading `ROLE_` stripped, lowercase.
   */
  private hasRole(name: string): boolean {
    const roles = this.getClaims()?.['roles'];
    if (!Array.isArray(roles)) return false;
    return roles.some(r => {
      const raw = typeof r === 'string' ? r : (r as { name?: unknown } | null)?.name;
      return typeof raw === 'string'
        && raw.trim().replace(/^role_/i, '').toLowerCase() === name;
    });
  }

  displayName(): string | null {
    const c = this.getClaims();
    if (!c) return null;
    return (c['displayName'] as string) ?? (c['name'] as string) ?? (c['sub'] as string) ?? null;
  }

  private _stored(): StoredAuth | null {
    try {
      const raw = localStorage.getItem(this.STORAGE_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch {
      return null;
    }
  }
}
