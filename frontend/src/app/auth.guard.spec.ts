import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { provideRouter } from '@angular/router';

import { authGuard } from './auth.guard';
import { AuthService } from './services/auth.service';

// Specs for the authGuard. The guard is a single ~10-line function but
// every guarded route in the app sits behind it (/, /dashboard, /admin,
// /admin/games), so the two branches — authed pass-through and the
// /login redirect — both deserve direct coverage.
describe('authGuard', () => {
  let authStub: { isAuthenticated: jasmine.Spy };
  let router: Router;

  beforeEach(() => {
    authStub = { isAuthenticated: jasmine.createSpy('isAuthenticated') };
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: authStub },
        provideRouter([]),
      ],
    });
    router = TestBed.inject(Router);
  });

  function run(): boolean | undefined {
    // CanActivateFn must run inside an injection context. Cast the
    // result because the union with UrlTree/Observable trips up the
    // boolean assertion below.
    const result = TestBed.runInInjectionContext(() =>
      authGuard(null as never, null as never),
    );
    return result as boolean;
  }

  it('returns true when the user is authenticated', () => {
    authStub.isAuthenticated.and.returnValue(true);
    const navSpy = spyOn(router, 'navigate');

    expect(run()).toBeTrue();
    expect(navSpy).not.toHaveBeenCalled();
  });

  it('redirects to /login and returns false when unauthenticated', () => {
    authStub.isAuthenticated.and.returnValue(false);
    const navSpy = spyOn(router, 'navigate');

    expect(run()).toBeFalse();
    expect(navSpy).toHaveBeenCalledWith(['/login']);
  });
});
