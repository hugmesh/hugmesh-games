import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router, NavigationError, NavigationStart } from '@angular/router';
import { Subject } from 'rxjs';

import { AppComponent } from './app.component';
import { SessionLeaveOnUnloadService } from './services/session-leave-on-unload.service';
import { GamepadService } from './services/gamepad.service';
import { ApiBridgeService } from './services/api-bridge.service';
import { AdminActionsListenerService } from './services/admin-actions-listener.service';

// Specs for the federation NavigationError banner. AppComponent
// subscribes to Router.events so that a loadComponent() rejection on
// a federated route (manifest fetch failed, remote 404'd) surfaces a
// retry banner instead of silently leaving the user on the previous
// URL. The label predicate is the source of truth for which routes
// the banner cares about, so it gets direct coverage too.
describe('AppComponent', () => {
  let component: AppComponent;
  let routerEvents$: Subject<unknown>;

  beforeEach(() => {
    routerEvents$ = new Subject();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    component = TestBed.createComponent(AppComponent).componentInstance;
    // Replace Router.events with a Subject so we can deterministically
    // fire NavigationError / NavigationStart in each spec.
    const router = TestBed.inject(Router);
    Object.defineProperty(router, 'events', { value: routerEvents$.asObservable() });
    component.ngOnInit();
  });

  describe('federatedRouteLabel', () => {
    it('returns the right label for /login', () => {
      expect(component.federatedRouteLabel('/login')).toBe('Login');
      expect(component.federatedRouteLabel('/login?next=/admin')).toBe('Login');
    });

    it('returns the right label for /admin and /admin/games', () => {
      // The /admin/games entry must come BEFORE /admin in the prefix
      // table (longest-match) — without that, /admin/games would
      // claim "Admin console" and the user'd see the wrong banner.
      expect(component.federatedRouteLabel('/admin')).toBe('Admin console');
      expect(component.federatedRouteLabel('/admin/games')).toBe('Game uploads');
      expect(component.federatedRouteLabel('/admin/games/some-slug')).toBe('Game uploads');
    });

    it('returns null for non-federated routes', () => {
      expect(component.federatedRouteLabel('/')).toBeNull();
      expect(component.federatedRouteLabel('/dashboard')).toBeNull();
      expect(component.federatedRouteLabel('/about')).toBeNull();
    });
  });

  describe('NavigationError handling', () => {
    it('sets federationLoadError when the failing URL is a known federated route', () => {
      routerEvents$.next(new NavigationError(1, '/login', new Error('manifest fetch failed')));
      expect(component.federationLoadError()).toEqual({ url: '/login', label: 'Login' });
    });

    it('ignores NavigationError on non-federated routes', () => {
      routerEvents$.next(new NavigationError(1, '/dashboard', new Error('boom')));
      expect(component.federationLoadError()).toBeNull();
    });

    it('clears the error on the next NavigationStart so retries auto-recover', () => {
      routerEvents$.next(new NavigationError(1, '/admin', new Error('remote down')));
      expect(component.federationLoadError()?.label).toBe('Admin console');

      routerEvents$.next(new NavigationStart(2, '/admin'));
      expect(component.federationLoadError()).toBeNull();
    });
  });

  describe('retryFederationLoad', () => {
    it('navigates to the failing URL with replaceUrl: true and clears the banner', () => {
      const router = TestBed.inject(Router);
      const navSpy = spyOn(router, 'navigateByUrl');

      routerEvents$.next(new NavigationError(1, '/login', new Error('boom')));
      expect(component.federationLoadError()).not.toBeNull();

      component.retryFederationLoad();
      expect(component.federationLoadError()).toBeNull();
      expect(navSpy).toHaveBeenCalledWith('/login', { replaceUrl: true });
    });

    it('is a no-op when no error is set (defensive — the banner is hidden then)', () => {
      const router = TestBed.inject(Router);
      const navSpy = spyOn(router, 'navigateByUrl');
      component.retryFederationLoad();
      expect(navSpy).not.toHaveBeenCalled();
    });
  });
});

