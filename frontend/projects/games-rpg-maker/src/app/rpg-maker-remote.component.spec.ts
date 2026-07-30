import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZoneChangeDetection } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { RpgMakerRemoteComponent } from './rpg-maker-remote.component';

// Discovered by the host karma glob (the host project root is '', so
// `**/*.spec.ts` reaches into projects/). The component is standalone +
// ChangeDetectionStrategy.Eager with zone-era plain fields, so the
// TestBed restores zone change detection explicitly via
// provideZoneChangeDetection() — mirroring app.config.ts.
describe('RpgMakerRemoteComponent (Characters pane)', () => {
  let fixture: ComponentFixture<RpgMakerRemoteComponent>;
  let comp: RpgMakerRemoteComponent;
  let httpMock: HttpTestingController;

  const PROJECT = { id: 'p1', slug: 'six-realms', displayName: 'Six Realms' } as any;
  const DEFAULT_AVATAR = {
    id: null, projectId: 'p1', bodyShape: 'slim', headShape: 'round', hatType: 'none',
    heightScale: 1.0, bodyColor: '#3f59b3', headColor: '#e6c8a0', hatColor: '#202020',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [RpgMakerRemoteComponent],
      providers: [
        provideZoneChangeDetection(),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    fixture = TestBed.createComponent(RpgMakerRemoteComponent);
    comp = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function el(): HTMLElement { return fixture.nativeElement as HTMLElement; }

  /** Render once (ngOnInit) and drain the projects-list GET the async pipe fires. */
  function boot(): void {
    fixture.detectChanges();
    httpMock.match('/api/rpg/projects').forEach(r => r.flush([PROJECT]));
  }

  /**
   * Select the fixture project and flush the per-project fan-out GETs
   * selectProject() issues — including the new avatar-config load.
   */
  function selectProject(opts: { npcs?: unknown[]; assets?: unknown[]; avatar?: unknown } = {}): void {
    comp.selectProject(PROJECT);
    httpMock.expectOne('/api/rpg/projects/p1/buildings').flush([]);
    httpMock.expectOne('/api/rpg/projects/p1/areas').flush([]);
    httpMock.expectOne('/api/rpg/projects/p1/rooms').flush([]);
    httpMock.expectOne('/api/rpg/projects/p1/exits').flush([]);
    httpMock.expectOne('/api/rpg/projects/p1/items').flush([]);
    httpMock.expectOne('/api/rpg/projects/p1/npcs').flush(opts.npcs ?? []);
    httpMock.expectOne('/api/rpg/projects/p1/assets').flush(opts.assets ?? []);
    const avatarReq = httpMock.expectOne('/api/rpg/projects/p1/avatar-config');
    expect(avatarReq.request.method).toBe('GET');
    avatarReq.flush(opts.avatar ?? DEFAULT_AVATAR);
    // Image-kind assets each trigger a preview-bytes GET (object URL).
    for (const a of (opts.assets ?? []) as { id: string; mimeType: string }[]) {
      if (a.mimeType?.startsWith('image/')) {
        httpMock.expectOne(`/api/rpg/projects/p1/assets/${a.id}`).flush(new Blob(['<svg/>']));
      }
    }
    fixture.detectChanges();
  }

  async function settle(): Promise<void> {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('renders the Characters pane only once a project is selected', () => {
    boot();
    expect(el().querySelector('.characters-pane')).toBeNull();

    selectProject();
    const pane = el().querySelector('.characters-pane');
    expect(pane).not.toBeNull();
    expect(pane!.querySelector('h2')!.textContent).toContain('Characters');
    expect(pane!.querySelector('canvas.avatar-preview')).not.toBeNull();
    expect(pane!.textContent).toContain('presses E');
  });

  it('populates the composer from GET avatar-config on project select', async () => {
    boot();
    selectProject({
      avatar: {
        id: 'cfg-1', projectId: 'p1', bodyShape: 'stocky', headShape: 'square',
        hatType: 'top_hat', heightScale: 1.1, bodyColor: '#e21422',
        headColor: '#e6b58c', hatColor: '#1a1a1a',
      },
    });
    await settle();

    expect(comp.avatarConfig.bodyShape).toBe('stocky');
    expect(comp.avatarConfig.hatType).toBe('top_hat');
    expect(comp.avatarConfig.heightScale).toBe(1.1);
    expect(comp.avatarConfig.bodyColor).toBe('#e21422');
    const body = el().querySelector('select[name="avatarBody"]') as HTMLSelectElement;
    const head = el().querySelector('select[name="avatarHead"]') as HTMLSelectElement;
    const hat = el().querySelector('select[name="avatarHat"]') as HTMLSelectElement;
    expect(body.value).toBe('stocky');
    expect(head.value).toBe('square');
    expect(hat.value).toBe('top_hat');
    expect(el().querySelector('.height-readout')!.textContent).toContain('1.10');
  });

  it('saveAvatarConfig() PUTs exactly the whitelisted payload and folds the saved row back', () => {
    comp.selectedProject = PROJECT;
    comp.avatarConfig = {
      id: null, bodyShape: 'round', headShape: 'square', hatType: 'cone',
      heightScale: 0.9, bodyColor: '#7c4dff', headColor: '#e6c8a0', hatColor: '#202020',
    };
    comp.saveAvatarConfig();

    const req = httpMock.expectOne('/api/rpg/projects/p1/avatar-config');
    expect(req.request.method).toBe('PUT');
    // Exactly the seven whitelisted fields — the server stamps id/projectId.
    expect(req.request.body).toEqual({
      bodyShape: 'round', headShape: 'square', hatType: 'cone', heightScale: 0.9,
      bodyColor: '#7c4dff', headColor: '#e6c8a0', hatColor: '#202020',
    });
    req.flush({ ...DEFAULT_AVATAR, ...req.request.body, id: 'cfg-9', projectId: 'p1' });
    expect(comp.avatarConfig.id).toBe('cfg-9');
    expect(comp.successBanner).toBe('Hero avatar saved.');
  });

  it('clamps the height scale to the server range [0.7, 1.4]', () => {
    comp.selectedProject = PROJECT;

    comp.onHeightChange(9.9);
    expect(comp.avatarConfig.heightScale).toBe(1.4);
    expect(comp.heightLabel()).toBe('1.40×');

    comp.onHeightChange(0.1);
    expect(comp.avatarConfig.heightScale).toBe(0.7);

    comp.onHeightChange(Number.NaN);
    expect(comp.avatarConfig.heightScale).toBe(1.0);

    // A rogue direct assignment is still clamped at save time.
    comp.avatarConfig.heightScale = 99;
    comp.saveAvatarConfig();
    const req = httpMock.expectOne('/api/rpg/projects/p1/avatar-config');
    expect((req.request.body as { heightScale: number }).heightScale).toBe(1.4);
    req.flush({ ...DEFAULT_AVATAR, heightScale: 1.4, id: 'cfg-2' });
  });

  it('lists NPCs in the portrait roster with thumbnail, dialogue snippet and picker', () => {
    boot();
    selectProject({
      npcs: [{
        id: 'n1', projectId: 'p1', slug: 'town-greeter', name: 'Guide Bramble',
        dialogueText: 'Well met, traveller!', portraitAssetId: 'a1',
      }],
      assets: [{
        id: 'a1', projectId: 'p1', slug: 'greeter-portrait', kind: 'portrait',
        mimeType: 'image/svg+xml', byteSize: 1,
      }],
    });

    const row = el().querySelector('.npc-roster .npc-row')!;
    expect(row).not.toBeNull();
    expect(row.textContent).toContain('Guide Bramble');
    expect(row.textContent).toContain('Well met, traveller!');
    const img = row.querySelector('img.portrait-thumb') as HTMLImageElement;
    expect(img).not.toBeNull();
    expect(img.getAttribute('src')).toBe(comp.assetUrls['a1']);
    const picker = row.querySelector('select') as HTMLSelectElement;
    const options = Array.from(picker.options).map(o => o.textContent!.trim());
    expect(options).toContain('greeter-portrait (portrait)');
  });

  it('changing the roster picker updates the NPC portrait preview locally', async () => {
    boot();
    selectProject({
      npcs: [{ id: 'n1', projectId: 'p1', slug: 'town-greeter', name: 'Guide Bramble', portraitAssetId: 'a1' }],
      assets: [
        { id: 'a1', projectId: 'p1', slug: 'greeter-portrait', kind: 'portrait', mimeType: 'image/svg+xml', byteSize: 1 },
        { id: 'a2', projectId: 'p1', slug: 'other-portrait', kind: 'portrait', mimeType: 'image/svg+xml', byteSize: 1 },
      ],
    });
    await settle();

    const picker = el().querySelector('.npc-roster select') as HTMLSelectElement;
    picker.value = 'a2';
    picker.dispatchEvent(new Event('change'));
    await settle();

    expect(comp.npcs[0].portraitAssetId).toBe('a2');
    const img = el().querySelector('.npc-roster img.portrait-thumb') as HTMLImageElement;
    expect(img.getAttribute('src')).toBe(comp.assetUrls['a2']);
  });

  it('renders every whitelisted token in the composer selects', () => {
    boot();
    selectProject();

    const values = (name: string) => Array.from(
      (el().querySelector(`select[name="${name}"]`) as HTMLSelectElement).options,
    ).map(o => o.value);
    expect(values('avatarBody')).toEqual(['slim', 'stocky', 'round']);
    expect(values('avatarHead')).toEqual(['round', 'square']);
    expect(values('avatarHat')).toEqual(['none', 'cap', 'cone', 'top_hat']);
  });

  it('surfaces a PUT failure on the error banner without clobbering the composer', () => {
    comp.selectedProject = PROJECT;
    comp.avatarConfig = { ...DEFAULT_AVATAR, bodyShape: 'stocky' };
    comp.saveAvatarConfig();
    httpMock.expectOne('/api/rpg/projects/p1/avatar-config')
      .flush("unknown bodyShape 'X'", { status: 400, statusText: 'Bad Request' });
    expect(comp.errorBanner).toContain('save avatar config failed (400)');
    expect(comp.avatarConfig.bodyShape).toBe('stocky');
  });
});
