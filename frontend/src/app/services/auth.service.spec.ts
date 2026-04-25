import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';

import { AuthService } from './auth.service';

describe('AuthService', () => {
  let svc: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient()],
    });
    sessionStorage.clear();
    svc = TestBed.inject(AuthService);
  });

  it('reports unauthenticated when nothing is stored', () => {
    expect(svc.isAuthenticated()).toBeFalse();
    expect(svc.getAccessToken()).toBeNull();
  });

  it('treats an expired stored token as unauthenticated', () => {
    sessionStorage.setItem('tw_auth', JSON.stringify({
      accessToken: 'tok', idToken: '', expiry: Date.now() - 1_000,
    }));
    expect(svc.isAuthenticated()).toBeFalse();
  });

  it('treats a fresh stored token as authenticated', () => {
    sessionStorage.setItem('tw_auth', JSON.stringify({
      accessToken: 'tok', idToken: '', expiry: Date.now() + 60_000,
    }));
    expect(svc.isAuthenticated()).toBeTrue();
    expect(svc.getAccessToken()).toBe('tok');
  });

  it('logout clears stored auth', () => {
    sessionStorage.setItem('tw_auth', JSON.stringify({
      accessToken: 'tok', idToken: '', expiry: Date.now() + 60_000,
    }));
    svc.logout();
    expect(svc.isAuthenticated()).toBeFalse();
  });

  it('getClaims returns null for malformed tokens', () => {
    sessionStorage.setItem('tw_auth', JSON.stringify({
      accessToken: 'not-a-jwt', idToken: '', expiry: Date.now() + 60_000,
    }));
    expect(svc.getClaims()).toBeNull();
  });

  it('isAdmin reads the isAdmin claim from a JWT', () => {
    // Hand-rolled JWT — header.payload.signature, payload base64url-encoded.
    const payload = btoa(JSON.stringify({ isAdmin: true, sub: 'admin' }));
    const fakeJwt = `header.${payload}.sig`;
    sessionStorage.setItem('tw_auth', JSON.stringify({
      accessToken: fakeJwt, idToken: '', expiry: Date.now() + 60_000,
    }));
    expect(svc.isAdmin()).toBeTrue();
  });
});
