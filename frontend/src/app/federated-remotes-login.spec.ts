import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';

// Cross-project import — see federated-remotes-admin.spec.ts for the
// reason the spec lives in src/ instead of projects/login/.
import { LoginRemoteComponent } from '../../projects/login/src/app/login-remote.component';

// Specs for the federated LoginRemoteComponent. The remote owns the
// /api/auth/token call and writes the StoredAuth shape to
// localStorage with key 'tw_auth' — these are the cross-bundle
// contracts the host's interceptor + auth guard depend on, so each
// gets its own assertion.
describe('LoginRemoteComponent', () => {
  let component: LoginRemoteComponent;
  let httpMock: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    localStorage.removeItem('tw_auth');

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    component = TestBed.createComponent(LoginRemoteComponent).componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => httpMock.verify());

  it('does nothing when the form is invalid', () => {
    // form starts empty → invalid (Validators.required on both fields).
    component.submit();
    httpMock.expectNone('/api/auth/token');
    expect(component.loading()).toBeFalse();
  });

  it('POSTs /api/auth/token with raw form values on submit', () => {
    component.form.setValue({ username: 'magus', password: 'secret' });
    component.submit();

    const req = httpMock.expectOne('/api/auth/token');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ username: 'magus', password: 'secret' });

    // Resolve so afterEach's verify passes.
    req.flush({ access_token: 'a', id_token: 'b', token_type: 'Bearer', expires_in: 3600 });
  });

  it('writes StoredAuth to localStorage and navigates to / on success', () => {
    const navSpy = spyOn(router, 'navigate');
    component.form.setValue({ username: 'magus', password: 'secret' });
    component.submit();

    httpMock.expectOne('/api/auth/token').flush({
      access_token: 'jwt-abc', id_token: 'id-xyz',
      token_type: 'Bearer', expires_in: 600,
    });

    const stored = JSON.parse(localStorage.getItem('tw_auth') ?? '{}');
    expect(stored.accessToken).toBe('jwt-abc');
    expect(stored.idToken).toBe('id-xyz');
    expect(stored.expiry).toBeGreaterThan(Date.now());
    expect(navSpy).toHaveBeenCalledWith(['/']);
  });

  it('surfaces the BFF error message on failure and clears loading', () => {
    component.form.setValue({ username: 'magus', password: 'wrong' });
    component.submit();
    expect(component.loading()).toBeTrue();

    httpMock.expectOne('/api/auth/token').flush(
      { msg: 'bad credentials' },
      { status: 401, statusText: 'Unauthorized' },
    );

    expect(component.errorMsg()).toBe('bad credentials');
    expect(component.loading()).toBeFalse();
    expect(localStorage.getItem('tw_auth')).toBeNull();
  });

  it('falls back to a generic message when the error has no body', () => {
    component.form.setValue({ username: 'magus', password: 'wrong' });
    component.submit();

    httpMock.expectOne('/api/auth/token').flush(null, { status: 500, statusText: '' });

    expect(component.errorMsg()).toBe('Invalid credentials');
  });
});
