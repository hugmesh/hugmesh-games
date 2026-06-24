import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router, NavigationError, NavigationStart } from '@angular/router';
import { Subject } from 'rxjs';

import { AppComponent } from './app.component';

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
