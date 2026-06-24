import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { FederationManifest, FederationService } from './federation.service';

describe('FederationService', () => {
  let svc: FederationService;
  let httpMock: HttpTestingController;

  const sample: FederationManifest = {
    generatedAt: '2026-04-25T11:00:00Z',
    remotes: [
      {
        slug: 'login', displayName: 'Login UI', kind: 'remote-shell',
        remoteEntryUrl: 'http://login:8080/remoteEntry.json',
        exposedModule: './LoginComponent', gameSlug: null,
        version: '0.1.0', metadata: null,
      },
      {
        slug: 'games-dodecahedron', displayName: 'Dodecahedron Demo', kind: 'remote-game',
        remoteEntryUrl: 'http://games-dodecahedron:8080/remoteEntry.json',
        exposedModule: './GameRoot', gameSlug: 'dodecahedron',
        version: '0.1.0', metadata: '{"chromeless":true}',
      },
    ],
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    svc = TestBed.inject(FederationService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('fetches the manifest exactly once and replays the result', (done) => {
    svc.load().subscribe(first => {
      expect(first.remotes.length).toBe(2);
      svc.load().subscribe(second => {
        expect(second).toBe(first);
        done();
      });
    });
    const req = httpMock.expectOne(r => r.url.endsWith('/api/federation/manifest'));
    req.flush(sample);
  });

  it('resolves a game slug to its remote-game entry', (done) => {
    svc.remoteForGame('dodecahedron').subscribe(remote => {
      expect(remote?.slug).toBe('games-dodecahedron');
      expect(remote?.kind).toBe('remote-game');
      done();
    });
    httpMock.expectOne(r => r.url.endsWith('/api/federation/manifest')).flush(sample);
  });

  it('returns undefined for an unknown game slug', (done) => {
    svc.remoteForGame('nope').subscribe(remote => {
      expect(remote).toBeUndefined();
      done();
    });
    httpMock.expectOne(r => r.url.endsWith('/api/federation/manifest')).flush(sample);
  });

  it('falls back to an empty manifest on HTTP error', (done) => {
    svc.load().subscribe(m => {
      expect(m.remotes).toEqual([]);
      done();
    });
    httpMock.expectOne(r => r.url.endsWith('/api/federation/manifest'))
            .error(new ProgressEvent('network'));
  });
});