/**
 * Bootstrap activation of the browser-tier services.
 *
 * These are pure side-effect `start()` calls with no return value and no
 * component state, which is exactly why they were unasserted — and why
 * deleting `this.leaveOnUnload.start()` kept the whole suite green while
 * making the entire closed-tab fix inert: with no `pagehide` listener
 * attached, `SessionLeaveOnUnloadService.track()` still records a session id
 * that nothing will ever act on, and every closed tab ghosts its seat again.
 * The activation, not just the service's own behaviour, has to be pinned.
 *
 * A fresh component is built here rather than reusing the outer suite's,
 * because the spy has to exist BEFORE `ngOnInit` runs.
 */
describe('AppComponent bootstrap activation', () => {
  function bootWithSpies() {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    const spies = {
      leaveOnUnload: spyOn(TestBed.inject(SessionLeaveOnUnloadService), 'start'),
      gamepads:      spyOn(TestBed.inject(GamepadService), 'start'),
      apiBridge:     spyOn(TestBed.inject(ApiBridgeService), 'start'),
      adminActions:  spyOn(TestBed.inject(AdminActionsListenerService), 'start'),
    };
    const component = TestBed.createComponent(AppComponent).componentInstance;
    component.ngOnInit();
    return spies;
  }

  it('attaches the unload seat-release listener at bootstrap', () => {
    expect(bootWithSpies().leaveOnUnload).toHaveBeenCalled();
  });

  it('starts the other host-shell services it owns', () => {
    // Same failure mode as the one above — assert the whole set so the next
    // service added here inherits the pin instead of repeating the mistake.
    const spies = bootWithSpies();
    expect(spies.gamepads).toHaveBeenCalled();
    expect(spies.apiBridge).toHaveBeenCalled();
    expect(spies.adminActions).toHaveBeenCalled();
  });
});

// Rendered, not just the getters: this navigation stayed hidden for real admins
// because Casdoor mints `roles` as {name} objects and the checks only read strings.
describe('AppComponent navigation, with the token Casdoor actually mints', () => {
  function navLinks(roles: string[], extraClaims: Record<string, unknown> = {}): string[] {
    const claims = {
      sub: 'someone', name: 'someone',
      roles: roles.map(name => ({ owner: 'threewizards', name, displayName: name, isEnabled: true })),
      ...extraClaims,
    };
    localStorage.setItem('tw_auth', JSON.stringify({
      accessToken: `header.${btoa(JSON.stringify(claims))}.sig`,
      idToken: '',
      expiry: Date.now() + 60_000,
    }));
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    return Array.from<HTMLAnchorElement>(fixture.nativeElement.querySelectorAll('a'))
      .map(a => a.getAttribute('href') ?? '');
  }

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
  });

  afterEach(() => localStorage.clear());

  it('an admin sees the admin, mod and publisher links', () => {
    const links = navLinks(['admin', 'player']);
    expect(links).toContain('/admin');
    expect(links).toContain('/mod');
    expect(links).toContain('/publisher');
  });

  it('a mod sees the mod link and not the admin link', () => {
    const links = navLinks(['mod']);
    expect(links).toContain('/mod');
    expect(links).not.toContain('/admin');
    expect(links).not.toContain('/publisher');
  });

  it('a publisher sees the publisher link and not the admin link', () => {
    const links = navLinks(['publisher']);
    expect(links).toContain('/publisher');
    expect(links).not.toContain('/admin');
    expect(links).not.toContain('/mod');
  });

  it('a player sees none of them', () => {
    const links = navLinks(['player', 'play-three-wizards']);
    expect(links).not.toContain('/admin');
    expect(links).not.toContain('/mod');
    expect(links).not.toContain('/publisher');
  });

  it('the legacy isAdmin claim alone does not show the admin link', () => {
    expect(navLinks(['player'], { isAdmin: true })).not.toContain('/admin');
  });
});
