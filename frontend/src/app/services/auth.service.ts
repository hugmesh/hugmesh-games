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
  private readonly STORAGE_KEY = 'tw_auth';

  login(username: string, password: string): Observable<void> {
    return this.http.post<TokenResponse>('/api/auth/token', { username, password }).pipe(
      tap(resp => {
        const stored: StoredAuth = {
          accessToken: resp.access_token,
          idToken: resp.id_token ?? '',
          expiry: Date.now() + resp.expires_in * 1000,
        };
        sessionStorage.setItem(this.STORAGE_KEY, JSON.stringify(stored));
      }),
      map(() => void 0),
    );
  }

  logout(): void {
    sessionStorage.removeItem(this.STORAGE_KEY);
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

  isAdmin(): boolean {
    return this.getClaims()?.['isAdmin'] === true;
  }

  displayName(): string | null {
    const c = this.getClaims();
    if (!c) return null;
    return (c['displayName'] as string) ?? (c['name'] as string) ?? (c['sub'] as string) ?? null;
  }

  private _stored(): StoredAuth | null {
    try {
      const raw = sessionStorage.getItem(this.STORAGE_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch {
      return null;
    }
  }
}
