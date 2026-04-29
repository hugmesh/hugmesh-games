import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { PublisherRemoteComponent } from './publisher-remote.component';

describe('PublisherRemoteComponent', () => {
  let comp: PublisherRemoteComponent;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
      imports: [PublisherRemoteComponent],
    });
    const fixture = TestBed.createComponent(PublisherRemoteComponent);
    comp = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => httpMock.verify());

  it('fetches publisher scopes on init', () => {
    comp.scopes$.subscribe();
    const req = httpMock.expectOne('/api/publisher/scopes');
    expect(req.request.method).toBe('GET');
    req.flush([]);
  });

  it('grantMod() POSTs targetCasdoorId + gameSlug to /api/publisher/grant-mod', () => {
    comp.selectedSlug = 'three-wizards';
    comp.grantTarget = 'alice';
    comp.grantMod();
    const req = httpMock.expectOne('/api/publisher/grant-mod');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      targetCasdoorId: 'alice', gameSlug: 'three-wizards',
    });
    req.flush({ casdoorId: 'alice', gameSlug: 'three-wizards' });
    // refresh fans out scopes + per-game mods
    httpMock.expectOne('/api/publisher/scopes').flush([]);
    httpMock.expectOne('/api/publisher/games/three-wizards/mods').flush([]);
  });

  it('revokeMod() POSTs target + slug to /api/publisher/revoke-mod', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    comp.selectedSlug = 'three-wizards';
    comp.revokeMod('alice');
    const req = httpMock.expectOne('/api/publisher/revoke-mod');
    expect(req.request.body).toEqual({
      targetCasdoorId: 'alice', gameSlug: 'three-wizards',
    });
    req.flush(null);
    httpMock.expectOne('/api/publisher/games/three-wizards/mods').flush([]);
  });

  it('upload() POSTs the chosen File via FormData to /api/admin/games/upload', () => {
    const file = new File([new Blob(['x'])], 'bundle.zip', { type: 'application/zip' });
    comp.pickedFile = file;
    comp.upload();
    const req = httpMock.expectOne('/api/admin/games/upload');
    expect(req.request.method).toBe('POST');
    const fd = req.request.body as FormData;
    expect(fd.get('bundle')).toBeTruthy();
    req.flush({});
  });
});
