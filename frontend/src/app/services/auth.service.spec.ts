import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';

import { AuthService } from './auth.service';

describe('AuthService', () => {
  let svc: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr())],
    });
    localStorage.clear();
    svc = TestBed.inject(AuthService);
  });

  it('reports unauthenticated when nothing is stored', () => {
    expect(svc.isAuthenticated()).toBeFalse();
    expect(svc.getAccessToken()).toBeNull();
  });

  it('treats an expired stored token as unauthenticated', () => {
    localStorage.setItem('tw_auth', JSON.stringify({
      accessToken: 'tok', idToken: '', expiry: Date.now() - 1_000,
    }));
    expect(svc.isAuthenticated()).toBeFalse();
  });

  it('treats a fresh stored token as authenticated', () => {
    localStorage.setItem('tw_auth', JSON.stringify({
      accessToken: 'tok', idToken: '', expiry: Date.now() + 60_000,
    }));
    expect(svc.isAuthenticated()).toBeTrue();
    expect(svc.getAccessToken()).toBe('tok');
  });

  it('logout clears stored auth', () => {
    localStorage.setItem('tw_auth', JSON.stringify({
      accessToken: 'tok', idToken: '', expiry: Date.now() + 60_000,
    }));
    svc.logout();
    expect(svc.isAuthenticated()).toBeFalse();
  });

  it('getClaims returns null for malformed tokens', () => {
    localStorage.setItem('tw_auth', JSON.stringify({
      accessToken: 'not-a-jwt', idToken: '', expiry: Date.now() + 60_000,
    }));
    expect(svc.getClaims()).toBeNull();
  });

  function signInWith(claims: Record<string, unknown>): void {
    // Hand-rolled JWT — header.payload.signature, payload base64url-encoded.
    const fakeJwt = `header.${btoa(JSON.stringify({ sub: 'someone', ...claims }))}.sig`;
    localStorage.setItem('tw_auth', JSON.stringify({
      accessToken: fakeJwt, idToken: '', expiry: Date.now() + 60_000,
    }));
  }

  /** The shape casdoor-games actually mints: tokenFormat JWT, roles as objects. */
  const casdoorRole = (name: string) => ({
    owner: 'threewizards', name, createdTime: '', displayName: name, isEnabled: true,
  });

  describe('roles as Casdoor mints them ({name} objects)', () => {
    it('a realm admin is an admin', () => {
      signInWith({ roles: [casdoorRole('admin'), casdoorRole('player')] });
      expect(svc.isAdmin()).toBeTrue();
      expect(svc.isMod()).toBeFalse();
      expect(svc.isPublisher()).toBeFalse();
    });

    it('a mod is a mod and not an admin', () => {
      signInWith({ roles: [casdoorRole('mod')] });
      expect(svc.isMod()).toBeTrue();
      expect(svc.isAdmin()).toBeFalse();
    });

    it('a publisher is a publisher and not an admin', () => {
      signInWith({ roles: [casdoorRole('publisher')] });
      expect(svc.isPublisher()).toBeTrue();
      expect(svc.isAdmin()).toBeFalse();
    });

    it('a player is none of them', () => {
      signInWith({ roles: [casdoorRole('player'), casdoorRole('play-three-wizards')] });
      expect(svc.isAdmin()).toBeFalse();
      expect(svc.isMod()).toBeFalse();
      expect(svc.isPublisher()).toBeFalse();
    });

    it('the games-admin roles the BFF admits are admins', () => {
      for (const role of ['games_admin', 'ROLE_GAMES_ADMIN', 'mesh-games-admin']) {
        signInWith({ roles: [casdoorRole(role)] });
        expect(svc.isAdmin()).withContext(role).toBeTrue();
      }
    });

    it('ignores entries with no usable name', () => {
      signInWith({ roles: [null, {}, { name: 42 }, 7] });
      expect(svc.isAdmin()).toBeFalse();
    });
  });

  describe('roles as plain strings', () => {
    it('reads them case-insensitively, with one ROLE_ prefix stripped', () => {
      signInWith({ roles: ['ROLE_ADMIN'] });
      expect(svc.isAdmin()).toBeTrue();
      signInWith({ roles: ['Mod'] });
      expect(svc.isMod()).toBeTrue();
      signInWith({ roles: ['publisher'] });
      expect(svc.isPublisher()).toBeTrue();
    });

    it('does not strip a second ROLE_ prefix', () => {
      signInWith({ roles: ['ROLE_ROLE_ADMIN'] });
      expect(svc.isAdmin()).toBeFalse();
    });
  });

  it('the legacy isAdmin claim does not make an admin, because the BFF refuses it', () => {
    signInWith({ isAdmin: true, roles: [casdoorRole('player')] });
    expect(svc.isAdmin()).toBeFalse();
    signInWith({ isAdmin: true });
    expect(svc.isAdmin()).toBeFalse();
  });

  it('a token with no roles claim has no roles', () => {
    signInWith({});
    expect(svc.isAdmin()).toBeFalse();
    expect(svc.isMod()).toBeFalse();
    expect(svc.isPublisher()).toBeFalse();
  });
});
