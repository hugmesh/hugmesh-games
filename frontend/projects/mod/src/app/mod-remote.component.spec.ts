import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { ModRemoteComponent } from './mod-remote.component';

/**
 * Wire-format coverage for the federated moderator remote.
 * On init it fans out two requests: /api/mod/scopes and /api/sessions.
 * Stop + kick map to /api/mod/sessions/{id}/{stop|kick} with a body
 * carrying the reason / target.
 */
describe('ModRemoteComponent', () => {
  let comp: ModRemoteComponent;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
      imports: [ModRemoteComponent],
    });
    const fixture = TestBed.createComponent(ModRemoteComponent);
    comp = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => httpMock.verify());

  it('fetches scopes + sessions on init', () => {
    comp.scopes$.subscribe();
    comp.sessions$.subscribe();
    const scopes = httpMock.expectOne('/api/mod/scopes');
    expect(scopes.request.method).toBe('GET');
    scopes.flush([]);
    const sessions = httpMock.expectOne('/api/sessions');
    expect(sessions.request.method).toBe('GET');
    sessions.flush([]);
  });

  it('stop() POSTs to /api/mod/sessions/{id}/stop with reason', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    comp.stop('s-1');
    const req = httpMock.expectOne('/api/mod/sessions/s-1/stop');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.reason).toBe('mod-stop');
    req.flush(null);
    // refresh fires after success
    httpMock.expectOne('/api/mod/scopes').flush([]);
    httpMock.expectOne('/api/sessions').flush([]);
  });

  it('kick() POSTs targetCasdoorId + reason to /api/mod/sessions/{id}/kick', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    comp.kick('s-1', 'noisy');
    const req = httpMock.expectOne('/api/mod/sessions/s-1/kick');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.targetCasdoorId).toBe('noisy');
    expect(req.request.body.reason).toBe('mod-kick');
    req.flush(null);
    httpMock.expectOne('/api/mod/scopes').flush([]);
    httpMock.expectOne('/api/sessions').flush([]);
  });

  it('shows a 403 hint when scopes endpoint forbids', () => {
    comp.scopes$.subscribe();
    const scopes = httpMock.expectOne('/api/mod/scopes');
    scopes.flush('forbidden', { status: 403, statusText: 'Forbidden' });
    expect(comp.errorMessage).toContain('mod or admin');
  });
});
