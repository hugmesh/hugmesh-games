import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

// Cross-project import: the AdminRemoteComponent ships from its own
// Angular project under projects/admin/, but we test it from the
// host's src/ tree because the host's karma builder roots its
// require.context at src/ — files outside that root never reach the
// spec discovery layer no matter what tsconfig.spec.json's include
// says. The relative path resolves to the source class at compile
// time; at runtime the host fetches the compiled module via
// federation, but tests don't go through that seam.
import { AdminRemoteComponent } from '../../projects/admin/src/app/admin-remote.component';

// Coverage targets: the BFF wiring that used to live in the host's
// deleted AdminService. The component does the HTTP calls inline now,
// and the 403 → "log in with an isAdmin account" hint is a
// user-visible contract worth pinning.
describe('AdminRemoteComponent', () => {
  let component: AdminRemoteComponent;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    component = TestBed.createComponent(AdminRemoteComponent).componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  describe('refresh', () => {
    it('populates stats$ from /api/admin/stats on success', () => {
      let captured: unknown = null;
      component.refresh();
      component.stats$.subscribe(s => captured = s);

      const req = httpMock.expectOne('/api/admin/stats');
      expect(req.request.method).toBe('GET');
      req.flush({
        totalUsers: 4, totalSessions: 12, currentlyActive: 1,
        averageSessionMinutes: 7.5, totalCharacters: 4, perUser: [],
      });

      expect(captured).toEqual(jasmine.objectContaining({ totalUsers: 4, currentlyActive: 1 }));
    });

    it('sets the admin-required hint on 403 from /api/admin/stats', () => {
      component.refresh();
      let stats: unknown = 'unset';
      // Subscribe to both observables — `http.get(...)` is cold, so the
      // request never fires (and the mock can't match it) without an
      // active subscription.
      component.stats$.subscribe(s => stats = s);
      component.sessions$.subscribe();

      httpMock.expectOne('/api/admin/stats').flush(null, { status: 403, statusText: 'Forbidden' });
      httpMock.expectOne('/api/admin/sessions').flush([]);

      expect(stats).toBeNull();
      expect(component.errorMessage).toContain('isAdmin');
    });

    it('uses a generic failure message on non-403 errors', () => {
      component.refresh();
      component.stats$.subscribe();
      component.sessions$.subscribe();

      httpMock.expectOne('/api/admin/stats').flush(null, { status: 500, statusText: 'Server Error' });
      httpMock.expectOne('/api/admin/sessions').flush([]);

      expect(component.errorMessage).toBe('Failed to load stats.');
    });
  });

  describe('kick', () => {
    it('does nothing if the operator declines the confirm prompt', () => {
      spyOn(window, 'confirm').and.returnValue(false);
      component.kick('player-1');
      httpMock.expectNone('/api/admin/sessions/player-1');
    });

    it('DELETEs /api/admin/sessions/{id} and refreshes after confirm', fakeAsync(() => {
      spyOn(window, 'confirm').and.returnValue(true);
      component.kick('player-1');

      const del = httpMock.expectOne('/api/admin/sessions/player-1');
      expect(del.request.method).toBe('DELETE');
      del.flush(null);
      tick();

      // refresh() reassigns stats$/sessions$ after DELETE resolves —
      // subscribe so the cold observables actually fire their HTTP
      // requests, then verify the mock saw them.
      component.stats$.subscribe();
      component.sessions$.subscribe();
      httpMock.expectOne('/api/admin/stats').flush({
        totalUsers: 0, totalSessions: 0, currentlyActive: 0,
        averageSessionMinutes: 0, totalCharacters: 0, perUser: [],
      });
      httpMock.expectOne('/api/admin/sessions').flush([]);
    }));
  });
});
