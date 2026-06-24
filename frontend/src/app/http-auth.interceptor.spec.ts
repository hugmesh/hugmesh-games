import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { authInterceptor } from './http-auth.interceptor';
import { AuthService } from './services/auth.service';

// Specs for the authInterceptor. Every /api/* request goes through
// this — both the host's calls and the calls made by the federated
// remotes (HttpClient is shared via federation's shareAll). Pinning
// the three branches keeps the federated remotes' Bearer-JWT contract
// honest:
//   - token + /api/* URL → header attached
//   - token + non-/api URL → header NOT attached (avoids leaking the
//     JWT to third-party hosts the federation manifest might fetch)
//   - no token → request passes through unchanged
describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let auth: { getAccessToken: jasmine.Spy };

  beforeEach(() => {
    auth = { getAccessToken: jasmine.createSpy('getAccessToken') };
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withXhr(), withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('attaches Authorization: Bearer <token> when token + /api/ URL', () => {
    auth.getAccessToken.and.returnValue('jwt-abc');
    http.get('/api/games').subscribe();

    const req = httpMock.expectOne('/api/games');
    expect(req.request.headers.get('Authorization')).toBe('Bearer jwt-abc');
    req.flush({});
  });

  it('does NOT attach the token to non-/api URLs', () => {
    auth.getAccessToken.and.returnValue('jwt-abc');
    // Pretend the remote loader is fetching a federation manifest from
    // an asset path — the interceptor must not leak the JWT there.
    http.get('/_remotes/login/remoteEntry.json').subscribe();
    const req = httpMock.expectOne('/_remotes/login/remoteEntry.json');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({});
  });

  it('passes /api/ requests through unchanged when no token is stored', () => {
    auth.getAccessToken.and.returnValue(null);
    http.get('/api/games').subscribe();
    const req = httpMock.expectOne('/api/games');
    expect(req.request.headers.has('Authorization')).toBeFalse();
    req.flush({});
  });
});
