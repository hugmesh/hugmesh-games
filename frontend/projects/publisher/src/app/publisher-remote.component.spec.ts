import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { PublisherRemoteComponent } from './publisher-remote.component';

describe('PublisherRemoteComponent', () => {
  let fixture: ComponentFixture<PublisherRemoteComponent>;
  let comp: PublisherRemoteComponent;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
      imports: [PublisherRemoteComponent],
    });
    fixture = TestBed.createComponent(PublisherRemoteComponent);
    comp = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    // First render subscribes `scopes$ | async` — settle the init GET so
    // every test starts with no outstanding requests.
    httpMock.expectOne('/api/publisher/scopes').flush([]);
  });

  afterEach(() => httpMock.verify());

  it('fetches publisher scopes on init', () => {
    // A fresh subscription to the cold scopes$ re-issues the GET.
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
    // Grant success re-pulls the per-game mod list (scopes$ is untouched,
    // so the already-subscribed async pipe issues no new scopes GET).
    fixture.detectChanges();
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
    // Revoke success re-pulls the per-game mod list once rendered.
    fixture.detectChanges();
    httpMock.expectOne('/api/publisher/games/three-wizards/mods').flush([]);
  });

  it('"Build a new game" card copy offers the Character Maker', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent!.replace(/\s+/g, ' ');
    expect(text).toContain(
      "design your playable hero's 3D avatar and NPC dialogue portraits in the maker's Characters pane");
    expect(text).toContain('Includes the Character Maker (hero avatar + dialogue portraits).');
  });

  it('Launch RPG Maker still opens /rpg-maker in a new tab', () => {
    const openSpy = spyOn(window, 'open');
    const btn = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button'))
      .find(b => b.textContent!.includes('Launch RPG Maker'));
    expect(btn).toBeTruthy();
    btn!.click();
    expect(openSpy).toHaveBeenCalledWith('/rpg-maker', '_blank', 'noopener');
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
