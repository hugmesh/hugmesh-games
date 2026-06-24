import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Router } from '@angular/router';
import { provideRouter } from '@angular/router';

import { authGuard } from './auth.guard';
import { AuthService } from './services/auth.service';

// Specs for the authGuard. The guard is a single ~10-line function but
// every guarded route in the app sits behind it (/, /dashboard, /admin,
// /admin/games), so the two branches — authed pass-through and the
// /login redirect — both deserve direct coverage.
import { HttpTestingController } from '@angular/common/http/testing';
import { Observable, isObservable } from 'rxjs';

describe('authGuard', () => {
  let authStub: { isAuthenticated: jasmine.Spy };
  let router: Router;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    authStub = { isAuthenticated: jasmine.createSpy('isAuthenticated') };
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: authStub },
        // authGuard now also injects HttpClient (for the
        // /api/me/ban-status probe) so the test must provide both
        // the client and the testing controller.
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    router = TestBed.inject(Router);
    httpMock = TestBed.inject(HttpTestingController);
  });

  function run() {
    return TestBed.runInInjectionContext(() =>
      authGuard(null as never, null as never));
  }

  it('returns true when the user is authenticated and not banned', async () => {
    authStub.isAuthenticated.and.returnValue(true);
    const navSpy = spyOn(router, 'navigate');

    const result = run();
    expect(isObservable(result)).toBeTrue();
    const promise = new Promise<boolean>(resolve => {
      (result as Observable<boolean>).subscribe(v => resolve(v));
    });
    // The guard hits /api/me/ban-status; flush a not-banned reply.
    httpMock.expectOne('/api/me/ban-status').flush({ banned: false });
    expect(await promise).toBeTrue();
    expect(navSpy).not.toHaveBeenCalled();
  });

  it('redirects to /login and returns false when unauthenticated', () => {
    authStub.isAuthenticated.and.returnValue(false);
    const navSpy = spyOn(router, 'navigate');

    expect(run()).toBeFalse();
    expect(navSpy).toHaveBeenCalledWith(['/login']);
    // No HTTP probe in the unauthenticated path.
    httpMock.verify();
  });
});
