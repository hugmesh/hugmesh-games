import { ComponentFixture, TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideZoneChangeDetection } from '@angular/core';
import { HttpRequest, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';

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
  const PROJECT_B = { id: 'p2', slug: 'flatland', displayName: 'Flatland' } as any;
  const PREVIEW_URL = '/api/rpg/avatar-preview.svg';
  // A predicate, not the bare string: HttpClientTestingBackend matches a
  // string against urlWithParams, and the preview carries ?expression&w&h.
  const isPreview = (r: HttpRequest<unknown>) => r.url === PREVIEW_URL;
  /** Mirrors PREVIEW_DEBOUNCE_MS in the component. */
  const DEBOUNCE_MS = 250;
  const SVG = '<svg xmlns="http://www.w3.org/2000/svg" width="256" height="384"><circle r="4"/></svg>';

  // The whole rpg_avatar_config row as the service answers it — every
  // column default of changelog 009 / CharacterSpec, restated here on
  // purpose so the spec is an INDEPENDENT check of the contract rather
  // than a mirror of the component's own constants.
  const DEFAULT_AVATAR = {
    id: null, projectId: 'p1',
    renderStyle: 'smooth', bodyShape: 'slim', headShape: 'round', hairStyle: 'short',
    outfitType: 'robe', hatType: 'none', heldMain: 'none', heldOff: 'none',
    backItem: 'none', faceItem: 'none', skinTextureAssetId: null,
    heightScale: 1, headScale: 1, neckScale: 1, chestScale: 1, waistScale: 1,
    shoulderScale: 1, armLengthScale: 1, armThicknessScale: 1, legLengthScale: 1,
    legThicknessScale: 1, handScale: 1, footScale: 1,
    bodyColor: '#3f59b3', headColor: '#e6c8a0', hatColor: '#202020', hairColor: '#3a2a1c',
    accentColor: '#d8b45a', itemColor: '#8a8f98', eyeColor: '#2b2b33',
  };

  /** Every field the PUT / preview POST is allowed to carry. */
  const WIRE_FIELDS = [
    'renderStyle', 'bodyShape', 'headShape', 'hairStyle', 'outfitType', 'hatType',
    'heldMain', 'heldOff', 'backItem', 'faceItem', 'skinTextureAssetId',
    'heightScale', 'headScale', 'neckScale', 'chestScale', 'waistScale', 'shoulderScale',
    'armLengthScale', 'armThicknessScale', 'legLengthScale', 'legThicknessScale',
    'handScale', 'footScale',
    'bodyColor', 'headColor', 'hatColor', 'hairColor', 'accentColor', 'itemColor', 'eyeColor',
  ];

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

  // destroy() first: it tears the preview pipeline down, so a debounce
  // timer a test left queued can't fire a render request after verify().
  afterEach(() => { fixture.destroy(); httpMock.verify(); });

  function el(): HTMLElement { return fixture.nativeElement as HTMLElement; }

  /** Render once (ngOnInit) and drain the projects-list GET the async pipe fires. */
  function boot(): void {
    fixture.detectChanges();
    httpMock.match('/api/rpg/projects').forEach(r => r.flush([PROJECT]));
  }

  /**
   * Select a project (the p1 fixture by default) and flush the per-project
   * fan-out GETs selectProject() issues — including the avatar-config load.
   */
  function selectProject(opts: { npcs?: unknown[]; assets?: unknown[]; avatar?: unknown } = {},
                         project: { id: string } = PROJECT): void {
    comp.selectProject(project as any);
    httpMock.expectOne(`/api/rpg/projects/${project.id}/buildings`).flush([]);
    httpMock.expectOne(`/api/rpg/projects/${project.id}/areas`).flush([]);
    httpMock.expectOne(`/api/rpg/projects/${project.id}/rooms`).flush([]);
    httpMock.expectOne(`/api/rpg/projects/${project.id}/exits`).flush([]);
    httpMock.expectOne(`/api/rpg/projects/${project.id}/items`).flush([]);
    httpMock.expectOne(`/api/rpg/projects/${project.id}/npcs`).flush(opts.npcs ?? []);
    httpMock.expectOne(`/api/rpg/projects/${project.id}/assets`).flush(opts.assets ?? []);
    const avatarReq = httpMock.expectOne(`/api/rpg/projects/${project.id}/avatar-config`);
    expect(avatarReq.request.method).toBe('GET');
    avatarReq.flush(opts.avatar ?? DEFAULT_AVATAR);
    // Image-kind assets each trigger a preview-bytes GET (object URL).
    for (const a of (opts.assets ?? []) as { id: string; mimeType: string }[]) {
      if (a.mimeType?.startsWith('image/')) {
        httpMock.expectOne(`/api/rpg/projects/${project.id}/assets/${a.id}`).flush(new Blob(['<svg/>']));
      }
    }
    fixture.detectChanges();
  }

  /** fakeAsync only — advance past the debounce and answer the render. */
  function flushPreview(svg = SVG): TestRequest {
    tick(DEBOUNCE_MS);
    const req = httpMock.expectOne(isPreview);
    req.flush(svg);
    fixture.detectChanges();
    return req;
  }

  /** fakeAsync only — drain whatever render a test left queued, so the
   *  spec doesn't fail on a leftover timer or an unflushed request. */
  function drainPreview(): void {
    tick(DEBOUNCE_MS);
    httpMock.match(isPreview).forEach(r => r.flush(SVG));
    fixture.detectChanges();
  }

  function previewImg(): HTMLImageElement | null {
    return el().querySelector('img.avatar-preview');
  }

  it('renders the Characters pane only once a project is selected', () => {
    boot();
    expect(el().querySelector('.characters-pane')).toBeNull();

    selectProject();
    const pane = el().querySelector('.characters-pane');
    expect(pane).not.toBeNull();
    expect(pane!.querySelector('h2')!.textContent).toContain('Characters');
    expect(pane!.textContent).toContain('presses E');
  });

  it('renders the preview from POST avatar-preview.svg and draws nothing locally', fakeAsync(() => {
    boot();
    selectProject();
    const req = flushPreview();

    expect(req.request.method).toBe('POST');
    expect(req.request.responseType).toBe('text');
    expect(req.request.params.get('expression')).toBe('neutral');
    expect(req.request.params.get('w')).toBe('256');
    expect(req.request.params.get('h')).toBe('384');
    // The POSTed body is the config itself — the server renders it unsaved.
    expect(Object.keys(req.request.body as object).sort()).toEqual([...WIRE_FIELDS].sort());

    // Decision 028: no second geometry table on the client. No canvas
    // anywhere in the component, and no drawing routine left on the class.
    expect(el().querySelector('canvas')).toBeNull();
    expect((comp as unknown as Record<string, unknown>)['drawAvatarPreview']).toBeUndefined();

    const img = previewImg()!;
    expect(img).not.toBeNull();
    expect(img.getAttribute('src')!.startsWith('data:image/svg+xml')).toBeTrue();
    expect(decodeURIComponent(img.getAttribute('src')!)).toContain('<svg');
    expect(img.getAttribute('alt')).toContain('preview');
  }));

  it('debounces rapid control changes into ONE render request', fakeAsync(() => {
    boot();
    selectProject();
    flushPreview();

    const chest = comp.proportions.find(p => p.key === 'chest')!;
    comp.setProportion(chest, 1.2);
    tick(100);
    comp.setProportion(chest, 1.3);
    tick(100);
    comp.setColor(comp.colorSlots[0], '#112233');
    // Still inside the debounce window — nothing has gone out yet.
    httpMock.expectNone(isPreview);

    tick(DEBOUNCE_MS);
    const reqs = httpMock.match(isPreview);
    expect(reqs.length).toBe(1);
    const body = reqs[0].request.body as Record<string, unknown>;
    expect(body['chestScale']).toBe(1.3);
    expect(body['bodyColor']).toBe('#112233');
    reqs[0].flush(SVG);
  }));

  it('keeps the previous preview on screen while the next render is in flight', fakeAsync(() => {
    boot();
    selectProject();
    flushPreview('<svg xmlns="http://www.w3.org/2000/svg" id="first"/>');
    const first = previewImg()!.getAttribute('src')!;
    expect(decodeURIComponent(first)).toContain('id="first"');

    comp.setRenderStyle('lowpoly');
    tick(DEBOUNCE_MS);
    fixture.detectChanges();
    const inFlight = httpMock.expectOne(isPreview);
    expect(previewImg()!.getAttribute('src')).toBe(first);   // no flicker to blank

    inFlight.flush('<svg xmlns="http://www.w3.org/2000/svg" id="second"/>');
    fixture.detectChanges();
    expect(decodeURIComponent(previewImg()!.getAttribute('src')!)).toContain('id="second"');
  }));

  it('switching projects cancels the in-flight render — the old hero never paints on the new pane', fakeAsync(() => {
    boot();
    selectProject();
    flushPreview('<svg xmlns="http://www.w3.org/2000/svg" id="hero-a"/>');

    // A render for project A goes out and is still in flight...
    comp.setRenderStyle('lowpoly');
    tick(DEBOUNCE_MS);
    const inFlightA = httpMock.expectOne(isPreview);

    // ...when the author switches to project B. The switch must abort A's
    // request THROUGH the pipeline: clearing previewSrc alone cannot, because
    // switchMap only cancels its inner request on a new emission — which is
    // exactly how A's late response used to escape and repaint A's hero over
    // B's pane until B's config GET + debounce + a second round trip elapsed.
    selectProject({ avatar: { ...DEFAULT_AVATAR, projectId: 'p2', bodyShape: 'stocky' } }, PROJECT_B);
    expect(inFlightA.cancelled).toBeTrue();

    // A's response landing late must not reach the pane. (Guarded flush so a
    // response-filtering fix is exercised the same way; under the cancelling
    // fix the backend forbids flushing an aborted request.)
    if (!inFlightA.cancelled) {
      inFlightA.flush('<svg xmlns="http://www.w3.org/2000/svg" id="hero-a"/>');
    }
    fixture.detectChanges();
    const afterSwitch = previewImg()?.getAttribute('src') ?? '';
    expect(decodeURIComponent(afterSwitch)).not.toContain('id="hero-a"');

    // B's own render — issued from B's freshly-loaded config — lands and shows.
    const reqB = flushPreview('<svg xmlns="http://www.w3.org/2000/svg" id="hero-b"/>');
    expect((reqB.request.body as Record<string, unknown>)['bodyShape']).toBe('stocky');
    expect(decodeURIComponent(previewImg()!.getAttribute('src')!)).toContain('id="hero-b"');
  }));

  it('surfaces a preview 400 in role="alert" and keeps the last good frame', fakeAsync(() => {
    boot();
    selectProject();
    flushPreview();
    const good = previewImg()!.getAttribute('src')!;

    comp.setColor(comp.colorSlots[0], 'not-a-hex');
    tick(DEBOUNCE_MS);
    httpMock.expectOne(isPreview)
      .flush('bodyColor must be a #rrggbb hex colour', { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    const alert = el().querySelector('.avatar-preview-wrap p[role="alert"]');
    expect(alert).not.toBeNull();
    expect(alert!.textContent).toContain('bodyColor must be a #rrggbb hex colour');
    expect(alert!.textContent).toContain('render preview failed (400)');
    expect(previewImg()!.getAttribute('src')).toBe(good);
  }));

  it('renders one bounded slider per CharacterSpec proportion', fakeAsync(() => {
    boot();
    selectProject();
    flushPreview();

    expect(comp.proportions.length).toBe(12);
    for (const p of comp.proportions) {
      const input = el().querySelector(
        `input[type="range"][name="avatar-${p.wire}"]`) as HTMLInputElement;
      expect(input).withContext(p.wire).not.toBeNull();
      expect(Number(input.min)).withContext(`${p.wire} min`).toBe(p.min);
      expect(Number(input.max)).withContext(`${p.wire} max`).toBe(p.max);
      expect(input.closest('label')!.textContent).withContext(p.wire).toContain(p.label);
    }
    // The four the feature exists for, at their documented humanoid bounds.
    const byKey = (k: string) => comp.proportions.find(p => p.key === k)!;
    expect([byKey('chest').min, byKey('chest').max]).toEqual([0.70, 1.40]);
    expect([byKey('height').min, byKey('height').max]).toEqual([0.70, 1.40]);
    expect([byKey('legLength').min, byKey('legLength').max]).toEqual([0.75, 1.30]);
    expect([byKey('armLength').min, byKey('armLength').max]).toEqual([0.75, 1.30]);
  }));

  it('renders every whitelisted token in the composer controls', fakeAsync(() => {
    boot();
    selectProject();
    flushPreview();

    const values = (name: string) => Array.from(
      (el().querySelector(`select[name="${name}"]`) as HTMLSelectElement).options,
    ).map(o => o.value);

    expect(values('avatarBody')).toEqual(['slim', 'stocky', 'round']);
    expect(values('avatarHead')).toEqual(['round', 'square', 'oval', 'tapered']);
    expect(values('avatarHair')).toEqual(['none', 'short', 'long', 'ponytail', 'spiky', 'bun', 'braid']);
    expect(values('avatarOutfit')).toEqual(['robe', 'tunic', 'armor', 'dress', 'jumpsuit', 'bare']);
    expect(values('avatarHat')).toEqual(
      ['none', 'cap', 'cone', 'top_hat', 'helm', 'crown', 'hood', 'bandana', 'straw']);
    expect(values('avatarHeldMain')).toEqual(
      ['none', 'sword', 'staff', 'axe', 'bow', 'wand', 'pickaxe', 'lantern', 'book']);
    expect(values('avatarHeldOff')).toEqual(['none', 'shield', 'torch', 'book', 'lantern', 'orb']);
    expect(values('avatarBackItem')).toEqual(['none', 'cape', 'backpack', 'wings', 'quiver', 'scabbard']);
    expect(values('avatarFaceItem')).toEqual(['none', 'glasses', 'mask', 'visor', 'eyepatch']);
    expect(values('avatarExpression')).toEqual(
      ['neutral', 'happy', 'sad', 'angry', 'surprised', 'wink', 'sleepy', 'determined', 'shout']);

    const styles = Array.from(
      el().querySelectorAll('input[type="radio"][name="avatarRenderStyle"]')) as HTMLInputElement[];
    expect(styles.map(i => i.value)).toEqual(['smooth', 'lowpoly']);
    expect(styles[0].checked).toBeTrue();
    // The retro mode has to READ as the retro mode.
    expect(styles[1].closest('label')!.textContent).toContain('N64');

    const colors = Array.from(
      el().querySelectorAll('.color-grid input[type="color"]')) as HTMLInputElement[];
    expect(colors.map(i => i.getAttribute('name'))).toEqual(
      comp.colorSlots.map(c => `avatar-${c.wire}`));
    expect(colors.length).toBe(7);
  }));

  it('gives every composer control a wrapping <label>', fakeAsync(() => {
    boot();
    selectProject();
    flushPreview();

    const pane = el().querySelector('.characters-pane')!;
    const controls = Array.from(pane.querySelectorAll('input, select, textarea'));
    expect(controls.length).toBeGreaterThan(25);
    for (const c of controls) {
      expect(c.closest('label')).withContext(c.getAttribute('name') ?? c.tagName).not.toBeNull();
    }
  }));

  it('populates the composer from GET avatar-config on project select', fakeAsync(() => {
    boot();
    selectProject({
      avatar: {
        ...DEFAULT_AVATAR, id: 'cfg-1', renderStyle: 'lowpoly', bodyShape: 'stocky',
        headShape: 'tapered', hairStyle: 'ponytail', outfitType: 'armor', hatType: 'helm',
        heldMain: 'sword', heldOff: 'shield', backItem: 'cape', faceItem: 'visor',
        heightScale: 1.1, chestScale: 1.25, legLengthScale: 0.8, bodyColor: '#e21422',
      },
    });
    flushPreview();

    expect(comp.avatarConfig.renderStyle).toBe('lowpoly');
    expect(comp.avatarConfig.chestScale).toBe(1.25);
    expect(comp.avatarConfig.bodyColor).toBe('#e21422');

    const value = (name: string) => (el().querySelector(`select[name="${name}"]`) as HTMLSelectElement).value;
    expect(value('avatarBody')).toBe('stocky');
    expect(value('avatarHead')).toBe('tapered');
    expect(value('avatarHair')).toBe('ponytail');
    expect(value('avatarOutfit')).toBe('armor');
    expect(value('avatarHat')).toBe('helm');
    expect(value('avatarHeldMain')).toBe('sword');
    expect(value('avatarHeldOff')).toBe('shield');
    expect(value('avatarBackItem')).toBe('cape');
    expect(value('avatarFaceItem')).toBe('visor');

    const lowpoly = el().querySelector(
      'input[name="avatarRenderStyle"][value="lowpoly"]') as HTMLInputElement;
    expect(lowpoly.checked).toBeTrue();
    const chest = el().querySelector('input[name="avatar-chestScale"]') as HTMLInputElement;
    expect(Number(chest.value)).toBe(1.25);
    expect(chest.closest('label')!.textContent).toContain('1.25×');
  }));

  it('saveAvatarConfig() PUTs the whole whitelisted payload and folds the row back', () => {
    comp.selectedProject = PROJECT;
    comp.avatarConfig = {
      ...comp.defaultAvatarConfig(),
      renderStyle: 'lowpoly', bodyShape: 'round', headShape: 'square', hairStyle: 'bun',
      outfitType: 'dress', hatType: 'crown', heldMain: 'staff', heldOff: 'orb',
      backItem: 'wings', faceItem: 'glasses', chestScale: 1.2, eyeColor: '#112233',
    };
    comp.saveAvatarConfig();

    const req = httpMock.expectOne('/api/rpg/projects/p1/avatar-config');
    expect(req.request.method).toBe('PUT');
    const body = req.request.body as Record<string, unknown>;
    // Exactly the whitelisted fields — the server stamps id/projectId, and
    // expression is render-time so it must never be persisted.
    expect(Object.keys(body).sort()).toEqual([...WIRE_FIELDS].sort());
    expect(body['id']).toBeUndefined();
    expect(body['expression']).toBeUndefined();
    expect(body['renderStyle']).toBe('lowpoly');
    expect(body['hairStyle']).toBe('bun');
    expect(body['backItem']).toBe('wings');
    expect(body['chestScale']).toBe(1.2);
    expect(body['eyeColor']).toBe('#112233');
    expect(body['headColor']).toBe('#e6c8a0');   // the SKIN slot's column

    req.flush({ ...DEFAULT_AVATAR, ...body, id: 'cfg-9', projectId: 'p1' });
    expect(comp.avatarConfig.id).toBe('cfg-9');
    expect(comp.successBanner).toBe('Hero character saved.');
  });

  it('clamps a proportion to its humanoid bound, including at payload time', () => {
    const height = comp.proportions.find(p => p.key === 'height')!;

    comp.setProportion(height, 9.9);
    expect(comp.avatarConfig.heightScale).toBe(1.4);
    expect(comp.proportionLabel(height)).toBe('1.40×');

    comp.setProportion(height, 0.1);
    expect(comp.avatarConfig.heightScale).toBe(0.7);

    comp.setProportion(height, Number.NaN);
    expect(comp.avatarConfig.heightScale).toBe(1.0);

    // A rogue direct assignment is still clamped on the way out.
    comp.selectedProject = PROJECT;
    comp.avatarConfig.legLengthScale = 99;
    comp.saveAvatarConfig();
    const req = httpMock.expectOne('/api/rpg/projects/p1/avatar-config');
    expect((req.request.body as Record<string, number>)['legLengthScale']).toBe(1.3);
    req.flush({ ...DEFAULT_AVATAR, id: 'cfg-2' });
  });

  it('reset restores every default but keeps the row identity', fakeAsync(() => {
    boot();
    selectProject({
      avatar: {
        ...DEFAULT_AVATAR, id: 'cfg-1', renderStyle: 'lowpoly', bodyShape: 'stocky',
        hatType: 'crown', chestScale: 1.3, heightScale: 0.8, bodyColor: '#e21422',
        skinTextureAssetId: 'a-tex-id',
      },
    });
    flushPreview();

    comp.resetAvatarConfig();
    expect(comp.avatarConfig.renderStyle).toBe('smooth');
    expect(comp.avatarConfig.bodyShape).toBe('slim');
    expect(comp.avatarConfig.hatType).toBe('none');
    expect(comp.avatarConfig.bodyColor).toBe('#3f59b3');
    for (const p of comp.proportions) expect(comp.avatarConfig[p.wire]).withContext(p.wire).toBe(p.dflt);
    for (const c of comp.colorSlots) expect(comp.avatarConfig[c.wire]).withContext(c.wire).toBe(c.dflt);
    // Row identity and the texture (no control for it here) survive.
    expect(comp.avatarConfig.id).toBe('cfg-1');
    expect(comp.avatarConfig.skinTextureAssetId).toBe('a-tex-id');
    expect(comp.successBanner).toContain('reset to defaults');

    // A reset re-renders through the same server route.
    const req = flushPreview();
    expect((req.request.body as Record<string, unknown>)['bodyShape']).toBe('slim');
  }));

  it('expression drives the preview only — it never reaches the saved payload', fakeAsync(() => {
    boot();
    selectProject();
    flushPreview();

    comp.setExpression('angry');
    tick(DEBOUNCE_MS);
    const req = httpMock.expectOne(isPreview);
    expect(req.request.params.get('expression')).toBe('angry');
    expect(Object.keys(req.request.body as object)).not.toContain('expression');
    req.flush(SVG);

    comp.saveAvatarConfig();
    const put = httpMock.expectOne('/api/rpg/projects/p1/avatar-config');
    expect(Object.keys(put.request.body as object)).not.toContain('expression');
    put.flush({ ...DEFAULT_AVATAR, id: 'cfg-3' });
    drainPreview();
  }));

  it('downloads the portable Character Document', () => {
    comp.selectedProject = PROJECT;
    const click = spyOn(HTMLAnchorElement.prototype, 'click');
    spyOn(URL, 'createObjectURL').and.returnValue('blob:fake');
    spyOn(URL, 'revokeObjectURL');

    comp.downloadCharacter();
    const req = httpMock.expectOne('/api/rpg/projects/p1/avatar.json');
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('blob');
    req.flush(new Blob(['{"kind":"hugmesh.character"}'], { type: 'application/json' }));

    expect(click).toHaveBeenCalled();
    expect(comp.successBanner).toContain('downloaded');
  });

  it('imports a pasted Character Document, rejecting bad JSON before any request', () => {
    comp.selectedProject = PROJECT;

    comp.importDoc = 'not json at all';
    comp.importCharacter();
    expect(comp.importError).toContain('valid JSON');
    httpMock.expectNone('/api/rpg/projects/p1/avatar.json');

    comp.importDoc = JSON.stringify({
      schemaVersion: 1, kind: 'hugmesh.character', shape: { body: 'round' },
    });
    comp.importCharacter();
    const req = httpMock.expectOne('/api/rpg/projects/p1/avatar.json');
    expect(req.request.method).toBe('POST');
    expect((req.request.body as Record<string, unknown>)['kind']).toBe('hugmesh.character');
    req.flush({ ...DEFAULT_AVATAR, id: 'cfg-7', bodyShape: 'round' });

    expect(comp.avatarConfig.bodyShape).toBe('round');
    expect(comp.importDoc).toBe('');
    expect(comp.successBanner).toContain('imported');
  });

  it('surfaces the server message on an import 400, in the inline error slot', fakeAsync(() => {
    boot();
    selectProject();
    flushPreview();

    comp.importDoc = JSON.stringify({ kind: 'hugmesh.character', shape: { hair: 'mullet' } });
    comp.importCharacter();
    httpMock.expectOne('/api/rpg/projects/p1/avatar.json')
      .flush("unknown hairStyle 'mullet'", { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(comp.importError).toContain("unknown hairStyle 'mullet'");
    const alerts = Array.from(el().querySelectorAll('.characters-pane p[role="alert"]'))
      .map(p => p.textContent ?? '');
    expect(alerts.some(t => t.includes("unknown hairStyle 'mullet'"))).toBeTrue();
    // The paste box keeps its content so the author can fix it in place.
    expect(comp.importDoc).not.toBe('');
  }));

  it('surfaces the server message even when Angular could not JSON-parse the error body', () => {
    comp.selectedProject = PROJECT;
    comp.saveAvatarConfig();
    // What a text/plain 400 actually looks like on a JSON-responseType
    // request in a real browser: the parse failed, the raw text is on .text.
    httpMock.expectOne('/api/rpg/projects/p1/avatar-config').flush(
      { error: new SyntaxError('Unexpected token u'), text: "unknown hatType 'sombrero'" },
      { status: 400, statusText: 'Bad Request' });

    expect(comp.errorBanner).toContain('save avatar config failed (400)');
    expect(comp.errorBanner).toContain("unknown hatType 'sombrero'");
  });

  it('surfaces a PUT failure on the error banner without clobbering the composer', () => {
    comp.selectedProject = PROJECT;
    comp.avatarConfig = { ...comp.defaultAvatarConfig(), bodyShape: 'stocky' };
    comp.saveAvatarConfig();
    httpMock.expectOne('/api/rpg/projects/p1/avatar-config')
      .flush("unknown bodyShape 'X'", { status: 400, statusText: 'Bad Request' });
    expect(comp.errorBanner).toContain('save avatar config failed (400)');
    expect(comp.errorBanner).toContain("unknown bodyShape 'X'");
    expect(comp.avatarConfig.bodyShape).toBe('stocky');
  });

  it('loads a .json file into the paste box', async () => {
    const file = new File(['{"kind":"hugmesh.character"}'], 'hero.json', { type: 'application/json' });
    comp.onImportFile({ target: { files: [file] } } as unknown as Event);
    // Poll rather than a fixed wait: file.text() resolves on its own
    // schedule, and a 10 ms bet loses on a loaded CI box.
    for (let i = 0; i < 100 && !comp.importDoc; i++) {
      await new Promise(r => setTimeout(r, 10));
    }
    expect(comp.importDoc).toContain('hugmesh.character');
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

  it('changing the roster picker updates the NPC portrait preview locally', fakeAsync(() => {
    boot();
    selectProject({
      npcs: [{ id: 'n1', projectId: 'p1', slug: 'town-greeter', name: 'Guide Bramble', portraitAssetId: 'a1' }],
      assets: [
        { id: 'a1', projectId: 'p1', slug: 'greeter-portrait', kind: 'portrait', mimeType: 'image/svg+xml', byteSize: 1 },
        { id: 'a2', projectId: 'p1', slug: 'other-portrait', kind: 'portrait', mimeType: 'image/svg+xml', byteSize: 1 },
      ],
    });
    flushPreview();

    const picker = el().querySelector('.npc-roster select') as HTMLSelectElement;
    picker.value = 'a2';
    picker.dispatchEvent(new Event('change'));
    tick();
    fixture.detectChanges();

    expect(comp.npcs[0].portraitAssetId).toBe('a2');
    const img = el().querySelector('.npc-roster img.portrait-thumb') as HTMLImageElement;
    expect(img.getAttribute('src')).toBe(comp.assetUrls['a2']);
  }));
});
